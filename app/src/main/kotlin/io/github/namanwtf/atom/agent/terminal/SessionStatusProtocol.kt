package io.github.namanwtf.atom.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Status-line protocol for long-lived shell sessions: after each command, a printf with a random marker is appended to the same stdin,
 * printing the exit code and the post-execution PWD. The host side uses it to split output and track cwd; the marker contains a random UUID,
 * so normal command output won't collide with it.
 */
internal object SessionStatusProtocol {

    fun newMarker(): String = "__ETA_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    fun statusCommand(marker: String): String =
        "printf '\\n$marker:%s:%s\\n' \"\$?\" \"\$PWD\""

    /**
     * Single-logical-line protocol: the command runs via eval, the status printf sits on the same line as the command, and the shell prints it after the command exits.
     * The status line doesn't enter stdin as a separate line - interactive commands (read, REPL, etc.) reading stdin won't consume the marker,
     * and user input written while the session runs is left intact for the foreground process.
     */
    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; atom_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$atom_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    /** Parses the status line; returns null when cwd is empty (empty segment), letting the caller fall back to the session's current cwd. */
    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

/** Bounded output collector: the reader thread keeps draining the pipe, dropping further content past the limit and marking truncation. */
internal class ByteArrayOutputCollector {
    private val output = ByteArrayOutputStream()
    private var totalBytesRead = 0L
    private var truncated = false

    fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(this) {
                    totalBytesRead += read.toLong()
                    val allowed = (maxBytes - output.size()).coerceAtLeast(0)
                    if (allowed > 0) {
                        output.write(buffer, 0, read.coerceAtMost(allowed))
                    }
                    if (read > allowed) {
                        truncated = true
                    }
                }
            }
        }.onFailure { throwable ->
            if (throwable !is IOException) throw throwable
        }
    }

    fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

    fun text(): String = bytes().decodeToString()

    fun totalBytesRead(): Long = synchronized(this) { totalBytesRead }

    fun isTruncated(): Boolean = synchronized(this) { truncated }

    fun clear() {
        synchronized(this) {
            output.reset()
            totalBytesRead = 0
            truncated = false
        }
    }
}
