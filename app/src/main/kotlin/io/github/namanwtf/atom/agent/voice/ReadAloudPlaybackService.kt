package io.github.namanwtf.atom.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.core.safeLogType
import io.github.namanwtf.atom.ui.MainActivity

/**
 * Foreground playback service for chat read-aloud: it exists only while audio is actually playing, so read-aloud isn't frozen by the system when the app goes to the background or the screen locks.
 * Playback itself is still owned by the chat page's controller; the "Stop" action on the notification is handed back to the controller via [onStopRequested].
 */
internal class ReadAloudPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (failure: RuntimeException) {
            // When foreground eligibility is denied, read-aloud still continues in the foreground, only it may be paused by the system when moving to the background.
            AndroidAgentLogger.warn("Read-aloud foreground failed: type=${failure.safeLogType()}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.read_aloud_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ReadAloudPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4285F4.toInt())
            .setContentTitle(getString(R.string.read_aloud_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, getString(R.string.speech_read_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.github.namanwtf.atom.action.STOP_READ_ALOUD"
        private const val CHANNEL = "atom_read_aloud"
        private const val NOTIFICATION_ID = 4204

        /** Stop entry for the current read-aloud; only one read-aloud exists at a time, and a later one replaces the previous. */
        @Volatile private var onStopRequested: (() -> Unit)? = null

        /** Called at the moment audio starts after the user taps read-aloud; the app is still in the foreground then, so the foreground service can be started. */
        fun start(context: Context, onStop: () -> Unit) {
            onStopRequested = onStop
            try {
                context.startForegroundService(Intent(context, ReadAloudPlaybackService::class.java))
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn("Read-aloud service start rejected: type=${failure.safeLogType()}")
            }
        }

        fun stop(context: Context) {
            onStopRequested = null
            context.stopService(Intent(context, ReadAloudPlaybackService::class.java))
        }
    }
}
