package io.github.mangi.eta.agent.scheduler

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.ScheduledTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Internal receiver for the exact alarm and notification quick actions (`exported="false"`).
 *
 * Immediately hands execution over to [ScheduledTaskRunner] under `goAsync()` protection; the runner completes the WakeLock and foreground-service takeover within the Android Doze 10-second
 * temporary exemption window.
 */
internal class ScheduledTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    ScheduledTaskAlarmScheduler.ACTION_ALARM_FIRE -> {
                        ScheduledTaskRunner.onAlarmFired(appContext)
                    }
                    ACTION_RUN_TASK_NOW -> {
                        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() }
                        if (taskId != null) {
                            ScheduledTaskRunner.triggerManualRun(appContext, taskId)
                        }
                    }
                }
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Scheduled alarm receiver failed: type=${failure.safeLogType()}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RUN_TASK_NOW = "io.github.mangi.eta.action.RUN_SCHEDULED_TASK_NOW"
        const val EXTRA_TASK_ID = "scheduled_task_id"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * System lifecycle broadcast receiver: rebuilds scheduling on reboot, time change, timezone change, app update, or exact-alarm permission change.
 *
 * Strictly never starts a foreground service directly inside the boot broadcast to run overdue model tasks, avoiding Android 15+ boot FGS restrictions.
 */
internal class ScheduledTaskSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val recomputeAll = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val pending = goAsync()
        receiverScope.launch {
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    action == Intent.ACTION_TIME_CHANGED ||
                    action == Intent.ACTION_TIMEZONE_CHANGED ||
                    action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
                ) {
                    ScheduledTaskRepository.reconcileAfterRestartOrClockChange(
                        recomputeAllSchedules = recomputeAll,
                        rescheduleAlarm = true,
                        context = appContext,
                    )
                }
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Scheduled system reconcile failed: type=${failure.safeLogType()}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
