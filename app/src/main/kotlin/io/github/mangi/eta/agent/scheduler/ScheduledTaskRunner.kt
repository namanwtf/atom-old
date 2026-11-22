package io.github.mangi.eta.agent.scheduler

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ScheduledTask
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskRun
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import io.github.mangi.eta.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Scheduled-task execution coordinator.
 *
 * - Uniformly accepts both exact-alarm expiry triggers and UI/tool-triggered "Run now";
 * - Strictly reuses [AgentRuntimeClient] / [AgentRuntimeService], sharing the tool, permission, MCP, virtual-screen, archival, and cancellation pipelines;
 * - Handles per-task re-entry prevention, FGS and bounded WakeLock takeover within the Android Doze 10-second window, lock-screen GUI gating,
 *   a single hard-timeout guard, consecutive-failure circuit breaking, and result notification.
 */
internal object ScheduledTaskRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ActiveExecution(
        val taskId: String,
        val runId: String,
        val job: Job,
        val timedOut: AtomicBoolean = AtomicBoolean(false),
        val userCancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private val activeByTaskId = ConcurrentHashMap<String, ActiveExecution>()
    private val _activeTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val activeTaskIds: StateFlow<Set<String>> = _activeTaskIds.asStateFlow()

    fun isTaskRunning(taskId: String): Boolean = activeByTaskId.containsKey(taskId)

    /** Cancels a running scheduled task and notifies AgentRuntimeService to stop the model stream and tool resources. */
    fun cancelRunningTask(context: Context, taskId: String): Boolean {
        val active = activeByTaskId[taskId] ?: return false
        active.userCancelled.set(true)
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(active.runId)
            }
            active.job.cancel()
        }
        return true
    }

    /** System alarm trigger entry point: scans all due tasks, schedules them per the missed-run/quiet-hours policy, then immediately re-syncs the next alarm. */
    suspend fun onAlarmFired(context: Context) {
        val appContext = context.applicationContext
        val nowMs = System.currentTimeMillis()
        val dueTasks = ScheduledTaskRepository.dueTasks(nowMs, appContext)
        for (task in dueTasks) {
            val scheduledFor = task.nextRunAt ?: nowMs
            val decision = ScheduledTaskScheduleCalculator.evaluateMisfire(
                task = task,
                scheduledForAt = scheduledFor,
                nowMs = nowMs,
            )
            when (decision) {
                is ScheduledTaskScheduleCalculator.MisfireDecision.Skip -> {
                    recordSkippedAndAdvance(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
                        scheduledForAt = scheduledFor,
                        reason = decision.reason,
                    )
                }
                ScheduledTaskScheduleCalculator.MisfireDecision.RunNow -> {
                    launchExecution(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
                        scheduledForAt = scheduledFor,
                        advanceSchedule = true,
                    )
                }
                ScheduledTaskScheduleCalculator.MisfireDecision.RunCatchUp -> {
                    launchExecution(
                        context = appContext,
                        task = task,
                        triggerSource = ScheduledTaskTriggerSource.MISFIRE_CATCHUP,
                        scheduledForAt = scheduledFor,
                        advanceSchedule = true,
                    )
                }
            }
        }
        ScheduledTaskAlarmScheduler.syncNextAlarm(appContext)
    }

    /** Run now (does not modify the original schedule and is not subject to quiet-hours restrictions). */
    suspend fun triggerManualRun(context: Context, taskId: String): ManualTriggerResult {
        val appContext = context.applicationContext
        val task = ScheduledTaskRepository.getTask(taskId, appContext)
            ?: return ManualTriggerResult.NotFound
        if (isTaskRunning(taskId)) {
            return ManualTriggerResult.AlreadyRunning
        }
        val launched = launchExecution(
            context = appContext,
            task = task,
            triggerSource = ScheduledTaskTriggerSource.MANUAL,
            scheduledForAt = null,
            advanceSchedule = false,
        )
        return if (launched) ManualTriggerResult.Started else ManualTriggerResult.AlreadyRunning
    }

    sealed interface ManualTriggerResult {
        data object Started : ManualTriggerResult
        data object AlreadyRunning : ManualTriggerResult
        data object NotFound : ManualTriggerResult
    }

    private suspend fun launchExecution(
        context: Context,
        task: ScheduledTask,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        advanceSchedule: Boolean,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val runId = "sched-${UUID.randomUUID()}"

        // If the previous run is still in progress, record it as skipped and advance the next schedule to prevent overlapping concurrency.
        if (activeByTaskId.containsKey(task.id)) {
            if (advanceSchedule) {
                recordSkippedAndAdvance(
                    context = context,
                    task = task,
                    triggerSource = triggerSource,
                    scheduledForAt = scheduledForAt,
                    reason = context.getString(R.string.scheduled_skip_overlap_running),
                )
            }
            return false
        }

        // Protect physical main-screen GUI tasks while locked: don't force entry when not manually triggered, GUI is enabled, there is no background virtual screen, and the screen is locked.
        if (triggerSource != ScheduledTaskTriggerSource.MANUAL &&
            task.capabilities.guiTools &&
            shouldDeferLockedGuiTask(context)
        ) {
            val reason = context.getString(R.string.scheduled_skip_locked_gui_deferred)
            recordSkippedAndAdvance(
                context = context,
                task = task,
                triggerSource = triggerSource,
                scheduledForAt = scheduledForAt,
                reason = reason,
            )
            ScheduledTaskNotificationManager.notifyGuiReadyForUser(context, task, reason)
            return false
        }

        // When "show runtime progress notification" is off and the device has an FGS-free background network exemption (battery unrestricted / Root / foreground),
        // skip AgentExecutionService and only hold a bounded WakeLock and a bound service, achieving silent runtime execution with zero notifications.
        val leaseId = "scheduled:$runId"
        val executionJobHolder = arrayOfNulls<ActiveExecution>(1)
        val allowBoundFallback = RootAccess.isGranted
        val needForegroundLease = task.showRunningNotification || !canRunSilentlyWithoutForegroundService(context)
        if (needForegroundLease) {
            val fgsAcquired = AgentExecutionService.acquire(
                context = context,
                id = leaseId,
                allowBoundFallback = allowBoundFallback,
            ) {
                cancelRunningTask(context, task.id)
            }
            if (!fgsAcquired && !allowBoundFallback) {
                recordSkippedAndAdvance(
                    context = context,
                    task = task,
                    triggerSource = triggerSource,
                    scheduledForAt = scheduledForAt,
                    reason = context.getString(R.string.scheduled_skip_fgs_denied),
                )
                return false
            }
        }

        val timeoutMs = task.timeoutSeconds.coerceIn(
            ScheduledTask.MIN_TIMEOUT_SECONDS,
            ScheduledTask.MAX_TIMEOUT_SECONDS,
        ) * 1000L
        val wakeLock = acquireBoundedWakeLock(context, timeoutMs + 30_000L)

        val job = scope.launch {
            val execution = executionJobHolder[0]
            try {
                executeInternal(
                    context = context,
                    task = task,
                    runId = runId,
                    triggerSource = triggerSource,
                    scheduledForAt = scheduledForAt,
                    startedAt = startedAt,
                    advanceSchedule = advanceSchedule,
                    timeoutMs = timeoutMs,
                    execution = execution,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Scheduled task execution crashed: type=${failure.safeLogType()}")
                val finishedAt = System.currentTimeMillis()
                val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
                val failedRun = ScheduledTaskRun(
                    id = runId,
                    taskId = task.id,
                    triggerSource = triggerSource,
                    scheduledForAt = scheduledForAt,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    status = ScheduledTaskRunStatus.FAILED,
                    summary = reason.take(300),
                    notified = false,
                )
                val commit = ScheduledTaskRepository.recordRunFinished(
                    run = failedRun,
                    advanceSchedule = advanceSchedule,
                    nowMs = finishedAt,
                    context = context,
                )
                if (commit != null) {
                    ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                        context = context,
                        task = commit.task,
                        reason = commit.task.autoDisabledReason ?: reason,
                        autoDisabled = commit.autoDisabledNow,
                    )
                }
            } finally {
                ScheduledTaskRunReportRegistry.clear(runId)
                activeByTaskId.remove(task.id)
                publishActiveTasks()
                if (needForegroundLease) AgentExecutionService.release(leaseId)
                releaseWakeLockSafely(wakeLock)
            }
        }
        val active = ActiveExecution(taskId = task.id, runId = runId, job = job)
        executionJobHolder[0] = active
        val previous = activeByTaskId.putIfAbsent(task.id, active)
        if (previous != null) {
            job.cancel()
            if (needForegroundLease) AgentExecutionService.release(leaseId)
            releaseWakeLockSafely(wakeLock)
            return false
        }
        publishActiveTasks()
        return true
    }

    private suspend fun executeInternal(
        context: Context,
        task: ScheduledTask,
        runId: String,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        startedAt: Long,
        advanceSchedule: Boolean,
        timeoutMs: Long,
        execution: ActiveExecution?,
    ) {
        val archiveKey = when (task.sessionMode) {
            ScheduledTaskSessionMode.DEDICATED ->
                task.dedicatedConversationKey?.takeIf { it.isNotBlank() } ?: "task-${task.id}"
            ScheduledTaskSessionMode.ISOLATED -> runId
        }
        val baseModelConfig = resolveTaskModelConfig(task)
        if (baseModelConfig == null) {
            val finishedAt = System.currentTimeMillis()
            val message = context.getString(R.string.scheduled_error_no_model_configured)
            val failedRun = ScheduledTaskRun(
                id = runId,
                taskId = task.id,
                triggerSource = triggerSource,
                scheduledForAt = scheduledForAt,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = ScheduledTaskRunStatus.FAILED,
                summary = message,
                notified = false,
            )
            val commit = ScheduledTaskRepository.recordRunFinished(
                run = failedRun,
                advanceSchedule = advanceSchedule,
                nowMs = finishedAt,
                context = context,
            )
            if (commit != null) {
                ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                    context = context,
                    task = commit.task,
                    reason = commit.task.autoDisabledReason ?: message,
                    autoDisabled = commit.autoDisabledNow,
                )
            }
            return
        }

        val modelLabel = listOf(baseModelConfig.providerName, baseModelConfig.modelDisplayName.ifBlank { baseModelConfig.model })
            .filter { it.isNotBlank() }
            .joinToString(" / ")

        ScheduledTaskRepository.recordRunStarted(
            run = ScheduledTaskRun(
                id = runId,
                taskId = task.id,
                triggerSource = triggerSource,
                scheduledForAt = scheduledForAt,
                startedAt = startedAt,
                status = ScheduledTaskRunStatus.RUNNING,
                archiveConversationKey = archiveKey,
                modelLabel = modelLabel,
            ),
            context = context,
        )

        val runContext = ScheduledTaskRunContext(
            taskId = task.id,
            taskName = task.name,
            runId = runId,
            triggerSource = triggerSource,
            deliveryMode = task.deliveryMode,
            showRunningNotification = task.showRunningNotification,
            sessionMode = task.sessionMode,
            capabilities = task.capabilities,
            memo = task.memo,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
        )
        val archivePayload = AgentExternalArchivePayload(
            userText = task.prompt,
            conversationKey = archiveKey,
            title = task.name.ifBlank { context.getString(R.string.route_scheduled_tasks) },
            thinkingEnabled = baseModelConfig.thinkingEnabled,
            reasoningEffort = baseModelConfig.effectiveReasoningEffort,
            adapterPayload = runContext.toAdapterPayload(),
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = task.prompt,
            images = emptyList(),
            config = baseModelConfig,
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.SCHEDULED_TASK_HANDOFF_SOURCE,
                payload = archivePayload.toJson(),
                dismissEntrySurfaceOnForegroundOperation = false,
            ),
            modelSessionId = archiveKey,
        )

        // Hard-timeout watchdog: proactively cancel the Runtime run once the single-task wall-clock budget is exceeded, preventing unbounded battery drain or infinite loops.
        val watchdog = scope.launch {
            delay(timeoutMs)
            execution?.timedOut?.set(true)
            runCatching {
                AgentRuntimeClient(context, AndroidAgentLogger).cancelRun(runId)
            }
        }
        val runResult = try {
            AgentRuntimeClient(context, AndroidAgentLogger).run(request) { _ -> }
        } finally {
            watchdog.cancel()
        }

        val finishedAt = System.currentTimeMillis()
        val reported = ScheduledTaskRunReportRegistry.consume(runId)
        val timedOut = execution?.timedOut?.get() == true
        val userCancelled = execution?.userCancelled?.get() == true

        val (status, summary, shouldNotifySuccess) = when {
            timedOut -> Triple(
                ScheduledTaskRunStatus.TIMEOUT,
                context.getString(R.string.scheduled_run_timeout_summary, task.timeoutSeconds),
                false,
            )
            userCancelled -> Triple(
                ScheduledTaskRunStatus.CANCELLED,
                context.getString(R.string.scheduled_run_cancelled_summary),
                false,
            )
            !runResult.ok -> Triple(
                ScheduledTaskRunStatus.FAILED,
                runResult.error?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.scheduled_status_failed),
                false,
            )
            task.deliveryMode == ScheduledTaskDeliveryMode.ON_CHANGE &&
                reported?.outcome == ScheduledTaskReportedOutcome.Outcome.NO_CHANGE -> Triple(
                ScheduledTaskRunStatus.NO_CHANGE,
                reported.summary.ifBlank { context.getString(R.string.scheduled_run_no_change_summary) },
                false,
            )
            task.deliveryMode == ScheduledTaskDeliveryMode.SILENT -> Triple(
                ScheduledTaskRunStatus.NO_CHANGE,
                reported?.summary?.takeIf { it.isNotBlank() } ?: summarizeOutput(runResult.content),
                false,
            )
            else -> Triple(
                ScheduledTaskRunStatus.DELIVERED,
                reported?.summary?.takeIf { it.isNotBlank() } ?: summarizeOutput(runResult.content),
                true,
            )
        }

        val finalRun = ScheduledTaskRun(
            id = runId,
            taskId = task.id,
            triggerSource = triggerSource,
            scheduledForAt = scheduledForAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status,
            summary = summary.take(300),
            notified = false,
            archiveConversationKey = archiveKey,
            modelLabel = modelLabel,
        )
        val notified = when {
            shouldNotifySuccess -> ScheduledTaskNotificationManager.notifyResult(
                context = context,
                task = task,
                run = finalRun,
                summaryText = summary,
            )
            status.countsAsFailure -> false // Failure notifications are sent after the breaker decision to avoid duplicates.
            else -> false
        }

        val committedRun = finalRun.copy(notified = notified)
        val commit = ScheduledTaskRepository.recordRunFinished(
            run = committedRun,
            advanceSchedule = advanceSchedule,
            nowMs = finishedAt,
            context = context,
        )
        if (commit != null && status.countsAsFailure) {
            ScheduledTaskNotificationManager.notifyFailureOrAutoDisabled(
                context = context,
                task = commit.task,
                reason = commit.task.autoDisabledReason ?: summary,
                autoDisabled = commit.autoDisabledNow,
            )
        }
    }

    private suspend fun recordSkippedAndAdvance(
        context: Context,
        task: ScheduledTask,
        triggerSource: ScheduledTaskTriggerSource,
        scheduledForAt: Long?,
        reason: String,
    ) {
        val nowMs = System.currentTimeMillis()
        val skippedRun = ScheduledTaskRun(
            id = "sched-skip-${UUID.randomUUID()}",
            taskId = task.id,
            triggerSource = triggerSource,
            scheduledForAt = scheduledForAt,
            startedAt = nowMs,
            finishedAt = nowMs,
            status = ScheduledTaskRunStatus.SKIPPED,
            summary = reason,
            notified = false,
        )
        ScheduledTaskRepository.recordRunFinished(
            run = skippedRun,
            advanceSchedule = true,
            nowMs = nowMs,
            context = context,
        )
    }

    private suspend fun resolveTaskModelConfig(task: ScheduledTask): AgentModelClient.ModelConfig? {
        val base = RuntimeConfigRepository.runtimeConfigFor(task.modelId) ?: return null
        return applyTaskPolicyToModelConfig(
            base = base,
            task = task,
            globalThinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            globalBrowserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            globalTerminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            globalDeviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            globalDeviceSensitiveReadTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            globalDeviceSensitiveActionTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            globalSubagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        )
    }

    /**
     * Intersects the per-task config with the global user toggles.
     *
     * `RuntimeConfigRepository.buildRuntimeConfig()` does not populate local tool toggles (it constructs defaults from ModelConfig),
     * so here we must explicitly use the global toggles as the baseline and then trim by task capability, rather than reading `base`'s default booleans directly.
     */
    internal fun applyTaskPolicyToModelConfig(
        base: AgentModelClient.ModelConfig,
        task: ScheduledTask,
        globalThinkingEnabled: Boolean,
        globalBrowserTools: Boolean,
        globalTerminalTools: Boolean,
        globalDeviceDirectTools: Boolean,
        globalDeviceSensitiveReadTools: Boolean,
        globalDeviceSensitiveActionTools: Boolean,
        globalSubagentTools: Boolean,
    ): AgentModelClient.ModelConfig {
        val explicitEffort = task.resolvedReasoningEffort
        val capabilities = base.reasoningCapabilities
        val effort = when {
            explicitEffort != null -> when {
                capabilities != null -> capabilities.normalize(explicitEffort)
                explicitEffort == ReasoningEffort.OFF -> ReasoningEffort.OFF
                else -> base.effectiveReasoningEffort
            }
            !globalThinkingEnabled -> capabilities?.normalize(ReasoningEffort.OFF) ?: ReasoningEffort.OFF
            else -> base.effectiveReasoningEffort
        }
        return base.copy(
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            browserTools = globalBrowserTools && task.capabilities.browserTools,
            terminalTools = globalTerminalTools && task.capabilities.terminalTools,
            deviceDirectTools = globalDeviceDirectTools &&
                (task.capabilities.deviceReadTools || task.capabilities.deviceActionTools),
            deviceSensitiveReadTools = globalDeviceSensitiveReadTools && task.capabilities.deviceReadTools,
            deviceSensitiveActionTools = globalDeviceSensitiveActionTools && task.capabilities.deviceActionTools,
            subagentTools = globalSubagentTools,
        )
    }

    /** Avoid barging into the physical main screen from the background only when the device is locked and the trusted background virtual-screen condition is not met. */
    private fun shouldDeferLockedGuiTask(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val isLocked = keyguard?.isKeyguardLocked == true
        if (!isLocked) return false
        val virtualDisplayPossible = Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED) &&
            (RootAccess.isGranted || EtaApp.serviceInstance != null)
        return !virtualDisplayPossible
    }

    private fun summarizeOutput(raw: String): String {
        val normalized = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[#>*`]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.take(220).ifBlank {
            AppStrings.get(R.string.scheduled_run_completed_summary, "Execution completed")
        }
    }

    /**
     * Determines whether the current device allows a silent scheduled task to skip the foreground execution service:
     * safe to allow when in the foreground, Root is granted, or the battery-optimization whitelist is joined (Doze will not cut background network after 10 seconds).
     */
    internal fun canRunSilentlyWithoutForegroundService(context: Context): Boolean =
        MainActivity.isInForeground ||
            RootAccess.isGranted ||
            ScheduledTaskHealthChecker.inspect(context).ignoringBatteryOptimizations

    private fun acquireBoundedWakeLock(context: Context, timeoutMs: Long): PowerManager.WakeLock? =
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Eta:ScheduledTaskRun").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()

    private fun releaseWakeLockSafely(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun publishActiveTasks() {
        _activeTaskIds.value = activeByTaskId.keys.toSet()
    }
}
