package io.github.namanwtf.atom.agent.terminal

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Handles shell process admission, ownership identification, process-tree termination, and reclamation. */
internal class ShellProcessSupervisor(
    private val allowTreeFallback: Boolean = !isAndroidRuntime(),
    private val setsidCommand: String = "setsid",
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val ptyExecutable: () -> File? = { TerminalRuntime.nativeExecutable(PTY_EXECUTABLE) },
) {
    private companion object {
        const val PROCESS_REAP_TIMEOUT_MS = 1_000L
        const val PROCESS_OWNERSHIP_WAIT_MS = 500L
        const val PROCESS_SIGNAL_TIMEOUT_MS = 1_000L
        const val DEFAULT_PTY_COLS = 120
        const val DEFAULT_PTY_ROWS = 40
        const val PTY_TERM_TYPE = "xterm-256color"
        const val BUSYBOX_APPLET_DIR = "${TerminalRuntime.ROOT_WORKSPACE_PATH}/.busybox"

        fun isAndroidRuntime(): Boolean =
            System.getProperty("java.vm.name").orEmpty().equals("Dalvik", ignoreCase = true) ||
                System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)
    }

    private val activeProcesses = mutableSetOf<Process>()
    private val processMetadata = mutableMapOf<Process, ProcessMetadata>()

    @Volatile
    var isClosing: Boolean = false
        private set

    /**
     * ProcessBuilder.start() must run outside the lock; after startup, a short critical section performs admission or rejection.
     * Each shell preferably enters its own session and writes its real shell PID into a temp file used only by this process.
     */
    fun startShellProcess(
        identity: String,
        command: String?,
        mergeStderr: Boolean,
        environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
        linuxRootfsPath: String? = null,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): Process? {
        if (isClosing) return null
        require(identity == "root" || identity == "user") { "identity supports only root/user" }
        require(!environment.isLinux || identity == "root" || LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) {
            "Linux tool environment supports only root identity"
        }
        if (environment.isLinux && identity == "root" && LinuxEnvironmentPaths.backendOf(linuxRootfsPath) == LinuxExecutionBackend.PROOT) return null
        if (identity == "root" && !rootAvailable()) return null
        val ownershipFile = runCatching {
            File.createTempFile("atom-terminal-", ".owner")
        }.getOrNull() ?: return null
        val ownershipToken = UUID.randomUUID().toString().replace("-", "")
        val launcher = try {
            buildTrackedShellLauncher(
                ownershipFile = ownershipFile,
                ownershipToken = ownershipToken,
                command = command,
                identity = identity,
                environment = environment,
                linuxRootfsPath = linuxRootfsPath,
                linuxSharedMounts = linuxSharedMounts,
                pty = pty,
                ptyCols = ptyCols,
                ptyRows = ptyRows,
            )
        } catch (_: IllegalArgumentException) {
            ownershipFile.delete()
            return null
        } catch (_: java.io.IOException) {
            ownershipFile.delete()
            return null
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", launcher)
            } else {
                ProcessBuilder("sh", "-c", launcher)
            }
            if (identity == "user" && environment == TerminalEnvironment.ANDROID) {
                builder.environment()["HOME"] = TerminalRuntime.userWorkspacePath
            }
            builder.redirectErrorStream(mergeStderr).start()
        }.getOrElse {
            ownershipFile.delete()
            return null
        }
        val metadata = ProcessMetadata(identity, ownershipFile, ownershipToken)
        val accepted = synchronized(activeProcesses) {
            if (isClosing) {
                false
            } else {
                activeProcesses += process
                processMetadata[process] = metadata
                true
            }
        }
        if (!accepted) {
            terminateAndReap(process, metadata)
            metadata.ownershipFile.delete()
            return null
        }
        val ownership = resolveProcessOwnership(metadata)
        val stillAccepted = synchronized(activeProcesses) {
            processMetadata[process] === metadata && !isClosing
        }
        if (ownership == null || !stillAccepted) {
            terminateAndReap(process, metadata)
            unregisterProcess(process)
            return null
        }
        return process
    }

    internal fun ownershipOf(process: Process): ShellProcessOwnership? {
        val metadata = synchronized(activeProcesses) { processMetadata[process] } ?: return null
        val ownership = resolveProcessOwnership(metadata) ?: return null
        return ShellProcessOwnership(ownership.pid, metadata.ownershipToken, ownership.isolatedGroup)
    }

    fun transferActiveProcess(process: Process, transfer: () -> Unit): Boolean =
        synchronized(activeProcesses) {
            if (isClosing) return false
            transfer()
            activeProcesses -= process
            true
        }

    fun beginClosing() {
        synchronized(activeProcesses) {
            isClosing = true
        }
    }

    fun takeRemainingProcesses(): List<Process> = synchronized(activeProcesses) {
        activeProcesses.toList().also { activeProcesses.clear() }
    }

    fun terminateProcessTree(process: Process) {
        terminateProcessTree(process, metadataOverride = null)
    }

    fun terminateAndReap(process: Process) {
        terminateAndReap(process, metadataOverride = null)
    }

    fun reapProcess(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.waitFor(PROCESS_REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    fun unregisterProcess(process: Process) {
        val metadata = synchronized(activeProcesses) {
            activeProcesses -= process
            processMetadata.remove(process)
        }
        metadata?.ownershipFile?.delete()
    }

    /** After the leader exits, only revoke ownership; never signal a PID/PGID that may have been reused. */
    fun retireExitedProcess(process: Process) {
        unregisterProcess(process)
    }

    internal fun buildTrackedShellLauncher(
        ownershipFile: File,
        ownershipToken: String,
        command: String?,
        identity: String,
        environment: TerminalEnvironment,
        linuxRootfsPath: String?,
        linuxSharedMounts: List<SharedFolderMount> = emptyList(),
        pty: Boolean = false,
        ptyCols: Int = DEFAULT_PTY_COLS,
        ptyRows: Int = DEFAULT_PTY_ROWS,
    ): String {
        val managedCommand = command?.let { value ->
            "$value\natom_status=${'$'}?\nwait\nexit ${'$'}atom_status"
        }
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> buildAndroidPayload(
                identity = identity,
                command = managedCommand,
                interactivePty = pty && command == null,
            )
            else -> buildLinuxPayload(
                rootfsPath = requireNotNull(linuxRootfsPath) {
                    "Linux tool environment rootfs is not configured"
                },
                command = managedCommand,
                sharedMounts = linuxSharedMounts,
                termType = if (pty) PTY_TERM_TYPE else "dumb",
            )
        }

        val path = shellQuote(ownershipFile.absolutePath)
        val exportOwner = "export $ATOM_PROCESS_OWNER_ENV=${shellQuote(ownershipToken)}"
        if (pty) {
            // The PTY session is set up by the native component as an independent session that reclaims the whole process group on exit; Root and normal identity share one entry point.
            val executable = ptyExecutable() ?: throw IOException("PTY component is unavailable")
            val script = "printf '%s group\\n' \"${'$'}${'$'}\" > $path; export TERM=$PTY_TERM_TYPE; $payload"
            return "$exportOwner; exec ${shellQuote(executable.absolutePath)} $ptyRows $ptyCols -- sh -c ${shellQuote(script)}"
        }
        val cleanupGroup =
                "atom_cleanup_proc_group() { " +
                "for stat_file in /proc/[0-9]*/stat; do " +
                "[ -r \"${'$'}stat_file\" ] || continue; " +
                "IFS= read -r stat < \"${'$'}stat_file\" || continue; " +
                "pid=${'$'}{stat%% *}; rest=${'$'}{stat##*) }; set -- ${'$'}rest; " +
                "[ \"${'$'}3\" = \"${'$'}${'$'}\" ] || continue; " +
                "[ \"${'$'}pid\" = \"${'$'}${'$'}\" ] || kill -9 \"${'$'}pid\" 2>/dev/null; " +
                "done; }; " +
                "atom_cleanup_ps_group() { " +
                "for pid in ${'$'}(ps -axo pid=,pgid= | " +
                "awk -v group=\"${'$'}${'$'}\" '${'$'}2 == group && ${'$'}1 != group { print ${'$'}1 }'); do " +
                "kill -9 \"${'$'}pid\" 2>/dev/null; done; }; " +
                "if [ -d /proc/${'$'}${'$'} ]; then " +
                "atom_cleanup_proc_group; atom_cleanup_proc_group; " +
                "else atom_cleanup_ps_group; atom_cleanup_ps_group; fi"
        val groupScript =
            "printf '%s group\\n' \"${'$'}${'$'}\" > $path; " +
                "$payload; atom_status=${'$'}?; $cleanupGroup; exit ${'$'}atom_status"
        val treeScript =
            "printf '%s tree\\n' \"${'$'}${'$'}\" > $path; $payload"
        val fallback = if (allowTreeFallback) {
            treeScript
        } else {
            "printf '%s unavailable\\n' \"${'$'}${'$'}\" > $path; exit 126"
        }
        val safeSetsid = shellQuote(setsidCommand)
        return "$exportOwner; if command -v $safeSetsid >/dev/null 2>&1; then " +
            "exec $safeSetsid -w sh -c ${shellQuote(groupScript)}; else $fallback; fi"
    }

    /**
     * Root sessions preferably enter Magisk/KernelSU/APatch's BusyBox standalone ash to supply applets outside the Android PATH.
     *
     * Interactive PTY exception: with BusyBox 1.35.0-1.36.x, while ash waits for input at the prompt, any signal interrupting poll causes
     * line editing to return and start a fresh prompt on a new line (upstream bug 15256, fixed in 1.37.0, not backported to 1.36.1). The console syncs window size on soft-keyboard and orientation
     * changes, so each SIGWINCH would add an extra empty prompt. On those versions, fall back to the system sh (matching su's default shell)
     * and append BusyBox applet links to the end of PATH so system commands still take precedence.
     */
    internal fun buildAndroidPayload(identity: String, command: String?, interactivePty: Boolean = false): String {
        val shellArgument = command?.let { "-c ${shellQuote(it)}" }.orEmpty()
        if (identity != "root") {
            return "sh $shellArgument".trimEnd()
        }
        val discovery = AndroidBusyBox.discoveryScript()
        val ash = "export ASH_STANDALONE=1; \"${'$'}atom_busybox\" ash $shellArgument"
        val busyBoxShell = if (interactivePty) {
            val applets = shellQuote(BUSYBOX_APPLET_DIR)
            "case \"${'$'}(\"${'$'}atom_busybox\" 2>&1 | head -n 1)\" in " +
                "*' v1.35.'*|*' v1.36.'*) " +
                "mkdir -p $applets 2>/dev/null && \"${'$'}atom_busybox\" --install -s $applets 2>/dev/null; " +
                "export PATH=\"${'$'}PATH:\"$applets; sh;; " +
                "*) $ash;; esac"
        } else {
            ash
        }
        return "$discovery; " +
            "if [ -n \"${'$'}atom_busybox\" ]; then " +
            "export ATOM_BUSYBOX=\"${'$'}atom_busybox\"; " +
            "$busyBoxShell; " +
            "else sh $shellArgument; fi"
    }

    /**
     * The Linux tool environment always starts in its own mount namespace to avoid bind mounts leaking into the global Android namespace.
     * chroot is not a security sandbox; it only provides a full Linux userland. Android system operations should still go through the android environment.
     * At namespace setup, [sharedMounts] are bind-mounted one by one into the rootfs's workspace/mounts/<name> per the current configuration;
     * they are reclaimed with the namespace when the session ends, leaving no global mounts to unmount on the Android side.
     */
    internal fun buildLinuxPayload(
        rootfsPath: String,
        command: String?,
        sharedMounts: List<SharedFolderMount> = emptyList(),
        termType: String = "dumb",
    ): String {
        if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) {
            return ProotCommandBuilder.payload(rootfsPath, command, sharedMounts, termType)
        }
        val rootfs = shellQuote(rootfsPath)
        val mode = if (command == null) "session" else "command"
        val payload = shellQuote(command.orEmpty())
        // name is validated by SharedFolderMounts to contain only [A-Za-z0-9._-], so it can be safely spliced into a double-quoted path.
        val mountsBlock = sharedMounts.joinToString("\n") { mount ->
            "atom_mount_optional ${shellQuote(mount.sourcePath)} " +
                "\"\$atom_rootfs${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}\" bind"
        }
        val innerScriptHead = """
            atom_rootfs=${'$'}1
            atom_busybox=${'$'}2
            atom_mode=${'$'}3
            atom_payload=${'$'}4
            atom_mount_required() {
              atom_source=${'$'}1
              atom_target=${'$'}2
              atom_options=${'$'}3
              "${'$'}atom_busybox" mkdir -p "${'$'}atom_target" || exit 125
              "${'$'}atom_busybox" mount -o "${'$'}atom_options" "${'$'}atom_source" "${'$'}atom_target" || exit 125
            }
            atom_mount_optional() {
              atom_source=${'$'}1
              atom_target=${'$'}2
              atom_options=${'$'}3
              "${'$'}atom_busybox" mkdir -p "${'$'}atom_target" 2>/dev/null || return 0
              "${'$'}atom_busybox" mount -o "${'$'}atom_options" "${'$'}atom_source" "${'$'}atom_target" 2>/dev/null || true
            }
            "${'$'}atom_busybox" mount -t proc proc "${'$'}atom_rootfs/proc" || exit 125
            atom_mount_required /dev "${'$'}atom_rootfs/dev" rbind
            atom_mount_optional /sys "${'$'}atom_rootfs/sys" rbind
            if [ -d /storage/emulated/0 ]; then
              atom_mount_optional /storage/emulated/0 "${'$'}atom_rootfs/storage/emulated/0" bind
            fi
            [ -d /data/local/tmp ] || exit 125
            atom_mount_required /data/local/tmp "${'$'}atom_rootfs/data/local/tmp" bind
            "${'$'}atom_busybox" mkdir -p /data/local/tmp/atom || exit 125
            atom_mount_required /data/local/tmp/atom "${'$'}atom_rootfs/workspace" bind
        """.trimIndent()
        val innerScriptTail = """
            if [ "${'$'}atom_mode" = command ]; then
              if [ -x "${'$'}atom_rootfs/usr/bin/env" ]; then
                exec "${'$'}atom_busybox" chroot "${'$'}atom_rootfs" /usr/bin/env -i \
                  HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                  LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/sh -lc "${'$'}atom_payload"
              fi
              exec "${'$'}atom_busybox" chroot "${'$'}atom_rootfs" /bin/busybox env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType NO_COLOR=1 \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh -lc "${'$'}atom_payload"
            fi
            if [ -x "${'$'}atom_rootfs/usr/bin/env" ]; then
              exec "${'$'}atom_busybox" chroot "${'$'}atom_rootfs" /usr/bin/env -i \
                HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
                LANG=C.UTF-8 LC_ALL=C.UTF-8 \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                /bin/sh
            fi
            exec "${'$'}atom_busybox" chroot "${'$'}atom_rootfs" /bin/busybox env -i \
              HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=$termType \
              LANG=C.UTF-8 LC_ALL=C.UTF-8 \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/sh
        """.trimIndent()
        val innerScript = if (mountsBlock.isEmpty()) {
            "$innerScriptHead\n$innerScriptTail"
        } else {
            "$innerScriptHead\n$mountsBlock\n$innerScriptTail"
        }
        val discovery = AndroidBusyBox.discoveryScript()
        // Alpine's /bin/sh is an absolute symlink to /bin/busybox; on the Android side, -x would resolve the link target against the host root
        // and wrongly report it missing; treat symlinks as present and let the kernel, after chroot, resolve actual executability.
        return "$discovery; " +
            "[ -n \"${'$'}atom_busybox\" ] || { echo 'ATOM_LINUX_BUSYBOX_MISSING' >&2; exit 127; }; " +
            "atom_rootfs=$rootfs; " +
            "[ -f \"${'$'}atom_rootfs/${LinuxEnvironmentPaths.READY_MARKER}\" ] && " +
            "{ [ -x \"${'$'}atom_rootfs/bin/sh\" ] || [ -h \"${'$'}atom_rootfs/bin/sh\" ]; } && " +
            "( [ -x \"${'$'}atom_rootfs/usr/bin/env\" ] || [ -x \"${'$'}atom_rootfs/bin/busybox\" ] ) || " +
            "{ echo 'ATOM_LINUX_ENVIRONMENT_NOT_READY' >&2; exit 127; }; " +
            "\"${'$'}atom_busybox\" unshare -m --propagation private " +
            "\"${'$'}atom_busybox\" sh -c ${shellQuote(innerScript)} atom-linux " +
            "\"${'$'}atom_rootfs\" \"${'$'}atom_busybox\" $mode $payload"
    }

    private fun terminateProcessTree(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        val metadata = metadataOverride ?: synchronized(activeProcesses) { processMetadata[process] }
        val ownership = metadata?.let(::resolveProcessOwnership)
        if (metadata != null && ownership != null) {
            if (ownership.isolatedGroup) {
                signalProcessGroup(metadata, ownership)
            } else {
                signalProcessTree(metadata, ownership)
            }
        }
        runCatching { if (process.isAlive) process.destroy() }
        runCatching { if (process.isAlive) process.destroyForcibly() }
    }

    private fun terminateAndReap(
        process: Process,
        metadataOverride: ProcessMetadata?,
    ) {
        terminateProcessTree(process, metadataOverride)
        reapProcess(process)
    }

    private fun resolveProcessOwnership(metadata: ProcessMetadata): ProcessOwnership? {
        metadata.ownership?.let { ownership -> return ownership }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_OWNERSHIP_WAIT_MS)
        do {
            metadata.ownership?.let { ownership -> return ownership }
            val content = runCatching { metadata.ownershipFile.readText().trim() }.getOrNull().orEmpty()
            if (content.isNotEmpty()) {
                val ownership = parseProcessOwnership(content) ?: return null
                metadata.ownership = ownership
                metadata.ownershipFile.delete()
                return ownership
            }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(10)
        } while (true)
    }

    private fun parseProcessOwnership(content: String): ProcessOwnership? {
        val parts = content.split(Regex("\\s+"))
        val pid = parts.getOrNull(0)?.toLongOrNull()?.takeIf { value -> value > 1 } ?: return null
        val isolatedGroup = when (parts.getOrNull(1)) {
            "group" -> true
            "tree" -> false
            else -> return null
        }
        return ProcessOwnership(pid = pid, isolatedGroup = isolatedGroup)
    }

    private fun signalProcessGroup(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = "kill -9 -${ownership.pid} 2>/dev/null || true",
            requireOwnershipProof = true,
        )
    }

    private fun signalProcessTree(metadata: ProcessMetadata, ownership: ProcessOwnership) {
        val script =
            "kill_tree() { " +
                "children=${'$'}(ps -axo pid=,ppid= | " +
                "awk -v parent=\"${'$'}1\" '${'$'}2 == parent { print ${'$'}1 }'); " +
                "for child in ${'$'}children; do kill_tree \"${'$'}child\"; done; " +
                "kill -9 \"${'$'}1\" 2>/dev/null || true; " +
                "}; kill_tree ${ownership.pid}"
        runSignalCommand(
            metadata = metadata,
            ownership = ownership,
            command = script,
            requireOwnershipProof = false,
        )
    }

    private fun runSignalCommand(
        metadata: ProcessMetadata,
        ownership: ProcessOwnership,
        command: String,
        requireOwnershipProof: Boolean,
    ) {
        val procPath = "/proc/${ownership.pid}"
        val expectedOwner = shellQuote("$ATOM_PROCESS_OWNER_ENV=${metadata.ownershipToken}")
        val guardedCommand = if (requireOwnershipProof) {
            "[ -e $procPath ] || exit 0; " +
                "[ -r $procPath/environ ] || exit 0; " +
                "tr '\\000' '\\n' < $procPath/environ | grep -Fqx $expectedOwner || exit 0; " +
                command
        } else {
            command
        }
        val process = runCatching {
            val builder = if (metadata.identity == "root") {
                ProcessBuilder("su", "-c", guardedCommand)
            } else {
                ProcessBuilder("sh", "-c", guardedCommand)
            }
            builder
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
        }.getOrNull() ?: return
        runCatching { process.outputStream.close() }
        val finished = runCatching {
            process.waitFor(PROCESS_SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) runCatching { process.destroyForcibly() }
    }

    private class ProcessMetadata(
        val identity: String,
        val ownershipFile: File,
        val ownershipToken: String,
    ) {
        @Volatile
        var ownership: ProcessOwnership? = null
    }

    private data class ProcessOwnership(
        val pid: Long,
        val isolatedGroup: Boolean,
    )
}

