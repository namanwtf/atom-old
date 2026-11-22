package io.github.namanwtf.atom.agent.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.core.safeLogType
import io.github.namanwtf.atom.data.repository.ScheduledTaskRepository

/**
 * Single primary alarm scheduler.
 *
 * Always registers only the nearest upcoming due task time with the system [AlarmManager], avoiding multi-task contention over
 * the Doze quota (measured Android 17 `allow_while_idle_quota=72/h`). After the process is killed or restarted,
 * it is re-synced by an alarm cold start or a system broadcast.
 */
internal object ScheduledTaskAlarmScheduler {
    const val ACTION_ALARM_FIRE = "io.github.namanwtf.atom.action.SCHEDULED_TASK_ALARM"
    private const val REQUEST_CODE = 20401
    /** Minimum lead time for the system AlarmManager, ensuring a just-expired pending point can still trigger immediately. */
    private const val MIN_FUTURITY_MS = 5_000L

    suspend fun syncNextAlarm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val nextTask = ScheduledTaskRepository.nextEnabledTask(appContext)
        val pendingIntent = buildAlarmPendingIntent(appContext)

        if (nextTask == null || nextTask.nextRunAt == null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            return
        }

        val triggerAt = maxOf(nextTask.nextRunAt, nowMs + MIN_FUTURITY_MS)
        val canExact = runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            AndroidAgentLogger.debug {
                "Scheduled task alarm synced: exact=$canExact delta_ms=${triggerAt - nowMs}"
            }
        } catch (security: SecurityException) {
            // The exact alarm permission may be revoked at a concurrent instant; degrade to an inexact wake-up and log it.
            AndroidAgentLogger.warn("Exact alarm denied, falling back to inexact: type=${security.safeLogType()}")
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Scheduled task alarm sync failed: type=${failure.safeLogType()}")
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
