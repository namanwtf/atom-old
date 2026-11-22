package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.annotation.SuppressLint
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.conversationHandoffStartMode
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayBubble
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.agent.overlay.AgentOrbAction
import io.github.mangi.eta.agent.overlay.AgentOrbLabels
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunContext
import io.github.mangi.eta.agent.scheduler.ScheduledTaskRunner
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * General-purpose Agent Runtime inside the module process.
 *
 * Hook entries only send requests and receive results; model calls, tool execution, and the run-status overlay all happen in this service.
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var destroyed = false
    @Volatile private var lastGuiOwnerRunId: String? = null
    private var displayedRun: RuntimeRunRegistry.Run? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var localPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val bubbleExpanded = mutableStateOf(false)
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** The window only projects the current phone controller; each run's state is kept in its own record. */
    private val state = mutableStateOf(AgentOverlayState.Initial)
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    private val capsuleExpanded = mutableStateOf(false)
    private val capsuleSupplementFocusable = mutableStateOf(false)
    /** True when the Agent gesture passes through the capsule: the window is not touchable and the capsule fades out to make way. */
    private val capsuleYielded = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        Prefs.localAgentPreferences()?.let { prefs ->
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED) {
                    mainHandler.post(::updateKeepScreenOn)
                }
            }
            localPrefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        serviceScope.launch { guiControl.state.collect(::applyGuiControlState) }
        serviceScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                val wasActive = virtualDisplayState.value.active
                virtualDisplayState.value = snapshot
                if (wasActive != snapshot.active) {
                    if (!snapshot.active) VirtualDisplayController.setStageExpanded(false)
                    updateCapsuleWindowBounds()
                    displayedRun?.let(::syncActiveRunOverlayVisibility)
                    publishExecutionState()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || runs.all().isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (runs.all().isNotEmpty()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        destroyed = true
        if (activeInstance === this) activeInstance = null
        localPrefsListener?.let { listener ->
            Prefs.localAgentPreferences()?.unregisterOnSharedPreferenceChangeListener(listener)
        }
        localPrefsListener = null
        setKeepScreenOn(false)
        runs.all().forEach { run ->
            run.releaseIncoming()
            run.session.cancel(
                AppStrings.get(R.string.runtime_err_service_stopped, "Agent Runtime service has stopped")
            )
        }
        displayedRun = null
        serviceScope.cancel()
        AgentExecutionService.updateRunStatus(this, null, activeRunCount = 0)
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        VirtualDisplayController.release(this)
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        bubbleView = null
        bubbleParams = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                } else if (msg.what == AgentRuntimeWire.MSG_STEER) {
                    AgentWireText.close(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure(
                            AppStrings.get(R.string.runtime_err_missing_body, "Agent Runtime request is missing a message body"),
                            msg.replyTo,
                        )
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            AppStrings.get(R.string.runtime_err_invalid_format, "Agent Runtime request format is invalid"),
                            msg.replyTo,
                        )
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure(
                            AppStrings.get(
                                R.string.runtime_err_missing_input,
                                "Agent Runtime request is missing runId or user input",
                            ),
                            msg.replyTo,
                        )
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo, msg.sendingUid == android.os.Process.myUid())
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_STEER -> {
                    steerRun(msg.data, msg.replyTo)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(
                            replyTo,
                            target?.result ?: AgentRuntimeWire.RunResult(
                                runId,
                                false,
                                "",
                                AppStrings.get(
                                    R.string.runtime_err_full_result_unavailable,
                                    "The complete run result is unavailable",
                                ),
                                contextSnapshotRef = runId,
                            ),
                        )
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS -> {
                    sendActiveRuns(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
        isEtaProcess: Boolean,
    ) {
        val run = admitRun(incoming.request, replyTo, incoming) ?: return
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                run.session.controller.throwIfCancelled()
                run.awaitPredecessors()
                val request = AgentRuntimeRequestConfigResolver.constrainPresentation(
                    AgentRuntimeImageTransfer.materialize(incoming), isEtaProcess,
                )
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        if (AgentRuntimeRequestConfigResolver.usesDefaultRuntimeConfig(request)) {
                            RuntimeConfigRepository.assistantOverlayRuntimeConfig()
                        } else {
                            RuntimeConfigRepository.oauthRuntimeConfigFor(
                                providerId = request.config.providerId,
                                authMode = request.config.authMode,
                                modelName = request.config.model,
                            )
                        }
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                run.releaseIncoming()
                if (destroyed || !runs.contains(run) || run.session.controller.isCancelled) return@post
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        run.prepared(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                        )
                        executePreparedRun(run)
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        failBeforeExecution(
                            run,
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: AppStrings.get(
                                        R.string.runtime_err_read_image_failed,
                                        "Agent Runtime cannot read the image",
                                    )
                                is RuntimeConfigUnavailableException ->
                                    AppStrings.get(
                                        R.string.runtime_err_no_model_configured,
                                        "Please configure an available model in Eta first",
                                    )
                                else -> AppStrings.get(
                                    R.string.runtime_err_prepare_failed,
                                    "Agent Runtime cannot prepare the request",
                                )
                            },
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        val run = admitRun(request, replyTo) ?: return
        run.prepared(request)
        executePreparedRun(run)
    }

    private fun admitRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger?,
        incoming: AgentRuntimeWire.IncomingRunRequest? = null,
    ): RuntimeRunRegistry.Run? {
        if (destroyed) {
            incoming?.close()
            sendRequestIngestedTo(replyTo, request.runId)
            sendResultTo(
                replyTo,
                AgentRuntimeWire.RunResult(
                    request.runId,
                    false,
                    "",
                    AppStrings.get(R.string.runtime_err_service_stopped, "Agent Runtime service has stopped"),
                ),
            )
            return null
        }
        val admission = runs.admit(request) {
            lateinit var session: AgentRuntimeSession
            session = AgentRuntimeSession(
                runId = request.runId,
                operation = request.operation,
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                beforeTerminalPublish = {
                    runs.finish(request.runId, session)?.releaseIncoming()
                    AgentExecutionService.release("run:${request.runId}")
                    mainHandler.post {
                        if (!destroyed) {
                            publishExecutionState()
                            updateKeepScreenOn()
                            if (runs.all().isEmpty() && displayedRun == null && !guiControl.state.value.userPaused) stopSelf()
                        }
                    }
                },
            )
            RuntimeRunRegistry.Run(request, session, incoming) {
                sendRequestIngestedTo(replyTo, request.runId)
            }
        }
        val run = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission.run
            is RuntimeRunRegistry.Admission.Duplicate -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                if (replyTo != null) {
                    val attached = admission.run.session.attach(
                        eventSink = { event -> sendEventTo(replyTo, event) },
                        resultSink = { result -> sendResultTo(replyTo, result) },
                    )
                    if (!attached) sendResultTo(
                        replyTo,
                        AgentRuntimeWire.RunResult(
                            request.runId,
                            false,
                            "",
                            AppStrings.get(
                                R.string.runtime_err_finished_recover,
                                "The task has ended; please restore the complete result",
                            ),
                            contextSnapshotRef = request.runId,
                            operation = request.operation,
                        ),
                    )
                }
                return null
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                incoming?.close()
                sendRequestIngestedTo(replyTo, request.runId)
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                sendResultTo(replyTo, AgentRuntimeWire.RunResult(
                    request.runId, false, "", admission.message,
                    operation = request.operation, rewriteTargetMessageId = request.rewriteTargetMessageId,
                ))
                return null
            }
        }
        // The Root entry keeps the original bound-service lifecycle; the added FGS must not become a new prerequisite permission for vendor background entries.
        // Scheduled tasks likewise skip FGS when they explicitly disable run-progress notifications and the device has background exemption, achieving zero notifications at runtime.
        val silentScheduledRun = isSilentScheduledRun(request)
        val skipForegroundLease = silentScheduledRun &&
            ScheduledTaskRunner.canRunSilentlyWithoutForegroundService(this)
        if (!skipForegroundLease) {
            val allowBoundFallback = RootAccess.isGranted
            val executionHeld = AgentExecutionService.acquire(
                this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
            ) { mainHandler.post { cancelRun(request.runId) } }
            if (!executionHeld && !allowBoundFallback) {
                failBeforeExecution(
                    run,
                    AppStrings.get(
                        R.string.runtime_err_fgs_start_failed,
                        "Unable to start the background execution service; return to Eta and try again",
                    ),
                )
                return null
            }
        }
        admission.replacedRuns.forEach { cancelRun(it.runId) }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        publishExecutionState()
        updateKeepScreenOn()
        return run
    }

    private fun failBeforeExecution(run: RuntimeRunRegistry.Run, message: String) {
        run.releaseIncoming()
        val result = AgentRuntimeWire.RunResult(
            run.runId, false, "", message,
            operation = run.request.operation, rewriteTargetMessageId = run.request.rewriteTargetMessageId,
        )
        dispatchResultIo {
            run.session.complete(result) {
                runCatching { persistRunArtifacts(run.snapshotRequest(), result, emptyList()) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.error("Agent runtime preparation result persistence failed: type=${throwable.safeLogType()}")
                    }
            }
        }
    }

    private fun executePreparedRun(run: RuntimeRunRegistry.Run) {
        if (!runs.contains(run)) return
        val screenOnBinding = run.session.controller.register { mainHandler.post(::updateKeepScreenOn) }
        thread(name = "agent-runtime") {
            try {
                executeRun(run)
            } finally {
                screenOnBinding.close()
            }
        }
    }

    private fun executeRun(run: RuntimeRunRegistry.Run) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            guiControl = guiControl,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = run::snapshotRequest,
            onAcceptedEvent = { event, _ ->
                handleAcceptedRunEvent(run, event)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(run.session, run.request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            run = run,
            result = outcome.result,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    RuntimeRunRegistry.CompletedContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        run: RuntimeRunRegistry.Run,
        event: AgentEvent,
    ) {
        if (event is AgentEvent.GuiControlChanged) {
            run.guiStatus = event.status
            if (event.status == GuiControlStatus.Acquired) lastGuiOwnerRunId = run.runId
        }
        mainHandler.post {
            if (destroyed) return@post
            val guiState = guiControl.state.value
            val ownsGui = guiState.ownerRunId == run.runId
            // StateFlow can coalesce a brief acquire and release; terminal ownership still follows the last owner recorded by the ordered events.
            if (event is AgentEvent.GuiControlChanged && event.status == GuiControlStatus.Acquired &&
                !guiState.userPaused && lastGuiOwnerRunId == run.runId && (ownsGui || guiState.ownerRunId == null)) {
                bindDisplayedRun(run)
            }
            if (AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, true)) {
                run.hasExecutedForegroundTool = true
            }
            runCatching {
                run.overlayState = run.overlayState.applyEvent(event)
                if (displayedRun === run && ownsGui && !run.session.isTerminal) {
                    state.value = run.overlayState
                    syncActiveRunOverlayVisibility(run)
                }
                publishExecutionState()
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // The outbox is the commit point between terminal state and the in-flight checkpoint; on failure the checkpoint is kept for the next recovery.
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        run: RuntimeRunRegistry.Run,
        result: AgentRuntimeWire.RunResult,
        completedContext: RuntimeRunRegistry.CompletedContext? = null,
    ) {
        mainHandler.post {
            if (destroyed) return@post
            run.completedContext = completedContext
            val guiState = guiControl.state.value
            if (lastGuiOwnerRunId != run.runId || guiState.userPaused ||
                (guiState.ownerRunId != null && guiState.ownerRunId != run.runId)) return@post
            bindDisplayedRun(run)
            updateKeepScreenOn()
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                    )
                } else {
                    val stopped = run.session.controller.isCancelled || isStoppedError(result.error)
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (stopped) {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = if (stopped) {
                                AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR)
                            } else {
                                result.error.orEmpty()
                            },
                        ),
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // Do not disguise a transfer failure as a delivered terminal state; the reference lets the new client keep the outbox and await full recovery.
            val fallback = AgentRuntimeWire.RunResult(
                result.runId,
                false,
                "",
                AppStrings.get(
                    R.string.runtime_err_result_transfer_failed,
                    "Complete result transfer failed; saved history was not deleted. Reopen the session to restore.",
                ),
                contextSnapshotRef = result.runId,
                operation = result.operation,
            )
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            val snapshots = runs.snapshots()
            val runId = snapshots.firstOrNull { it.runId == guiControl.state.value.ownerRunId }?.runId
                ?: snapshots.firstOrNull()?.runId.orEmpty()
            msg.data = AgentRuntimeWire.ackBundle(runId)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRuns(replyTo: Messenger?) {
        runCatching {
            replyTo?.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE).apply {
                data = AgentRuntimeWire.activeRunsToBundle(runs.snapshots())
            })
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_runs_delivery_failed") {
                "Agent runtime active runs delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        val session = runs.get(runId)?.session
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
    }

    private fun requestStop(runId: String?) {
        if (runId == null || runs.get(runId) == null) {
            if (!guiControl.state.value.userPaused && displayedRun?.runId == runId &&
                guiControl.state.value.ownerRunId == null) dismissAndStop()
            return
        }
        cancelRun(runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val run = runs.get(runId) ?: return
        if (run.preparing) {
            run.session.controller.cancel()
            failBeforeExecution(
                run,
                AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR),
            )
            return
        }
        val session = run.session
        if (!session.isTerminal) {
            session.controller.cancel()
            run.overlayState = run.overlayState.copy(status = AgentOverlayStatus.Stopping)
            if (displayedRun === run && !guiControl.state.value.userPaused) state.value = run.overlayState
            updateKeepScreenOn()
            publishExecutionState()
        }
    }

    private fun steerRun(data: Bundle?, replyTo: Messenger?) {
        if (data == null) {
            sendSteerResponse("", replyTo, accepted = false)
            return
        }
        var runId = ""
        val text = try {
            runId = AgentRuntimeWire.runIdFromBundle(data)
            AgentRuntimeWire.steerTextFromBundle(data).trim()
        } catch (throwable: Throwable) {
            AndroidAgentLogger.warnThrottled("runtime_invalid_steer_request") {
                "Agent runtime rejected invalid steer request: type=${throwable.safeLogType()}"
            }
            ""
        } finally {
            AgentWireText.close(data)
        }
        val run = runId.takeIf(String::isNotBlank)?.let(runs::get)
        val event = if (run != null && text.isNotBlank()) run.supplement(text) else null
        if (run == null || event == null) {
            sendSteerResponse(runId, replyTo, accepted = false)
            return
        }
        AndroidAgentLogger.info(
            "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
        )
        run.overlayState = run.overlayState.applyEvent(event)
        if (displayedRun === run && guiControl.state.value.ownerRunId == run.runId && !guiControl.state.value.userPaused) {
            state.value = run.overlayState
        }
        sendSteerResponse(runId, replyTo, accepted = true, supplementIndex = event.index)
    }

    private fun sendSteerResponse(
        runId: String,
        replyTo: Messenger?,
        accepted: Boolean,
        supplementIndex: Int = 0,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STEER_RESPONSE)
            msg.data = AgentRuntimeWire.steerResponseBundle(runId, accepted, supplementIndex)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_steer_response_delivery_failed") {
                "Agent runtime steer response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestPause(runId: String?) {
        if (runId != null) guiControl.pauseForUser(runId)
    }

    private fun requestResume() {
        guiControl.resumeForUser()
    }

    private fun requestSupplement(runId: String?, text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val run = displayedRun?.takeIf { it.runId == runId } ?: return
        if (guiControl.state.value.userPaused && guiControl.state.value.ownerRunId == null) return
        setCapsuleInputMode(focusable = false)
        runs.get(run.runId)?.let { active ->
            val event = active.supplement(supplementText)
            if (event == null) {
                if (!active.session.isTerminal) {
                    state.value = state.value.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                active.overlayState = active.overlayState.applyEvent(event)
                state.value = active.overlayState
                return
            }
        }

        val completed = run.completedContext ?: return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun syncActiveRunOverlayVisibility(run: RuntimeRunRegistry.Run?) {
        if (AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
                virtualDisplayActive = virtualDisplayState.value.active,
            )
        ) {
            removeAmbientWindows()
        } else {
            ensureOverlayVisible()
        }
    }

    private fun handleEtaUiVisibilityChanged() {
        if (destroyed) return
        val run = displayedRun ?: return
        val suppress = AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi(
            handoffSource = run.request.handoff?.source,
            etaUiInForeground = MainActivity.isInForeground,
            virtualDisplayActive = virtualDisplayState.value.active,
        )
        if (resultCardView != null) {
            if (suppress) dismissResult(run)
            return
        }
        val guiState = guiControl.state.value
        val activeGuiRun = guiState.userPaused || (guiState.ownerRunId == run.runId && !run.session.isTerminal)
        if (!activeGuiRun) return
        if (suppress) {
            removeAmbientWindows()
        } else {
            state.value = run.overlayState
            ensureOverlayVisible()
        }
        publishExecutionState()
    }

    private fun updateKeepScreenOn() {
        val enabled = !destroyed &&
            Prefs.isEnabled(Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED) &&
            runs.all().any { run ->
                !run.session.isTerminal &&
                    !run.session.controller.isCancelled &&
                    !run.session.controller.isPaused
            }
        setKeepScreenOn(enabled)
    }

    private fun applyGuiControlState(guiState: GuiControlCoordinator.State) {
        if (destroyed) return
        val owner = guiState.ownerRunId?.let(runs::get)
        if (owner != null && displayedRun !== owner) {
            bindDisplayedRun(owner)
            AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
        }
        when {
            guiState.userPaused -> {
                state.value = (owner?.overlayState ?: AgentOverlayState.Initial).copy(
                    phase = AgentOverlayPhase.PAUSED,
                    status = AgentOverlayStatus.Paused,
                    detailText = AppStrings.get(R.string.runtime_detail_gui_paused, "Phone control is paused"),
                )
                owner?.overlayState = state.value
                syncActiveRunOverlayVisibility(owner ?: displayedRun)
            }
            owner != null -> {
                if (owner.overlayState.phase == AgentOverlayPhase.PAUSED) {
                    owner.overlayState = owner.overlayState.copy(
                        phase = AgentOverlayPhase.RUNNING,
                        status = AgentOverlayStatus.Continuing,
                        detailText = "",
                    )
                }
                state.value = owner.overlayState
                syncActiveRunOverlayVisibility(owner)
            }
            else -> removeAmbientWindows()
        }
        updateKeepScreenOn()
        publishExecutionState()
    }

    private fun bindDisplayedRun(run: RuntimeRunRegistry.Run) {
        if (displayedRun === run) return
        removeOverlayWindows()
        displayedRun?.completedContext = null
        displayedRun = run
        capsuleExpanded.value = false
    }

    private fun publishExecutionState() {
        val guiState = guiControl.state.value
        val activeRuns = runs.all().filterNot { it.session.isTerminal }
        val visibleRuns = activeRuns.filterNot { isSilentScheduledRun(it.request) }
        val owner = activeRuns.firstOrNull { it.runId == guiState.ownerRunId }
        val targetRun = owner ?: visibleRuns.firstOrNull() ?: activeRuns.firstOrNull()
        val allActiveRunsSilent = activeRuns.isNotEmpty() && visibleRuns.isEmpty() && owner == null
        val status = if (owner != null && guiState.userPaused) {
            AgentOverlayStatus.Paused
        } else {
            targetRun?.overlayState?.status
        }
        val handoff = targetRun?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            .orEmpty()
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val guiOverlayActive = capsuleView != null ||
            (owner != null && !owner.session.isTerminal) ||
            virtualDisplayState.value.active
        AgentExecutionService.updateRunStatus(
            this,
            if (allActiveRunsSilent) null else status,
            activeRunCount = if (allActiveRunsSilent) 0 else visibleRuns.size.coerceAtLeast(if (owner != null) 1 else 0),
            conversationId = conversationId,
            archivedSource = archive?.first.orEmpty(),
            archivedKey = archive?.second.orEmpty(),
            startedAtEpochMs = targetRun?.startedAtEpochMs ?: 0L,
            suppressLiveUpdate = guiOverlayActive || allActiveRunsSilent,
        )
    }

    private fun isSilentScheduledRun(request: AgentRuntimeWire.RunRequest): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.SCHEDULED_TASK_HANDOFF_SOURCE) return false
        val scheduled = AgentExternalArchivePayload.from(handoff.payload)
            ?.adapterPayload
            ?.let(ScheduledTaskRunContext::fromAdapterPayload)
            ?: return false
        return !scheduled.showRunningNotification
    }

    @SuppressLint("WakelockTimeout")
    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        capsuleView?.keepScreenOn = enabled
        if (enabled) {
            val wakeLock = screenWakeLock ?: runCatching {
                @Suppress("DEPRECATION")
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, WAKE_LOCK_TAG)
                    ?.apply { setReferenceCounted(false) }
            }.getOrNull()?.also { screenWakeLock = it }
            if (wakeLock != null && !wakeLock.isHeld) {
                runCatching { wakeLock.acquire() }
            }
        } else {
            screenWakeLock?.let { wakeLock ->
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
            }
        }
    }

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY avoids the SYSTEM_ALERT_WINDOW permission; only the fallback state (accessibility not enabled) needs the check
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── Ambience-light window: full-screen touch passthrough, rainbow ring (hides the flowing light while the background virtual screen runs; only carries the full-screen wall-break animation) ─
        val glow = createOverlayComposeView {
            AgentOverlayGlow(
                state = state.value,
                corners = screenCornerRadii(),
                virtualDisplay = virtualDisplayState.value,
            )
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── Status capsule window: centered below the status bar, one-line status when collapsed (with minimap), tap to expand controls and the gallery view ─
        val capsule = createOverlayComposeView {
            val controlRunId = displayedRun?.runId
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = {
                    VirtualDisplayController.setUserControlling(false)
                    requestPause(controlRunId)
                },
                onResume = {
                    VirtualDisplayController.setUserControlling(false)
                    requestResume()
                },
                onStop = { requestStop(controlRunId) },
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { requestSupplement(controlRunId, it) },
                virtualDisplay = virtualDisplayState.value,
                onTakeOverVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(true)
                    requestPause(controlRunId)
                },
                onResumeVirtualDisplay = {
                    VirtualDisplayController.setUserControlling(false)
                    requestResume()
                },
                onFullscreenVirtualDisplay = ::requestFullscreenVirtualDisplay,
                onTapVirtualCanvas = { nx, ny, longPress ->
                    VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                },
                onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                    VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                },
            )
        }
        val capsuleLp = capsuleLayoutParams()
        capsule.keepScreenOn = keepScreenOn.value
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp

        showBubble(wm)
    }

    /**
     * On yielding, switch touch passthrough synchronously: the flag takes effect immediately so the gesture is not intercepted, while the visual transition is left to the capsule's animation.
     * On recovery, fade in first and then reclaim touch; the order is irrelevant because recovery happens after the gesture ends.
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        VirtualDisplayController.setStageExpanded(expand && virtualDisplayState.value.active)
        // First expand the window to the expanded tier before starting the animation; collapse shrinks the window via the capsule's onCollapsedSettled callback after the animation.
        if (expand) updateCapsuleWindowBounds(expanding = true)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            VirtualDisplayController.setStageExpanded(false)
            updateCapsuleWindowBounds(expanding = false)
        }
    }

    private fun updateCapsuleWindowBounds(expanding: Boolean = capsuleExpanded.value) {
        val vdActive = virtualDisplayState.value.active
        val focusable = capsuleSupplementFocusable.value
        // When the virtual screen is active, fix the window width to the expanded tier, avoiding horizontal centering jitter from expand/collapse WindowManager width changes.
        val widthDp = if (vdActive) {
            CAPSULE_STAGE_WINDOW_WIDTH_DP
        } else {
            CAPSULE_WINDOW_WIDTH_DP
        }
        val heightDp = when {
            focusable && expanding -> CAPSULE_SUPPLEMENT_HEIGHT_DP
            expanding && vdActive -> CAPSULE_STAGE_HEIGHT_DP
            expanding -> CAPSULE_CONTROLS_HEIGHT_DP
            else -> CAPSULE_COLLAPSED_HEIGHT_DP
        }
        setCapsuleWindowSize(widthDp, heightDp)
    }

    private fun setCapsuleWindowSize(widthDp: Int, heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        val height = dpToPx(heightDp)
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun requestFullscreenVirtualDisplay() {
        capsuleExpanded.value = false
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-fullscreen-handoff", isDaemon = true) {
            VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            val resultRun = displayedRun
            AgentResultCard(
                state = state.value,
                onClose = { dismissResult(resultRun) },
                onOpenEta = { openEtaFromResult(resultRun) },
                hasVirtualDisplayApp = virtualDisplayState.value.active &&
                    virtualDisplayState.value.packageName.isNotBlank(),
                onOpenVirtualApp = {
                    thread(name = "eta-vd-result-handoff", isDaemon = true) {
                        VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService)
                        mainHandler.post { dismissResult(resultRun) }
                    }
                },
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * The result card is the lingering screen after a task ends; the system back button should close it first rather than returning to the app underneath.
     * The window must be focusable to receive the back press; outside the card it remains FLAG_NOT_TOUCH_MODAL, so touches pass through to the layer below as usual.
     */
    /**
     * Return to Eta itself and navigate to this task's session. When the card window is visible it counts as a foreground-visible user action,
     * so starting an Activity from the service is allowed; on launch failure the card is kept so the user does not lose the result.
     */
    private fun openEtaFromResult(run: RuntimeRunRegistry.Run?) {
        val handoff = run?.request?.handoff
        val conversationId = handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        val archive = handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { item -> AgentExternalArchivePayload.from(item.payload)?.conversationKey?.let { item.source to it } }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode = conversationHandoffStartMode()
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissResult(run)
    }

    private fun dismissResult(run: RuntimeRunRegistry.Run?) {
        if (run != null && displayedRun === run && lastGuiOwnerRunId == run.runId &&
            guiControl.state.value.ownerRunId == null && !guiControl.state.value.userPaused) dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val resultRun = displayedRun
        val callback = OnBackInvokedCallback { dismissResult(resultRun) }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    // ── Orb window: draggable bubble that expands into Hide / Open chat / Talk ──
    private fun showBubble(wm: WindowManager) {
        if (bubbleView != null) return
        val labels = AgentOrbLabels(
            hide = getString(R.string.bubble_hide),
            openChat = getString(R.string.bubble_open_chat),
            talk = getString(R.string.bubble_talk),
        )
        val view = createOverlayComposeView {
            AgentOverlayBubble(
                state = state.value,
                expanded = bubbleExpanded.value,
                labels = labels,
                onToggleExpanded = { bubbleExpanded.value = !bubbleExpanded.value },
                onAction = { action ->
                    when (action) {
                        AgentOrbAction.HIDE -> {
                            bubbleExpanded.value = false
                            hideBubble()
                        }
                        AgentOrbAction.OPEN_CHAT -> {
                            bubbleExpanded.value = false
                            openEtaFromResult(displayedRun)
                        }
                        AgentOrbAction.TALK -> {
                            bubbleExpanded.value = false
                            startAssistantVoice()
                        }
                    }
                },
                onDrag = { dx, dy -> moveBubble(dx, dy) },
            )
        }
        val lp = bubbleLayoutParams()
        runCatching { wm.addView(view, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        bubbleView = view
        bubbleParams = lp
    }

    private fun hideBubble() {
        bubbleExpanded.value = false
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        bubbleParams = null
    }

    private fun moveBubble(dx: Float, dy: Float) {
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    /** Talk hands off to the assistant voice overlay; chat is the fallback when it cannot start. */
    private fun startAssistantVoice() {
        val intent = Intent(this, EtaAssistantOverlayService::class.java)
            .setAction(EtaAssistantOverlayService.ACTION_SHOW)
        runCatching { startForegroundService(intent) }
            .onFailure { openEtaFromResult(displayedRun) }
    }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Top|Start with explicit offsets: dragging only mutates x/y, so the
            // window never re-centres mid-gesture.
            gravity = Gravity.TOP or Gravity.START
            val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.currentWindowMetrics
            x = ((metrics?.bounds?.width() ?: dpToPx(1080)) - dpToPx(232)).coerceAtLeast(0)
            y = ((metrics?.bounds?.height() ?: dpToPx(2400)) * 0.62f).toInt()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = AppearanceSettings())
                AgentAppTheme(
                    appearance = appearance,
                    systemOverlay = true,
                ) {
                    // Some ROMs assign a software Canvas to TYPE_ACCESSIBILITY_OVERLAY; Miuix and COUI's
                    // RuntimeShader only checks the system version, so system overlays uniformly use the rounded-corner fallback without RuntimeShader.
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                    ) {
                        content()
                    }
                }
            }
        }

    /**
     * The capsule window uses a fixed width and tiered heights, with animation only inside the window.
     * WRAP_CONTENT makes every frame's size change trigger a full-window relayout by WindowManager, which is the source of jank;
     * but transparent regions inside the window would also swallow touches (including clicks the Agent itself injects), so the height only grows when needed:
     * expand to the target tier before expanding, and shrink back after the collapse animation ends; see [setCapsuleWindowHeight].
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(if (virtualDisplayState.value.active) CAPSULE_STAGE_WINDOW_WIDTH_DP else CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Positioned centered right below the status bar: away from most apps' main click areas, and consistent with the system capsule and fluid-cloud placement conventions.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Half-screen, bottom-centered, touches pass through outside the window
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // Use TYPE_ACCESSIBILITY_OVERLAY when the accessibility service is available (avoids the SYSTEM_ALERT_WINDOW permission, and screenshots
        // can be filtered by filterValidWindows); must be created with the accessibility-service context, otherwise BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // Real screen height (including status bar + navigation bar); MATCH_PARENT excludes system bars on some devices
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Full-screen coverage (including status/navigation bars), touch passthrough does not intercept page operations;
            // TYPE_ACCESSIBILITY_OVERLAY makes takeScreenshotOfWindow filter it out, transparent to the Agent
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Non-app windows shrink the window frame by system bars and the cutout by default, so the rounded corners the light band draws shift down and misalign with the physical screen corners.
            // Explicitly avoid no inset so the window origin coincides with the screen origin, making the Display-reported corner radius match.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * Physical corner radius of the screen's four corners. The light-effect window covers the whole screen and directly uses the Display-reported value;
     * when some ROMs do not report it, use 0 and fall back to a conservative estimate based on common large-corner models — better slightly smaller than letting the light band exceed the screen.
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        capsuleSupplementFocusable.value = focusable
        if (capsuleExpanded.value) updateCapsuleWindowBounds(expanding = true)
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState) {
        state.value = finalState

        val run = displayedRun
        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                hasExecutedForegroundTool = run?.hasExecutedForegroundTool == true,
                handoffSource = run?.request?.handoff?.source,
                etaUiInForeground = MainActivity.isInForeground,
            )
        ) {
            // Remove the capsule and edge light and show the half-screen result card instead; without interaction, the card itself delays its exit and calls back to close
            capsuleExpanded.value = false
            removeAmbientWindows()
            publishExecutionState()
            if (windowManager == null && (AgentAccessibilityService.current() != null || Settings.canDrawOverlays(this))) {
                windowManager = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            }
            windowManager?.let(::showResultCard) ?: dismissAndStop()
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    private fun dismissAndStop() {
        // stopSelf does not destroy a service still bound by clients; after the window closes, no full request or result history is retained.
        displayedRun?.completedContext = null
        displayedRun = null
        removeOverlayWindows()
        if (runs.all().isEmpty() && !guiControl.state.value.userPaused) {
            VirtualDisplayController.release(this)
            stopSelf()
        }
    }

    private fun removeOverlayWindows() {
        mainHandler.removeCallbacksAndMessages(hideToken)
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.keepScreenOn = false
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        bubbleView = null
        bubbleParams = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean =
        msg.sendingUid == Process.myUid()

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isStoppedError(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        return error == LEGACY_STOPPED_ERROR ||
            error == SYNTHETIC_STOPPED_ERROR ||
            error == "Stopped" ||
            error == AppStrings.get(R.string.runtime_err_stopped, LEGACY_STOPPED_ERROR)
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    companion object {
        @Volatile
        private var activeInstance: AgentRuntimeService? = null

        // True when there is an active task and the keep-awake switch is on; projects synchronously to the App foreground window and overlay and holds the screen wake lock.
        private val keepScreenOnState = MutableStateFlow(false)
        val keepScreenOn = keepScreenOnState.asStateFlow()

        internal fun onEtaUiVisibilityChanged() {
            val instance = activeInstance ?: return
            instance.mainHandler.post { instance.handleEtaUiVisibilityChanged() }
        }

        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        private const val WAKE_LOCK_TAG = "Eta:AgentRun"
        private const val LEGACY_STOPPED_ERROR = "Stopped"
        private const val SYNTHETIC_STOPPED_ERROR = "eta_status:stopped"
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_STAGE_WINDOW_WIDTH_DP = 324
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_STAGE_HEIGHT_DP = 524
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** An injected click is only a point; the target control often sits right at the capsule edge, so the hit region is expanded slightly outward. */
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
