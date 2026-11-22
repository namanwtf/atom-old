package io.github.namanwtf.atom.agent.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeWire
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.core.safeLogType
import io.github.namanwtf.atom.data.model.ScheduledTask
import io.github.namanwtf.atom.data.model.ScheduledTaskRun
import io.github.namanwtf.atom.ui.MainActivity

/**
 * Scheduled-task result and alert notification management.
 *
 * - Routine result pushes use a dedicated channel, support expanded reading of the 1~3 line core conclusion, and open the full conversation on tap to continue asking;
 * - Failures and consecutive-failure circuit breaking use a dedicated alert channel;
 * - GUI tasks that require the physical main screen to be unlocked issue an interactive "Run now" reminder instead of barging through the lock screen in the middle of the night.
 */
internal object ScheduledTaskNotificationManager {
    private const val CHANNEL_RESULTS = "atom_scheduled_results"
    private const val CHANNEL_ALERTS = "atom_scheduled_alerts"
    private const val BRAND_COLOR = 0xFF4285F4.toInt()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                context.getString(R.string.scheduled_notification_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.scheduled_notification_channel_results_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.scheduled_notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.scheduled_notification_channel_alerts_desc)
            }
        )
    }

    fun notifyResult(
        context: Context,
        task: ScheduledTask,
        run: ScheduledTaskRun,
        summaryText: String,
    ): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false

        val cleanSummary = summaryText.trim().ifBlank {
            context.getString(R.string.scheduled_notification_completed_fallback)
        }
        val contentIntent = buildOpenRunIntent(context, task.id, run.archiveConversationKey)
        val notification = Notification.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(task.name.ifBlank { context.getString(R.string.route_scheduled_tasks) })
            .setContentText(cleanSummary)
            .setStyle(Notification.BigTextStyle().bigText(cleanSummary.take(600)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        return runCatching {
            manager.notify(notificationIdFor(task.id, run.id), notification)
            true
        }.getOrElse { failure ->
            AndroidAgentLogger.warn("Scheduled result notify failed: type=${failure.safeLogType()}")
            false
        }
    }

    fun notifyFailureOrAutoDisabled(
        context: Context,
        task: ScheduledTask,
        reason: String,
        autoDisabled: Boolean,
    ): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false

        val title = if (autoDisabled) {
            context.getString(R.string.scheduled_notification_auto_disabled_title, task.name)
        } else {
            context.getString(R.string.scheduled_notification_failed_title, task.name)
        }
        val body = reason.trim().ifBlank {
            context.getString(R.string.scheduled_notification_failed_fallback)
        }
        val contentIntent = buildOpenTaskDetailIntent(context, task.id)
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body.take(400)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        return runCatching {
            manager.notify(alertNotificationIdFor(task.id), notification)
            true
        }.getOrElse { failure ->
            AndroidAgentLogger.warn("Scheduled alert notify failed: type=${failure.safeLogType()}")
            false
        }
    }

    /** Scheduled tasks that need physical main-screen GUI actions while locked send an interactive reminder notification for the user to tap and run. */
    fun notifyGuiReadyForUser(
        context: Context,
        task: ScheduledTask,
        reason: String,
    ): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false

        val runNowIntent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ScheduledTaskAlarmReceiver.ACTION_RUN_TASK_NOW
            putExtra(ScheduledTaskAlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        val runNowPendingIntent = PendingIntent.getBroadcast(
            context,
            ("run_now:${task.id}".hashCode() and 0x7FFFFFFF),
            runNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detailIntent = buildOpenTaskDetailIntent(context, task.id)
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(context.getString(R.string.scheduled_notification_gui_ready_title, task.name))
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(detailIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.scheduled_action_run_now),
                    runNowPendingIntent,
                ).build()
            )
            .setAutoCancel(true)
            .build()

        return runCatching {
            manager.notify(alertNotificationIdFor(task.id), notification)
            true
        }.getOrDefault(false)
    }

    private fun buildOpenRunIntent(
        context: Context,
        taskId: String,
        archiveConversationKey: String?,
    ): PendingIntent {
        val intent = if (!archiveConversationKey.isNullOrBlank()) {
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, AgentRuntimeWire.SCHEDULED_TASK_HANDOFF_SOURCE)
                putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archiveConversationKey)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SCHEDULED_TASK_DETAIL
                putExtra(MainActivity.EXTRA_SCHEDULED_TASK_ID, taskId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        return PendingIntent.getActivity(
            context,
            ("open_run:$taskId:${archiveConversationKey.orEmpty()}".hashCode() and 0x7FFFFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenTaskDetailIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SCHEDULED_TASK_DETAIL
            putExtra(MainActivity.EXTRA_SCHEDULED_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            ("open_task:$taskId".hashCode() and 0x7FFFFFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(taskId: String, runId: String): Int =
        31_000 + ((taskId.hashCode() * 31 + runId.hashCode()) and 0x0FFF)

    private fun alertNotificationIdFor(taskId: String): Int =
        35_000 + (taskId.hashCode() and 0x0FFF)
}
