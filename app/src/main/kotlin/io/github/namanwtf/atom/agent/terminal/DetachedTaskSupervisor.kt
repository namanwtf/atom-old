package io.github.namanwtf.atom.agent.terminal

import io.github.namanwtf.atom.core.AgentLogger

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host for detached tasks: launches long-running processes that detach from any command-session process group and manages their lifecycle.
 *
 * The difference from ordinary terminal commands is reclamation semantics: the managed shell cleans up same-group processes before exiting, while detached tasks use
 * setsid to create an independent session outside that reclamation net; output goes to a workspace log file rather than memory, the task record is persisted,
 * and after an app restart surviving processes are reclaimed by pid + ownership token.
 *
 * All tasks are invalidated after a device reboot; when the app is force-stopped, user-identity tasks are killed along with it,
 * while root-identity tasks are unaffected.
 */
internal class DetachedTaskSupervisor(
    private val logger: AgentLogger,
    private val recordsFile: File,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val daemonDir: String = DEFAULT_DAEMON_DIR,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val oneShotSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(rootAvailable = rootAvailable),
    private val acquireUserLease: (String, () -> Unit) -> Boolean = TerminalRuntime::acquireUserTask,
    private val releaseUserLease: (String) -> Unit = TerminalRuntime::releaseUserTask,
) {
    companion object {
        const val DEFAULT_DAEMON_DIR = "/data/local/tmp/atom/daemon"
        const val LINUX_DAEMON_DIR = "/workspace/daemon"
        const val MAX_TASKS = 8
        const val MAX_RETAINED_RECORDS = 32
        const val MAX_LOG_READ_BYTES = 64 * 1024

        // One instance each is allowed for the AI side and UI side in-process; record-file read-modify-write must serialize through one lock.
        private val RECORDS_LOCK = Any()
        private val COMMAND_PROCESSES = java.util.concurrent.ConcurrentHashMap<String, CommandProcess>()
        private val USER_WAITERS = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** The AI tool side and UI side share one record file; its path may only be defined here. */
        fun defaultRecordsFile(context: Context): File =
            File(context.filesDir, "terminal-daemons.json")
    }


    /** command/cwd/identity/environment must already be normalized by the caller. */
    fun start(
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        commandTask: Boolean = false,
        description: String = "",
        timeoutMs: Long = 0,
    ): DaemonStartResult {
        if (identity != "root" && identity != "user") return DaemonStartResult.Failed("INVALID_ARGUMENT", "Execution identity is invalid")
        if (identity == "root" && !rootAvailable()) return DaemonStartResult.Failed("ROOT_REQUIRED", "Root authorization is unavailable")
        if (identity == "user" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) != LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "The selected Linux environment requires Root")
        }
        if (identity == "root" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) == LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("INVALID_IDENTITY", "Rootless Linux uses the normal app identity")
        }
        if (list().count { it.running } >= MAX_TASKS) {
            return DaemonStartResult.Failed("MAX_TASKS_REACHED", "Daemon task count has reached the limit $MAX_TASKS; stop unused tasks first")
        }
        val id = (if (commandTask) "job_" else "dm_") + UUID.randomUUID().toString().take(8)
        val token = UUID.randomUUID().toString().replace("-", "")
        if (commandTask) return startCommand(id, command, cwd, identity, environment, description, timeoutMs)
        if (identity == "user") return startUserDaemon(id, token, command, cwd, environment)
        val wireDir = wireDaemonDir(environment)
        val wirePidFile = "$wireDir/$id.pid"
        val wireLogFile = "$wireDir/$id.log"
        // The inner sh writes the pidfile before exec'ing the target command; the PID stays the same across the exec.
        val innerScript = "echo \$\$ > ${shellQuote(wirePidFile)}; " +
            "export $ATOM_PROCESS_OWNER_ENV=${shellQuote(token)}; " +
            "exec sh -c ${shellQuote(command)} >> ${shellQuote(wireLogFile)} 2>&1"
        val launcherScript = buildString {
            appendLine("mkdir -p ${shellQuote(wireDir)} || exit 1")
            appendLine("rm -f ${shellQuote(wirePidFile)}")
            appendLine("cd ${shellQuote(cwd)} || exit 1")
            // Both branches must background the process: setsid only detaches the session, and the caller would still wait in the foreground for a long-running command.
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("else")
            appendLine("  sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("fi")
            appendLine("i=0")
            appendLine("while [ \$i -lt 30 ]; do")
            appendLine("  [ -s ${shellQuote(wirePidFile)} ] && break")
            appendLine("  i=\$((i+1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            append("cat ${shellQuote(wirePidFile)} 2>/dev/null")
        }
        // The launcher must run bare: the managed shell's wait would hang on an already-backgrounded child, and its pre-exit same-group cleanup would kill them.
        val result = launchRaw(identity, environment, launcherScript, timeoutSeconds = 10)
        val outputText = result.output.decodeToString().trim()
        val pid = outputText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
        if (result.exitCode != 0 || pid == null || pid <= 1) {
            val message = outputText.ifBlank { "exit=${result.exitCode}" }
            logger.warn(
                "Agent terminal daemon action=start outcome=failed environment=${environment.wireName} " +
                    "exitCode=${result.exitCode} errorChars=${message.length}"
            )
            return DaemonStartResult.Failed("DAEMON_START_FAILED", message)
        }
        val task = DetachedTask(
            id = id,
            pid = pid,
            token = token,
            command = command,
            cwd = cwd,
            identity = identity,
            environment = environment,
            logPath = "$wireDir/$id.log",
            startedAt = System.currentTimeMillis(),
            commandTask = commandTask,
            description = description,
            timeoutMs = timeoutMs,
        )
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            stopTaskProcess(task)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "Unable to save the background task; check internal storage")
        }
        logger.info(
            "Agent terminal daemon action=start outcome=started taskId=$id " +
                "environment=${environment.wireName} identity=$identity commandChars=${command.length}"
        )
        return DaemonStartResult.Started(task)
    }

    /** Inspects all records: re-claims the living, keeps records of the dead for log viewing, and prunes excess exited records. */
    fun list(): List<DetachedTaskStatus> {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        if (tasks.isEmpty()) return emptyList()
        // Inspect by identity group: /proc/<pid>/environ is readable only by the process owner and root, so probing with the task's own identity is most reliable.
        val aliveById = mutableMapOf<String, Boolean>()
        tasks.filter { it.completedAt == null }.groupBy { it.identity }.forEach { (identity, group) ->
            if (identity == "root" && !rootAvailable()) return@forEach
            val probeScript = aliveCheckFunction() + "\n" + group.joinToString("\n") { task ->
                "if atom_alive ${task.pid} ${shellQuote(ownerProof(task))}; " +
                    "then echo '${task.id} 1'; else echo '${task.id} 0'; fi"
            }
            val result = runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = probeScript,
                timeoutSeconds = 15,
            )
            if (result.exitCode == 0) {
                result.output.decodeToString().lineSequence().forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) aliveById[parts[0]] = parts[1] == "1"
                }
            } else {
                logger.warn(
                    "Agent terminal daemon action=list outcome=probe_failed identity=$identity exitCode=${result.exitCode}"
                )
            }
        }
        // Tasks that fail probing are conservatively treated as still running to avoid false death reports; only confirmed-dead tasks may be pruned.
        val statuses = tasks.map { task ->
            if (task.completedAt != null) return@map DetachedTaskStatus(task, false)
            var running = aliveById[task.id] ?: true
            if (!task.commandTask && task.identity == "user" && aliveById[task.id] == true && !adoptUserTask(task)) running = false
            if (task.commandTask && !running) {
                val exitCode = readCommandExit(task)
                DetachedTaskStatus(completeCommand(task.id, exitCode, if (exitCode == null) "interrupted" else null) ?: task, false)
            } else DetachedTaskStatus(task, running)
        }
        if (aliveById.isNotEmpty()) {
            pruneExitedRecords(statuses)
        }
        return statuses
    }

    fun readLogs(id: String, maxBytes: Int = MAX_LOG_READ_BYTES): DaemonLogsResult {
        val task = synchronized(RECORDS_LOCK) { loadTasksLocked() }.firstOrNull { it.id == id }
            ?: return DaemonLogsResult(ok = false, code = "TASK_NOT_FOUND", message = "Daemon task not found: $id")
        if (task.identity == "root" && !rootAvailable()) return DaemonLogsResult(ok = false, code = "ROOT_REQUIRED", message = "Root authorization is unavailable")
        val limit = maxBytes.coerceIn(1_024, MAX_LOG_READ_BYTES)
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "tail -c $limit ${shellQuote(hostDaemonPath(task, task.logPath))} 2>/dev/null",
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) {
            return DaemonLogsResult(ok = false, code = "LOGS_UNAVAILABLE", message = "Logs are unavailable: exit=${result.exitCode}")
        }
        return DaemonLogsResult(
            ok = true,
            text = result.output.decodeToString(),
            truncated = result.output.size >= limit,
        )
    }

    fun findTask(id: String): DetachedTask? = synchronized(RECORDS_LOCK) { loadTasksLocked().firstOrNull { it.id == id } }

    private class CommandProcess(val process: Process, val supervisor: ShellProcessSupervisor) {
        @Volatile var stopReason: String? = null
    }

    private fun startCommand(id: String, command: String, cwd: String, identity: String,
        environment: TerminalEnvironment, description: String, timeoutMs: Long): DaemonStartResult {
        val workspace = if (identity == "user") TerminalRuntime.userWorkspacePath else TerminalRuntime.ROOT_WORKSPACE_PATH
        val wireDir = when {
            environment.isLinux -> LINUX_DAEMON_DIR
            identity == "user" && daemonDir == DEFAULT_DAEMON_DIR -> "$workspace/daemon"
            else -> daemonDir
        }
        val logPath = "$wireDir/$id.log"
        val script = "mkdir -p ${shellQuote(wireDir)} && { cd ${shellQuote(cwd)} && " +
            commandPayload(command, "$wireDir/$id.exit") + "; } > ${shellQuote(logPath)} 2>&1"
        val process = oneShotSupervisor.startShellProcess(identity, script, true, environment,
            linuxRootfsPath = rootfsPath(environment), linuxSharedMounts = linuxSharedMountsProvider())
            ?: return DaemonStartResult.Failed("PROCESS_START_FAILED", "Unable to start the background command")
        val ownership = oneShotSupervisor.ownershipOf(process)
        if (ownership == null) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("PROCESS_START_FAILED", "Background command did not complete process ownership confirmation")
        }
        val task = DetachedTask(id, ownership.pid, ownership.token, command, cwd, identity, environment,
            logPath, System.currentTimeMillis(), LinuxEnvironmentPaths.backendOf(rootfsPath(environment)), workspace,
            commandTask = true, description = description, timeoutMs = timeoutMs, isolatedGroup = ownership.isolatedGroup)
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "Unable to save the background command")
        }
        val running = CommandProcess(process, oneShotSupervisor)
        COMMAND_PROCESSES[id] = running
        process.outputStream.close()
        val reader = thread(name = "atom-command-output", isDaemon = true) {
            process.inputStream.use { ByteArrayOutputCollector().readFrom(it, 8_192) }
        }
        thread(name = "atom-command-completion", isDaemon = true) {
            try {
                val exitCode = process.waitFor()
                reader.join(500)
                completeCommand(id, if (running.stopReason == null) exitCode else null, running.stopReason)
            } finally {
                oneShotSupervisor.retireExitedProcess(process)
                COMMAND_PROCESSES.remove(id, running)
            }
        }
        return DaemonStartResult.Started(task)
    }

    fun stopCommand(id: String, reason: String = "stopped"): Boolean {
        val task = findTask(id)?.takeIf { it.commandTask } ?: return false
        if (task.completedAt != null) return true
        if (task.identity == "root" && !rootAvailable()) return false
        COMMAND_PROCESSES[id]?.stopReason = reason
        if (!stopTaskProcess(task, removeFiles = false)) return false
        return completeCommand(id, null, reason) != null
    }

    fun markCompletionNotified(id: String): Boolean = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id && it.completedAt != null }
        if (index < 0) return@synchronized false
        tasks[index] = tasks[index].copy(completionNotified = true)
        saveTasksLocked(tasks)
    }

    private fun completeCommand(id: String, exitCode: Int?, reason: String?): DetachedTask? = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val existing = tasks[index]
        if (existing.completedAt != null) return@synchronized existing
        val completed = existing.copy(completedAt = System.currentTimeMillis(), exitCode = exitCode, endReason = reason)
        tasks[index] = completed
        if (saveTasksLocked(tasks)) completed else null
    }

    private fun readCommandExit(task: DetachedTask): Int? {
        val path = hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit")
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "head -c 32 ${shellQuote(path)} 2>/dev/null",
            timeoutSeconds = 5,
            maxOutputBytes = 32,
        )
        return if (result.exitCode == 0) result.output.decodeToString().trim().toIntOrNull() else null
    }

    private fun commandPayload(command: String, exitPath: String): String {
        val payload = "$command\natom_command_status=${'$'}?\nwait\nexit ${'$'}atom_command_status"
        // The command itself may call exit; after running in a subshell the host shell records the exit code atomically, so the result stays confirmable after an app restart.
        return "{ sh -c ${shellQuote(payload)}; atom_status=$?; " +
            "printf '%s\\n' \"${'$'}atom_status\" > ${shellQuote("$exitPath.tmp")} && " +
            "mv ${shellQuote("$exitPath.tmp")} ${shellQuote(exitPath)}; exit \"${'$'}atom_status\"; }"
    }

    /** Stops and deletes the record and log; when the process already exited, this is equivalent to cleaning the record. */
    fun stop(id: String): Boolean {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        val task = tasks.firstOrNull { it.id == id } ?: return false
        if (task.commandTask && task.completedAt == null) return stopCommand(id)
        if (task.identity == "root" && !rootAvailable()) return false
        if (!stopTaskProcess(task)) return false
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id == id })
        }
        logger.info(
            "Agent terminal daemon action=stop outcome=stopped taskId=$id " +
                "environment=${task.environment.wireName}"
        )
        if (task.identity == "user") releaseUserLease("daemon:$id")
        return true
    }

    private fun stopTaskProcess(task: DetachedTask, removeFiles: Boolean = true): Boolean {
        COMMAND_PROCESSES[task.id]?.let { live ->
            live.supervisor.terminateAndReap(live.process)
            if (!removeFiles) return true
        }
        val stopScript = buildString {
            appendLine(aliveCheckFunction())
            appendLine("if atom_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            // Under setsid, pgid==pid, so kill the whole tree by group at once; in degraded environments without setsid only the main process is killed.
            if (task.isolatedGroup != null) {
                appendLine("  kill -TERM ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("  if [ -d /proc ]; then kill -TERM -${task.pid} 2>/dev/null; else kill -TERM ${task.pid} 2>/dev/null; fi")
            appendLine("  sleep 1")
            appendLine("  if atom_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            if (task.isolatedGroup != null) {
                appendLine("    kill -9 ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("    if [ -d /proc ]; then kill -9 -${task.pid} 2>/dev/null; else kill -9 ${task.pid} 2>/dev/null; fi")
            appendLine("  fi")
            appendLine("  sleep 0.1")
            appendLine("  atom_alive ${task.pid} ${shellQuote(ownerProof(task))} && exit 81")
            appendLine("fi")
            if (removeFiles) append("rm -f ${shellQuote(hostDaemonPath(task, task.logPath))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".pid"))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit"))}")
        }
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = stopScript,
            timeoutSeconds = 10,
        )
        return result.exitCode == 0
    }

    /** Liveness check verifies the ownership token: when the PID is reused by the system, an unrelated process won't be mistaken for this task. Environments without /proc degrade to existence probing. */
    private fun aliveCheckFunction(): String =
        "atom_alive() { " +
            "if [ -d /proc ]; then " +
                "[ -d /proc/\$1 ] && " +
                "tr '\\000' '\\n' < /proc/\$1/environ 2>/dev/null | grep -Fqx \"\$2\"; " +
            "else kill -0 \"\$1\" 2>/dev/null; fi; " +
            "}"

    private fun ownerProof(task: DetachedTask): String = "$ATOM_PROCESS_OWNER_ENV=${task.token}"

    /** Ordinary daemon tasks keep the full host shell and tracer; inside the guest they can't detach from PRoot's lifecycle again. */
    private fun startUserDaemon(id: String, token: String, command: String, cwd: String, environment: TerminalEnvironment): DaemonStartResult {
        val workspace = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) TerminalRuntime.userWorkspacePath else File(daemonDir).parent!!
        val hostDir = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) File(workspace, "daemon") else File(daemonDir)
        if (!hostDir.mkdirs() && !hostDir.isDirectory) return DaemonStartResult.Failed("WORKSPACE_UNAVAILABLE", "Working directory is inaccessible")
        val pidFile = File(hostDir, "$id.pid")
        val logFile = File(hostDir, "$id.log")
        val commandBody = "exec sh -c ${shellQuote(command)}"
        val payload = if (environment.isLinux) {
            val rootfs = rootfsPath(environment)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return DaemonStartResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux environment is not installed yet")
            if (!ProotCommandBuilder.available()) return DaemonStartResult.Failed("PROOT_UNAVAILABLE", "The current device has no available rootless Linux runtime component")
            ProotCommandBuilder.payload(requireNotNull(rootfs), "cd ${shellQuote(cwd)} && $commandBody", linuxSharedMountsProvider(), workspace = workspace)
        } else "cd ${shellQuote(cwd)} && $commandBody"
        val lease = "daemon:$id"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val launchLock = Any()
        // Stop first records intent, then waits for the startup critical section; a stop issued before the record exists is not dropped.
        return synchronized(launchLock) {
            if (!acquireUserLease(lease) {
                    stopped.set(true)
                    synchronized(launchLock) { stop(id) }
                }) return@synchronized DaemonStartResult.Failed("BACKGROUND_START_NOT_ALLOWED", "Return to Atom and restart the background task")
            if (stopped.get()) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("TASK_CANCELLED", "Background task cancelled")
            }
            val script = "printf '%s\\n' \"${'$'}${'$'}\" > ${shellQuote(pidFile.absolutePath)}; $payload"
            val launcher = "if command -v setsid >/dev/null 2>&1; then exec setsid -w sh -c ${shellQuote(script)}; else exec sh -c ${shellQuote(script)}; fi"
            val process = try {
                ProcessBuilder("sh", "-c", launcher).apply {
                    environment()[ATOM_PROCESS_OWNER_ENV] = token
                    if (environment == TerminalEnvironment.ANDROID) environment()["HOME"] = workspace
                    redirectInput(File("/dev/null"))
                    redirectOutput(logFile)
                    redirectErrorStream(true)
                }.start()
            } catch (_: java.io.IOException) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "Unable to start the background task")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pidFile.length() == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            val pid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
            if (pid == null || pid <= 1) {
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "Background task did not complete startup")
            }
            val task = DetachedTask(id, pid, token, command, cwd, "user", environment,
                if (environment.isLinux) "$LINUX_DAEMON_DIR/$id.log" else logFile.absolutePath,
                System.currentTimeMillis(), if (environment.isLinux) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT, workspace)
            USER_WAITERS.add(id)
            if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
                USER_WAITERS.remove(id)
                stopTaskProcess(task)
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "Unable to save the background task; check internal storage")
            }
            thread(name = "atom-daemon-wait", isDaemon = true) {
                try { process.waitFor() } finally {
                    USER_WAITERS.remove(id)
                    releaseUserLease(lease)
                }
            }
            if (stopped.get()) {
                stop(id)
                DaemonStartResult.Failed("TASK_CANCELLED", "Background task cancelled")
            } else DaemonStartResult.Started(task)
        }
    }

    /** After the app process is rebuilt, only claim ordinary tasks whose ownership is confirmed, so a background tracer doesn't lose its notify and stop entry points. */
    private fun adoptUserTask(task: DetachedTask): Boolean {
        if (!USER_WAITERS.add(task.id)) return true
        val lease = "daemon:${task.id}"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!acquireUserLease(lease) { stopped.set(true); stop(task.id) }) {
            USER_WAITERS.remove(task.id)
            return !stop(task.id)
        }
        thread(name = "atom-daemon-adopt", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    val result = runOneShotShell(
                        processSupervisor = oneShotSupervisor, identity = "user",
                        command = aliveCheckFunction() + "\nif atom_alive ${task.pid} ${shellQuote(ownerProof(task))}; then echo alive; else echo exited; fi",
                        timeoutSeconds = 5,
                    )
                    if (result.exitCode == 0 && result.output.decodeToString().trim() == "exited") break
                    Thread.sleep(2_000)
                }
            } finally {
                USER_WAITERS.remove(task.id)
                releaseUserLease(lease)
            }
        }
        return !stopped.get()
    }

    /**
     * Bare launcher: bypasses [ShellProcessSupervisor]'s managed shell (its wait and exit cleanup are exactly the reclamation semantics a daemon task must escape),
     * doing only launch, output collection, and timeout protection. The Linux environment still reuses the same unshare + chroot wrapper.
     */
    private fun launchRaw(
        identity: String,
        environment: TerminalEnvironment,
        script: String,
        timeoutSeconds: Long,
    ): OneShotShellResult {
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> oneShotSupervisor.buildAndroidPayload(identity, script)
            else -> {
                val rootfs = rootfsPath(environment)
                    ?: return OneShotShellResult(-1, ByteArray(0), "Linux rootfs is not configured".toByteArray())
                oneShotSupervisor.buildLinuxPayload(rootfs, script, linuxSharedMountsProvider())
            }
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", payload)
            } else {
                ProcessBuilder("sh", "-c", payload)
            }
            builder.redirectErrorStream(true).start()
        }.getOrElse {
            return OneShotShellResult(-1, ByteArray(0), (it.message ?: "Unable to start process").toByteArray())
        }
        val output = ByteArrayOutputCollector()
        val reader = thread(name = "agent-daemon-launch-reader", isDaemon = true) {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return OneShotShellResult(-2, output.bytes(), "Command execution timed out".toByteArray())
        }
        runCatching { reader.join(500) }
        return OneShotShellResult(process.exitValue(), output.bytes(), ByteArray(0))
    }

    private fun wireDaemonDir(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DAEMON_DIR else daemonDir

    /**
     * A Linux task's /workspace is a bind view of the host working directory; the physical location of the log and pid files is still on the host.
     * All reads and writes use host paths, avoiding entering the chroot just to read a log.
     */
    internal fun hostDaemonPath(task: DetachedTask, wirePath: String): String =
        if (task.environment.isLinux && wirePath.startsWith(LINUX_DAEMON_DIR)) {
            (task.hostWorkspace?.let { "$it/daemon" } ?: DEFAULT_DAEMON_DIR) + wirePath.removePrefix(LINUX_DAEMON_DIR)
        } else {
            wirePath
        }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun pruneExitedRecords(statuses: List<DetachedTaskStatus>) {
        val excess = statuses.size - MAX_RETAINED_RECORDS
        if (excess <= 0) return
        val victims = statuses
            .filter { !it.running && (!it.task.commandTask || it.task.completionNotified) }
            .sortedBy { it.task.startedAt }
            .take(excess)
        if (victims.isEmpty()) return
        victims.groupBy { it.task.identity }.forEach { (identity, group) ->
            val removeScript = group.joinToString("\n") {
                "rm -f ${shellQuote(hostDaemonPath(it.task, it.task.logPath))} " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".pid")) + " " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".exit"))
            }
            runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = removeScript,
                timeoutSeconds = 10,
            )
        }
        val victimIds = victims.mapTo(mutableSetOf()) { it.task.id }
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id in victimIds })
        }
    }

    private fun loadTasksLocked(): MutableList<DetachedTask> {
        if (!recordsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapTo(mutableListOf()) { index ->
                array.getJSONObject(index).toDetachedTask()
            }
        }.getOrElse {
            logger.warn("Agent terminal daemon records corrupted, resetting")
            mutableListOf()
        }
    }

    private fun saveTasksLocked(tasks: List<DetachedTask>): Boolean =
        runCatching {
            recordsFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { array.put(it.toRecordJson()) }
            val tmp = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(recordsFile)) {
                recordsFile.writeText(array.toString())
            }
            true
        }.getOrElse {
            logger.warn("Agent terminal daemon records save failed")
            false
        }

}
