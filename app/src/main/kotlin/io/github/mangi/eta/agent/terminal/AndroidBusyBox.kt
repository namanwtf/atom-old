package io.github.mangi.eta.agent.terminal

/** Root-side BusyBox can only be probed inside an su process; the app process normally lacks permission to traverse /data/adb. */
internal object AndroidBusyBox {
    private val candidates = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )

    fun discoveryScript(variable: String = "eta_busybox"): String {
        require(variable.matches(Regex("[a-z_][a-z0-9_]*"))) { "Illegal Shell variable name" }
        val quotedCandidates = candidates.joinToString(" ") { shellQuote(it) }
        return "$variable=''; " +
            "for eta_candidate in $quotedCandidates; do " +
            "if [ -x \"${'$'}eta_candidate\" ]; then $variable=\"${'$'}eta_candidate\"; break; fi; " +
            "done; " +
            "if [ -z \"${'$'}$variable\" ] && command -v busybox >/dev/null 2>&1; then " +
            "$variable=${'$'}(command -v busybox); fi"
    }
}
