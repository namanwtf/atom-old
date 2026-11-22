package io.github.namanwtf.atom.agent.terminal

import android.content.Context
import java.io.File

/** Paths for the Atom-managed Linux tool environment; internal historical package names aren't exposed. */
internal object AlpineEnvironmentPaths {
    const val READY_MARKER = LinuxEnvironmentPaths.READY_MARKER
    const val COMMON_TOOLS_MARKER = ".atom-common-tools-ready"
    const val APK_ANALYSIS_MARKER = ".atom-apk-analysis-ready"
    const val PYTHON_TOOLS_MARKER = ".atom-python-tools-ready"
    const val NODE_TOOLS_MARKER = ".atom-node-tools-ready"
    const val SSH_TOOLS_MARKER = ".atom-ssh-tools-ready"
    const val TOOLSET_REVISION = 1
    const val APK_ANALYSIS_REVISION = 1
    const val PYTHON_TOOLS_REVISION = 1
    // revision 2: the Debian image additionally installs libatomic1; already-ready environments must re-run installation to fill the dependency.
    const val NODE_TOOLS_REVISION = 2
    const val SSH_TOOLS_REVISION = 1

    fun environmentDir(context: Context): File =
        LinuxEnvironmentPaths.environmentDir(context, LinuxDistribution.ALPINE)

    fun rootfsDir(context: Context): File =
        LinuxEnvironmentPaths.rootfsDir(context, LinuxDistribution.ALPINE)

    fun artifactDir(context: Context): File =
        File(context.cacheDir, "linux-installer/artifacts")

    fun profileStagingDir(context: Context, profile: String): File =
        File(context.cacheDir, "linux-installer/profiles/$profile.installing")

    fun rootfsReady(rootfsPath: String?): Boolean {
        return LinuxEnvironmentPaths.rootfsReady(rootfsPath)
    }

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        val marker = File(rootfsPath, COMMON_TOOLS_MARKER)
        if (!marker.isFile) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "toolset=$TOOLSET_REVISION" }
            }
        }.getOrDefault(false)
    }

}
