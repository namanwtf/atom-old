package io.github.mangi.eta.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** 进程内日志节流器；使用单调时钟，不受系统时间调整影响。 */
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
