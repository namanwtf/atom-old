package io.github.namanwtf.atom

internal object AppProcessPolicy {
    fun shouldInitializeFullRuntime(processName: String, packageName: String): Boolean =
        processName == packageName
}
