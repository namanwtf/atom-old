package io.github.namanwtf.atom.agent.overlay

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordination to keep the Agent's gestures clear of the status capsule.
 *
 * The capsule window is touchable, so accessibility-injected gestures and Root `input` events would be intercepted by it;
 * when a gesture lands within the capsule bounds, make the capsule non-touchable and fade it out first, then restore it after a delay once the gesture completes.
 * Restoration is debounced: consecutive taps and swipes come back only after the last one, so the capsule does not flicker up and down with every step.
 */
internal object AgentOverlayClearance {

    /** Implemented by the service that holds the capsule window; all methods are called on the main thread. */
    interface Host {
        /** The capsule window's touch bounds on screen (edge margin included); returns null when the window does not exist. */
        fun capsuleTouchBounds(): Rect?

        /** Immediately release touches and start the fade-out animation. */
        fun yieldCapsule()

        /** Restore touchability and fade in; when a queued restore must be cancelled, undo it via [cancelRestore]. */
        fun scheduleCapsuleRestore(delayMs: Long)

        fun cancelRestore()
    }

    @Volatile
    private var host: Host? = null

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }

    /**
     * Wraps a gesture with clearance before and after. [area] is the screen region the gesture covers (a point for taps, the start-end bounding box for swipes).
     * The caller executes the gesture synchronously on the tool thread; this waits for the fade-out to pass halfway before releasing, so the tap feedback appears on a clean canvas.
     */
    fun <T> around(area: Rect, gesture: () -> T): T {
        val current = host ?: return gesture()
        if (Looper.myLooper() == Looper.getMainLooper()) return gesture()
        val yielded = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        current.post {
            val bounds = current.capsuleTouchBounds()
            if (bounds != null && Rect.intersects(bounds, area)) {
                current.cancelRestore()
                current.yieldCapsule()
                yielded.set(true)
            }
            ready.countDown()
        }
        ready.await(MAX_WAIT_MS, TimeUnit.MILLISECONDS)
        // Touches were already released at yieldCapsule; wait briefly for the fade-out to free visual space, so the gesture indicator does not overlap the capsule.
        if (yielded.get()) Thread.sleep(FADE_HEAD_START_MS)
        return try {
            gesture()
        } finally {
            // The main thread executes in posting order: even if the wait times out and clearance happens after the gesture, this restore is still queued after it.
            current.post { if (yielded.get()) current.scheduleCapsuleRestore(RESTORE_DELAY_MS) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun Host.post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private const val MAX_WAIT_MS = 300L
    private const val FADE_HEAD_START_MS = 90L
    /** After an action the screen is usually observed once more and immediately followed by the next step; leave enough interval before returning to avoid back-and-forth flicker. */
    private const val RESTORE_DELAY_MS = 900L
}
