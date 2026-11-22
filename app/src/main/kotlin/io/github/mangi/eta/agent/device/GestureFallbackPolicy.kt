package io.github.mangi.eta.agent.device

/** Root replay is allowed only when it is confirmed the gesture was never submitted to the system. */
internal object GestureFallbackPolicy {
    fun mayFallbackToRoot(errorCode: String): Boolean =
        errorCode == "GESTURE_NOT_DISPATCHED"
}
