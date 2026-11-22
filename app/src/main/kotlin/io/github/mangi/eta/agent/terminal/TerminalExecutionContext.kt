package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject

/** Fixes the directory and Linux environment for a single run; the Android default identity is chosen by confirmed authorization. */
internal data class TerminalExecutionContext(
    val linuxEnvironment: TerminalEnvironment,
    val linuxRootfsPath: String?,
    val userWorkspacePath: String = TerminalRuntime.userWorkspacePath,
    val sharedMounts: List<SharedFolderMount> = emptyList(),
) {
    init {
        require(linuxEnvironment.isLinux)
    }

    val androidWorkspacePath: String
        get() = userWorkspacePath

    val linuxBackend: LinuxExecutionBackend
        get() = LinuxEnvironmentPaths.backendOf(linuxRootfsPath)

    val linuxIdentity: String
        get() = if (linuxBackend == LinuxExecutionBackend.PROOT) "user" else "root"

    val linuxWorkspacePath: String
        get() = TerminalRuntime.LINUX_WORKSPACE_PATH

    val linuxHostWorkspacePath: String
        get() = if (linuxIdentity == "root") TerminalRuntime.ROOT_WORKSPACE_PATH else userWorkspacePath

    val linuxReady: Boolean
        get() = LinuxEnvironmentPaths.rootfsReady(linuxRootfsPath)

    val linuxCommonToolsReady: Boolean
        get() = AlpineEnvironmentPaths.commonToolsReady(linuxRootfsPath)

    val linuxInstalledProfiles: List<String>
        get() {
            if (!linuxReady || linuxRootfsPath.isNullOrBlank()) return emptyList()
            val rootfs = File(linuxRootfsPath)
            return buildList {
                LinuxPackageProfiles.ALL.forEach { profile ->
                    if (linuxPackageProfileReady(rootfs, profile)) add(profile.id)
                }
                if (linuxApkAnalysisReady(rootfs)) add(APK_ANALYSIS_PROFILE_ID)
            }
        }

    fun identityFor(environment: TerminalEnvironment, rootAvailable: Boolean): String =
        if (environment.isLinux) linuxIdentity else if (rootAvailable) "root" else "user"

    fun description(rootAvailable: Boolean): String = buildString {
        append("The command and file tools of this run share a default execution context: environment=android, identity=")
        append(identityFor(TerminalEnvironment.ANDROID, rootAvailable))
        append(", workspace=")
        append(JSONObject.quote(androidWorkspacePath))
        append(".When Root is authorized, use Root directly, when authorization is unconfirmed, use the App identity.identity=user explicitly selects the normal identity; the working directory does not move when identity changes.\n")
        append("environment=linux uses the selected ")
        append(linuxEnvironment.wireName)
        append(", backend=")
        append(linuxBackend.wireName)
        append(", identity=")
        append(linuxIdentity)
        append(", ready=")
        append(linuxReady)
        append(", common_tools=")
        append(linuxCommonToolsReady)
        append(", profiles=")
        append(linuxInstalledProfiles.ifEmpty { listOf("none") }.joinToString("/"))
        append(".Linux workspace ")
        append(JSONObject.quote(linuxWorkspacePath))
        append(" maps to host directory ")
        append(JSONObject.quote(linuxHostWorkspacePath))
        append("(bind mount of the same physical folder, synced in real time both ways); ")
        if (linuxHostWorkspacePath == androidWorkspacePath) {
            append("is the exact same directory as the default Android workspace; edits on either side propagate directly.")
        } else {
            append("is a different directory from the default Android workspace; the Android side can directly access the same folder via ")
            append(JSONObject.quote(linuxHostWorkspacePath))
            append(".")
        }
        if (sharedMounts.isEmpty()) {
            append("No extra shared directory is currently configured(/workspace/mounts is mounted only after a mount entry is configured); ")
        } else {
            append("Configured extra shared directories(Linux mount point <-> Android source directory, the same physical folder underneath, synced in real time both ways): ")
            append(
                sharedMounts.joinToString(", ") { mount ->
                    "${JSONObject.quote("${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")} <-> ${JSONObject.quote(mount.sourcePath)}"
                },
            )
            append("; ")
        }
        if (linuxBackend == LinuxExecutionBackend.CHROOT) {
            append("Under chroot, the host \"/storage/emulated/0\"(and the symbolic link \"/sdcard\")and \"/data/local/tmp\" are mounted to Linux the same path.")
        } else {
            append("Under PRoot, if all file access permissions have been granted, the host \"/storage/emulated/0\" is also mounted to Linux the same path.")
        }
        append("Linux paths are interpreted in the Linux environment.")
    }

    private companion object {
        const val APK_ANALYSIS_PROFILE_ID = "apk_analysis"
    }
}
