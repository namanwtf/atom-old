package io.github.namanwtf.atom.agent.terminal

/** Root-side BusyBox can only be probed inside an su process; the app process normally lacks permission to traverse /data/adb. */
internal object AndroidBusyBox {
    private val candidates = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )

    fun discoveryScript(variable: String = "atom_busybox"): String {
        require(variable.matches(Regex("[a-z_][a-z0-9_]*"))) { "Illegal Shell variable name" }
        val quotedCandidates = candidates.joinToString(" ") { shellQuote(it) }
        return "$variable=''; " +
            "for atom_candidate in $quotedCandidates; do " +
            "if [ -x \"${'$'}atom_candidate\" ]; then $variable=\"${'$'}atom_candidate\"; break; fi; " +
            "done; " +
            "if [ -z \"${'$'}$variable\" ] && command -v busybox >/dev/null 2>&1; then " +
            "$variable=${'$'}(command -v busybox); fi"
    }
}