internal data class ShellProcessOwnership(val pid: Long, val token: String, val isolatedGroup: Boolean)

/** Writes an ownership marker into the managed process's environment block; used before inspection and stop to avoid killing a reused PID. */
internal const val ATOM_PROCESS_OWNER_ENV = "ATOM_PROCESS_OWNER"

internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

internal data class OneShotShellResult(
    val exitCode: Int,
    val output: ByteArray,
    val stderr: ByteArray,
    val outputTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
)

/** The console PTY requires the bundled native component to already be extracted as an executable by the installer; starts no process and can be called directly at entry. */
internal fun ptySupported(): Boolean = TerminalRuntime.nativeExecutable(PTY_EXECUTABLE) != null

internal const val PTY_EXECUTABLE = "libatom_pty.so"
internal const val SHELL_STDIN_FAILED_EXIT_CODE = -4

/**
 * One-shot shell command primitive with timeout reclamation and bounded output collection. The caller is responsible for command construction and output interpretation;
 * on timeout or supervisor shutdown the whole process tree is reclaimed by [ShellProcessSupervisor].
 */
internal fun runOneShotShell(
    processSupervisor: ShellProcessSupervisor,
    identity: String,
    command: String,
    timeoutSeconds: Long,
    stdin: ByteArray? = null,
    environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    linuxRootfsPath: String? = null,
    linuxSharedMounts: List<SharedFolderMount> = emptyList(),
    maxOutputBytes: Int = Int.MAX_VALUE,
): OneShotShellResult {
    val process = processSupervisor.startShellProcess(
        identity = identity,
        command = command,
        mergeStderr = false,
        environment = environment,
        linuxRootfsPath = linuxRootfsPath,
        linuxSharedMounts = linuxSharedMounts,
    ) ?: return OneShotShellResult(
        if (processSupervisor.isClosing) -3 else -1,
        ByteArray(0),
        if (processSupervisor.isClosing) "Operation cancelled".toByteArray() else "Unable to start process".toByteArray(),
    )

    try {
        val output = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val outputThread = thread(name = "agent-terminal-stdout") {
            process.inputStream.use { input -> output.readFrom(input, maxOutputBytes) }
        }
        val stderrThread = thread(name = "agent-terminal-stderr") {
            process.errorStream.use { input -> stderr.readFrom(input, minOf(maxOutputBytes, 64 * 1024)) }
        }
        val stdinFailed = AtomicBoolean(false)
        val stdinThread = thread(name = "agent-terminal-stdin") {
            try {
                process.outputStream.use { out ->
                    if (stdin != null) out.write(stdin)
                }
            } catch (_: IOException) {
                // Validation failure or cancellation may close the read end first; the calling thread handles it with the exit code, and the write thread must not crash the Runtime.
                stdinFailed.set(true)
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            processSupervisor.terminateProcessTree(process)
            outputThread.join(500)
            stderrThread.join(500)
            stdinThread.join(500)
            processSupervisor.reapProcess(process)
            return OneShotShellResult(-2, output.bytes(), "Command execution timed out".toByteArray(), output.isTruncated(), stderr.isTruncated())
        }

        outputThread.join(500)
        stderrThread.join(500)
        stdinThread.join(500)
        val exitCode = process.exitValue()
        // Child-process failures keep their original semantics; when it claims success but input transfer failed, an incomplete write must not be treated as success.
        if (exitCode == 0 && stdin?.isNotEmpty() == true && stdinFailed.get()) {
            return OneShotShellResult(
                SHELL_STDIN_FAILED_EXIT_CODE, output.bytes(), "Command input transfer failed".toByteArray(),
                output.isTruncated(),
            )
        }
        return OneShotShellResult(
            exitCode, output.bytes(), stderr.bytes(), output.isTruncated(), stderr.isTruncated(),
        )
    } finally {
        if (processSupervisor.isClosing) {
            processSupervisor.terminateAndReap(process)
        } else {
            processSupervisor.reapProcess(process)
        }
        processSupervisor.unregisterProcess(process)
    }
}
