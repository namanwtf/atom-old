package io.github.namanwtf.atom.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** In-process log throttler; uses a monotonic clock so it is unaffected by system time adjustments. */
internal class LogThrottle(
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis
) {
    private val lastAcceptedAt = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, windowMs: Long): Boolean {
        require(key.isNotBlank()) { "Log throttle key must not be empty" }
        require(windowMs >= 0L) { "Log throttle window must not be negative" }

        val now = uptimeMillis()
        var accepted = false
        lastAcceptedAt.compute(key) { _, previous ->
            if (previous == null || now < previous || now - previous >= windowMs) {
                accepted = true
                now
            } else {
                previous
            }
        }
        return accepted
    }
}
