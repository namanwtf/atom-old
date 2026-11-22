package io.github.namanwtf.atom.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.terminal.commandStatus
import io.github.namanwtf.atom.agent.terminal.DetachedTaskStatus
import io.github.namanwtf.atom.agent.terminal.DetachedTaskSupervisor
import io.github.namanwtf.atom.agent.terminal.LinuxEnvironmentPaths
import io.github.namanwtf.atom.agent.terminal.SharedFolderMounts
import io.github.namanwtf.atom.agent.terminal.TerminalEnvironment
import io.github.namanwtf.atom.agent.terminal.TerminalRuntime
import io.github.namanwtf.atom.agent.terminal.UserTerminalController
import io.github.namanwtf.atom.agent.terminal.isLinux
import io.github.namanwtf.atom.agent.terminal.terminalEnvironment
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.data.repository.LinuxEnvironmentSettingsRepository
import io.github.namanwtf.atom.ui.components.ansiPlainText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
internal data class TerminalBlockUi(
    val id: Long,
    val isSystem: Boolean = false,
    val command: String = "",
    val cwdAtStart: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = false,
    val truncated: Boolean = false,
)

@Immutable
internal data class DaemonTaskUi(
    val id: String,
    val command: String,
    val environment: TerminalEnvironment,
    val identity: String,
    val running: Boolean,
    val startedAt: Long,
    val description: String = "",
    val status: String = "",
)

@Immutable
internal data class TerminalSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val cwd: String,
    val running: Boolean = false,
    val alive: Boolean = true,
)

@Immutable
internal data class UserTerminalUiState(
    val sessions: List<TerminalSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** The current session's command blocks; each session's block list is saved independently. */
    val blocks: List<TerminalBlockUi> = emptyList(),
    /** Environment tab selection; matches the current session's environment, or the target environment for a new session when none exists. */
    val environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    val cwd: String = "",
    val running: Boolean = false,
    val linuxReady: Boolean = false,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val daemonTasks: List<DaemonTaskUi> = emptyList(),
    val failMessage: String? = null,
)

/**
 * App-level state owner for the user manual terminal: maps [UserTerminalController]'s multi-session thread model into Compose state.
 * Ordinary sessions are held by the foreground execution service, surviving leaving the terminal page or screen rotation; they end only when the App process dies.
 */
