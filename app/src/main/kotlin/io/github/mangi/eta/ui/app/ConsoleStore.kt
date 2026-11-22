package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalKeyEncoder
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One console frame: row snapshots are immutable; the render layer reuses entries by id and decides redraw by version. */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.RenderLine> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** Environment tab selection; matches the current session's environment, or the target environment for a new session when none exists. */
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = probing; when false the entry falls back to the block terminal. */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** The current session's frame; each session's screen buffer is saved independently. */
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * App-level state owner for the console page: holds multiple PTY sessions and their independent screen buffers,
 * feeds byte streams into [TerminalScreenBuffer] on the IO thread, and publishes the current session's frames to the UI on a throttled cadence.
 * Sessions stay alive after leaving the page; ordinary sessions are held by the foreground execution service and reclaimed after the user stops them.
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        /** Cap on holding back frame publication during synchronous output; if the program omits the end marker, the screen doesn't stay frozen forever. */
        const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 200L
        const val SCROLLBACK_LINES = 1000
    }

    /** Key presses and query responses must be written to stdin in production order; single-concurrency dispatch guarantees FIFO without occupying the main thread. */
    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** Per-session screen buffer; writes only happen under lock on the IO thread, while the UI thread reads snapshots under lock. */
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()

    /** The UI's current default foreground/background colors for TUIs to query light/dark theme; updated to all sessions on system theme switch. */
    private var themeColors = 0xFF000000L to 0xFFFFFFFFL
    private val sessionSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // Both the PTY read thread and the main thread trigger frame publication.
    /**
     * During session startup (Root grant and chroot preparation can take seconds), grid size still changes with the soft keyboard;
     * only the latest size is recorded during this window and synced once ready, avoiding starting a new session on every size change. Read/written only on the main thread.
     */
    private var creatingSession = false

    @Volatile private var lastFlushMs = 0L
    @Volatile private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** Probes PTY prerequisites; the result is cached in state, and the entry falls back to the block terminal when unsupported. */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported() }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        _uiState.update { state ->
            state.copy(
                linuxEnvironment = selected,
                environment = if (state.environment.isLinux) selected else state.environment,
            )
        }
    }

    /**
     * Ensures a live current session exists; on grid-size re-entry it only syncs the size, not rebuilding the session.
     * [environment] is used only when creating a new session because none is reusable.
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) {
            resizeSession(activeId, cols, rows)
            return
        }
        if (creatingSession) return
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** Creates a session with the current environment and the most recent grid size. */
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0) return
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        _uiState.update { state ->
            state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        // The grid may have changed while the session was backgrounded (rotation, soft keyboard); sync to the current grid when switching back.
        if (lastCols > 0 && lastRows > 0) resizeSession(sessionId, lastCols, lastRows)
        flushFrame(sessionId)
    }

    /** Closes the given session; when closing the current one, switch to the nearest remaining session, or the empty state if none remain. */
    fun closeSession(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** Restarts the given session: terminate, then reopen with the original environment and grid size. */
    fun restartSession(sessionId: String) {
        if (creatingSession) return
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
        }
        synchronized(bufferLock) { buffers.remove(sessionId) }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
        }
        createSession(session.environment, size.first, size.second)
    }

    /** Switches the environment tab: only the target environment changes; switch to a live session of that environment if any, otherwise the grid callback creates one. */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
    }

    /** Reconnects with the same environment and grid size after the current session disconnects. */
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId ?: return
        restartSession(activeId)
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) { controller.write(sessionId, text) }
    }

    /** Function keys are encoded per the current session's cursor-key mode, so the UI needs no awareness after a program toggles DECCKM. */
    fun sendKey(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val applicationCursorKeys = activeBuffer { it.applicationCursorKeys } ?: return
        write(TerminalKeyEncoder.key(key, modifiers, applicationCursorKeys))
    }

    fun sendText(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        write(TerminalKeyEncoder.text(text, modifiers))
    }

    fun paste(text: String) {
        val bracketed = activeBuffer { it.bracketedPaste } ?: return
        write(TerminalKeyEncoder.paste(text, bracketed))
    }

    fun setThemeColors(foreground: Long, background: Long) {
        synchronized(bufferLock) {
            themeColors = foreground to background
            buffers.values.forEach { it.applyTheme() }
        }
    }

    private fun TerminalScreenBuffer.applyTheme() {
        defaultForeground = themeColors.first
        defaultBackground = themeColors.second
    }

    private fun <T> activeBuffer(read: (TerminalScreenBuffer) -> T): T? {
        val sessionId = _uiState.value.activeSessionId ?: return null
        return synchronized(bufferLock) { buffers[sessionId]?.let(read) }
    }

    /** The screen buffer and PTY resize in sync; the kernel then tells the program to redraw, while the buffer first carries the old picture at the new size. */
    private fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        if (sessionSizes[sessionId] == (cols to rows)) return
        sessionSizes[sessionId] = cols to rows
        synchronized(bufferLock) { buffers[sessionId]?.resize(cols, rows) }
        scope.launch(inputDispatcher) { controller.resize(sessionId, cols, rows) }
        flushFrame(sessionId)
    }

    fun close() {
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || creatingSession) return
        lastCols = cols
        lastRows = rows
        creatingSession = true
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            try {
                startSession(environment, cols, rows)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { creatingSession = false }
            }
            withContext(Dispatchers.Main) {
                // The grid may have changed during startup; sync once to the latest size.
                _uiState.value.activeSessionId?.let { resizeSession(it, lastCols, lastRows) }
            }
        }
    }

    private fun startSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                _uiState.update { it.copy(failMessage = appContext.getString(R.string.capability_background_failed)) }
                return
            }
        } else null
        val result = try {
            controller.open(
                environment = environment,
                identity = identity,
                cols = cols,
                rows = rows,
                onOutput = ::onOutput,
                onExit = { sessionId ->
                    lease?.release()
                    onExit(sessionId)
                },
            )
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        when (result) {
            is ConsoleSessionController.OpenResult.Ready -> {
                if (lease != null) {
                    sessionLeases[result.sessionId] = lease
                    if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                        controller.closeSession(result.sessionId)
                        sessionLeases.remove(result.sessionId)?.release()
                        _uiState.update { it.copy(failMessage = appContext.getString(R.string.terminal_session_closed)) }
                        return
                    }
                }
                synchronized(bufferLock) {
                    buffers[result.sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES).apply { applyTheme() }
                }
                sessionSizes[result.sessionId] = cols to rows
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment),
                        activeSessionId = result.sessionId,
                        environment = environment,
                        connected = true,
                        exited = false,
                        frame = ConsoleFrame(),
                    )
                }
            }
            is ConsoleSessionController.OpenResult.Failed -> {
                lease?.release()
                _uiState.update { it.copy(connected = false, failMessage = result.message) }
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        var responses: String? = null
        var synchronizing = false
        synchronized(bufferLock) {
            val buffer = buffers[sessionId] ?: return
            buffer.feed(chunk)
            responses = buffer.drainResponses()
            synchronizing = buffer.synchronizedOutput
        }
        // Query responses bypass the UI; background sessions must reply promptly too, or the program blocks waiting.
        responses?.let { reply -> scope.launch(inputDispatcher) { controller.write(sessionId, reply) } }
        if (sessionId != _uiState.value.activeSessionId) return
        val now = System.currentTimeMillis()
        when {
            synchronizing -> scheduleFlush(sessionId, SYNCHRONIZED_OUTPUT_TIMEOUT_MS)
            now - lastFlushMs >= FLUSH_INTERVAL_MS -> flushFrame(sessionId)
            else -> scheduleFlush(sessionId, FLUSH_INTERVAL_MS)
        }
    }

    private fun scheduleFlush(sessionId: String, delayMs: Long) {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(delayMs)
            flushScheduled = false
            flushFrame(sessionId)
        }
    }

    private fun flushFrame(sessionId: String) {
        val frame = synchronized(bufferLock) {
            val current = buffers[sessionId] ?: return
            ConsoleFrame(
                lines = current.renderLines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        if (sessionId != _uiState.value.activeSessionId) return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