internal class UserTerminalStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val MAX_BLOCKS = 200
        const val MAX_BLOCK_OUTPUT_CHARS = 64_000
        const val FLUSH_INTERVAL_MS = 120L
    }

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = UserTerminalController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        onSessionExit = { sessionId ->
            sessionLeases.remove(sessionId)?.release()
            scope.launch { updateSessionEntry(sessionId) { it.copy(alive = false) } }
        },
    )
    private val daemonSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    private val _uiState = MutableStateFlow(
        UserTerminalUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
            linuxReady = isReady(initialLinuxEnvironment),
        )
    )
    val uiState: StateFlow<UserTerminalUiState> = _uiState.asStateFlow()

    private var blockId = 0L

    /** Per-session block list and streaming output buffer; only the current session projects into uiState.blocks. */
    private val sessionBlocks = mutableMapOf<String, List<TerminalBlockUi>>()
    private val sessionBuffers = mutableMapOf<String, SessionOutput>()

    fun refreshLinuxReady() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        val ready = isReady(selected)
        _uiState.update {
            it.copy(
                linuxEnvironment = selected,
                environment = if (it.environment.isLinux) selected else it.environment,
                linuxReady = ready,
            )
        }
    }

    fun refreshDaemonTasks() {
        scope.launch {
            val statuses = withContext(Dispatchers.IO) { daemonSupervisor.list() }
            _uiState.update { state ->
                state.copy(daemonTasks = statuses.map(::toDaemonTaskUi))
            }
        }
    }

    fun stopDaemonTask(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { daemonSupervisor.stop(id) }
            refreshDaemonTasks()
        }
    }

    /** Reads the daemon task log tail; ANSI sequences are stripped to plain text, returning a displayable reason on failure. */
    suspend fun daemonLogs(id: String): String = withContext(Dispatchers.IO) {
        val result = daemonSupervisor.readLogs(id)
        when {
            result.ok && result.text.isBlank() -> appContext.getString(R.string.terminal_daemon_logs_empty)
            result.ok -> ansiPlainText(result.text.trimEnd())
            else -> result.message.ifBlank { appContext.getString(R.string.terminal_daemon_logs_empty) }
        }
    }

    /** Creates a session with the current environment and switches to it. */
    fun newSession() {
        val environment = _uiState.value.environment
        scope.launch {
            openSessionInternal(environment)
        }
    }

    /** Switches the current session; the block list, cwd, and running state re-bind accordingly. */
    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                blocks = sessionBlocks[sessionId].orEmpty(),
                environment = session.environment,
                cwd = session.cwd,
                running = session.running,
            )
        }
    }

    /** Closes the given session; when closing the current one, switch to the nearest remaining session, or the empty state if none remain. */
    fun closeSession(sessionId: String) {
        scope.launch {
            withContext(Dispatchers.IO) { controller.stopSession(sessionId) }
            sessionLeases.remove(sessionId)?.release()
            sessionBlocks.remove(sessionId)
            sessionBuffers.remove(sessionId)
            _uiState.update { state ->
                val sessions = state.sessions.filterNot { it.id == sessionId }
                if (state.activeSessionId != sessionId) {
                    state.copy(sessions = sessions)
                } else {
                    val next = sessions.lastOrNull()
                    state.copy(
                        sessions = sessions,
                        activeSessionId = next?.id,
                        blocks = next?.let { sessionBlocks[it.id].orEmpty() }.orEmpty(),
                        cwd = next?.cwd ?: "",
                        running = next?.running == true,
                        environment = next?.environment ?: state.environment,
                    )
                }
            }
        }
    }

    /** Restarts the given session: terminate, then reopen with the original environment and cwd, keeping command blocks and appending a note. */
    fun restartSession(sessionId: String) {
        scope.launch {
            val entry = _uiState.value.sessions.find { it.id == sessionId } ?: return@launch
            withContext(Dispatchers.IO) { controller.stopSession(sessionId) }
            sessionLeases.remove(sessionId)?.release()
            val opened = withContext(Dispatchers.IO) {
                openLeasedSession(entry.environment, entry.cwd)
            }
            when (opened) {
                is UserTerminalController.OpenResult.Failed -> {
                    updateSessionEntry(sessionId) { it.copy(alive = false, running = false) }
                    appendSystemBlock(sessionId, opened.message)
                }
                is UserTerminalController.OpenResult.Ready -> {
                    replaceSession(sessionId, opened)
                    appendSystemBlock(
                        opened.sessionId,
                        appContext.getString(R.string.terminal_session_restarted),
                    )
                }
            }
        }
    }

    fun send(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return
        if (command.length > 16_000) return
        val state = _uiState.value
        if (state.running) return
        val environment = state.environment
        scope.launch {
            val sessionId = ensureSession(state, environment) ?: return@launch
            val cwdAtStart = _uiState.value.sessions.find { it.id == sessionId }?.cwd.orEmpty()
            val id = ++blockId
            appendBlock(
                sessionId,
                TerminalBlockUi(
                    id = id,
                    command = command,
                    cwdAtStart = cwdAtStart,
                    running = true,
                ),
            )
            updateSessionEntry(sessionId) { it.copy(running = true) }
            sessionBuffers[sessionId] = SessionOutput()
            val result = withContext(Dispatchers.IO) {
                controller.exec(sessionId, command) { text, _ -> onOutputDelta(sessionId, id, text) }
            }
            flushOutput(sessionId, id)
            finalizeBlock(sessionId, id, result)
        }
    }

    /** While running, sends one line of input to the current session's stdin and echoes it dimmed into the current block (pipes have no tty echo). */
    fun sendInput(rawInput: String) {
        val text = rawInput.trim()
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        if (!_uiState.value.running) return
        scope.launch(Dispatchers.IO) { controller.writeInput(sessionId, text + "\n") }
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.running) {
                    block.copy(output = block.output + "\u001B[2m" + text + "\u001B[0m\n")
                } else {
                    block
                }
            }
        }
    }

    /** Terminates the current session and its running command. */
    fun stop() {
        val sessionId = _uiState.value.activeSessionId ?: return
        if (!_uiState.value.running) return
        scope.launch(Dispatchers.IO) { controller.stopSession(sessionId) }
    }

    /** Switches the environment tab: only the target environment changes; switch to a live session of that environment if any, otherwise enter the empty state awaiting creation. */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { it.environment == environment && it.alive }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    linuxReady = isReady(environment),
                    activeSessionId = null,
                    blocks = emptyList(),
                    cwd = "",
                    running = false,
                )
            }
        }
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun openLeasedSession(environment: TerminalEnvironment, cwd: String? = null): UserTerminalController.OpenResult {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } }
                ?: return UserTerminalController.OpenResult.Failed("BACKGROUND_START_NOT_ALLOWED", appContext.getString(R.string.capability_background_failed))
        } else null
        val result = try {
            controller.openSession(environment, cwd = cwd, identity = identity)
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        if (result is UserTerminalController.OpenResult.Ready && lease != null) {
            sessionLeases[result.sessionId] = lease
            if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                controller.stopSession(result.sessionId)
                sessionLeases.remove(result.sessionId)?.release()
                return UserTerminalController.OpenResult.Failed("TERMINAL_CLOSED", appContext.getString(R.string.terminal_session_closed))
            }
        } else if (result is UserTerminalController.OpenResult.Failed) {
            lease?.release()
        }
        return result
    }

    /**
     * Ensures a session is available for execution: reuse the current live session; reopen (with a note) the current exited session;
     * with no current session, prefer reusing a live session of the same environment, otherwise create one. Returns a usable sessionId.
     */
    private suspend fun ensureSession(
        state: UserTerminalUiState,
        environment: TerminalEnvironment,
    ): String? {
        val activeId = state.activeSessionId
        val active = state.sessions.find { it.id == activeId }
        if (active != null && active.environment == environment) {
            val alive = withContext(Dispatchers.IO) { controller.sessionAlive(active.id) }
            if (alive) return active.id
            // Exited: reopen with the original environment and cwd, keeping blocks.
            val reopened = withContext(Dispatchers.IO) {
                openLeasedSession(active.environment, active.cwd)
            }
            return when (reopened) {
                is UserTerminalController.OpenResult.Failed -> {
                    updateSessionEntry(active.id) { it.copy(alive = false) }
                    appendSystemBlock(active.id, reopened.message)
                    null
                }
                is UserTerminalController.OpenResult.Ready -> {
                    replaceSession(active.id, reopened)
                    appendSystemBlock(
                        reopened.sessionId,
                        appContext.getString(R.string.terminal_session_restarted),
                    )
                    reopened.sessionId
                }
            }
        }
        val reusable = state.sessions.lastOrNull { it.environment == environment && it.alive }
        if (reusable != null) {
            switchSession(reusable.id)
            return reusable.id
        }
        return openSessionInternal(environment)
    }

    /** Creates a session and sets it current; on failure, appends the reason to the current block list. Returns the new sessionId. */
    private suspend fun openSessionInternal(environment: TerminalEnvironment): String? {
        _uiState.update { it.copy(failMessage = null) }
        val opened = withContext(Dispatchers.IO) { openLeasedSession(environment) }
        return when (opened) {
            is UserTerminalController.OpenResult.Failed -> {
                val activeId = _uiState.value.activeSessionId
                if (activeId != null) appendSystemBlock(activeId, opened.message)
                else _uiState.update { it.copy(failMessage = opened.message) }
                null
            }
            is UserTerminalController.OpenResult.Ready -> {
                val session = TerminalSessionUi(
                    id = opened.sessionId,
                    environment = opened.environment,
                    cwd = opened.cwd,
                )
                sessionBlocks[opened.sessionId] = emptyList()
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions + session,
                        activeSessionId = opened.sessionId,
                        blocks = emptyList(),
                        environment = opened.environment,
                        cwd = opened.cwd,
                        running = false,
                    )
                }
                opened.sessionId
            }
        }
    }

    /** Restart/reopen scenario: the new sessionId takes over the old session's position, and command blocks migrate with the id. */
    private fun replaceSession(oldId: String, opened: UserTerminalController.OpenResult.Ready) {
        sessionLeases.remove(oldId)?.release()
        val inheritedBlocks = sessionBlocks.remove(oldId).orEmpty()
        sessionBlocks[opened.sessionId] = inheritedBlocks
        sessionBuffers.remove(oldId)
        _uiState.update { state ->
            val sessions = state.sessions.map { session ->
                if (session.id == oldId) {
                    TerminalSessionUi(
                        id = opened.sessionId,
                        environment = opened.environment,
                        cwd = opened.cwd,
                    )
                } else {
                    session
                }
            }
            if (state.activeSessionId == oldId) {
                state.copy(
                    sessions = sessions,
                    activeSessionId = opened.sessionId,
                    blocks = inheritedBlocks,
                    cwd = opened.cwd,
                    running = false,
                )
            } else {
                state.copy(sessions = sessions)
            }
        }
    }

    /** Output keeps raw ANSI sequences that the render layer parses into colors/styles; log/copy scenarios have the render layer strip them. */
    private fun onOutputDelta(sessionId: String, blockId: Long, text: String) {
        if (text.isEmpty()) return
        val buffer = sessionBuffers[sessionId] ?: return
        synchronized(buffer.lock) { buffer.text.append(text) }
        val now = System.currentTimeMillis()
        if (now - buffer.lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushOutput(sessionId, blockId)
        }
    }

    private fun flushOutput(sessionId: String, blockId: Long) {
        val buffer = sessionBuffers[sessionId] ?: return
        val chunk = synchronized(buffer.lock) {
            if (buffer.text.isEmpty()) null else buffer.text.toString().also { buffer.text.setLength(0) }
        } ?: return
        buffer.lastFlushMs = System.currentTimeMillis()
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.id != blockId) {
                    block
                } else {
                    val combined = block.output + chunk
                    if (combined.length > MAX_BLOCK_OUTPUT_CHARS) {
                        block.copy(
                            output = combined.take(MAX_BLOCK_OUTPUT_CHARS),
                            truncated = true,
                        )
                    } else {
                        block.copy(output = combined)
                    }
                }
            }
        }
    }

    private fun finalizeBlock(sessionId: String, blockId: Long, result: UserTerminalController.ExecResult) {
        updateSessionBlocks(sessionId) { blocks ->
            blocks.map { block ->
                if (block.id == blockId) {
                    block.copy(running = false, exitCode = result.exitCode)
                } else {
                    block
                }
            }
        }
        updateSessionEntry(sessionId) {
            it.copy(running = false, cwd = result.cwd, alive = !result.sessionClosed)
        }
        if (result.sessionClosed) {
            sessionLeases.remove(sessionId)?.release()
            appendSystemBlock(
                sessionId,
                appContext.getString(
                    if (result.interrupted) R.string.terminal_interrupted else R.string.terminal_session_closed
                ),
            )
        }
    }

    private fun appendBlock(sessionId: String, block: TerminalBlockUi) {
        updateSessionBlocks(sessionId) { blocks -> (blocks + block).takeLast(MAX_BLOCKS) }
    }

    private fun appendSystemBlock(sessionId: String, message: String) {
        appendBlock(
            sessionId,
            TerminalBlockUi(
                id = ++blockId,
                isSystem = true,
                output = message,
            ),
        )
    }

    private fun updateSessionBlocks(sessionId: String, transform: (List<TerminalBlockUi>) -> List<TerminalBlockUi>) {
        val updated = transform(sessionBlocks[sessionId].orEmpty())
        sessionBlocks[sessionId] = updated
        _uiState.update { state ->
            if (state.activeSessionId == sessionId) state.copy(blocks = updated) else state
        }
    }

    private fun updateSessionEntry(sessionId: String, transform: (TerminalSessionUi) -> TerminalSessionUi) {
        _uiState.update { state ->
            val sessions = state.sessions.map { if (it.id == sessionId) transform(it) else it }
            if (state.activeSessionId == sessionId) {
                val active = sessions.firstOrNull { it.id == sessionId }
                state.copy(
                    sessions = sessions,
                    running = active?.running == true,
                    cwd = active?.cwd ?: state.cwd,
                )
            } else {
                state.copy(sessions = sessions)
            }
        }
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false

    private fun toDaemonTaskUi(status: DetachedTaskStatus) = DaemonTaskUi(
        id = status.task.id,
        command = status.task.command,
        environment = status.task.environment,
        identity = status.task.identity,
        running = status.running,
        startedAt = status.task.startedAt,
        description = status.task.description,
        status = if (status.task.commandTask) status.task.commandStatus else "",
    )

    private class SessionOutput {
        val lock = Any()
        val text = StringBuilder()
        var lastFlushMs = 0L
    }
}

internal val TerminalEnvironment.displayName: String
    get() = when (this) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
