package io.github.namanwtf.atom.agent.terminal

import java.io.File
import java.security.MessageDigest

/** File operations run inside the selected namespace; Linux links must not be resolved by the Android host. */
internal class ShellFileToolBackend(
    override val environment: String,
    override val identity: String,
    private val cwd: String,
    private val home: String,
    private val ensureActive: () -> Unit = {},
    private val execute: (command: String, stdin: ByteArray?, maxOutputBytes: Int) -> OneShotShellResult,
) : FileToolBackend {
    override fun checkActive() = ensureActive()

    override fun resolve(path: String): String {
        checkActive()
        if ('\u0000' in path || path.length > 4096) fail("INVALID_PATH", "File path is invalid or too long")
        val raw = path.ifEmpty { cwd }
        val absolute = when {
            raw == "~" -> home
            raw.startsWith("~/") -> "$home/${raw.removePrefix("~/")}"
            raw.startsWith('/') -> raw
            else -> "$cwd/$raw"
        }
        // Let the target namespace resolve links first; folding link/.. early would change the directory the kernel actually accesses.
        val result = run("readlink -f -- ${shellQuote(absolute)}", maxOutputBytes = 8192, allowFailure = true)
        return if (result.exitCode == 0) {
            decode(result.output).removeSuffix("\n").takeIf { it.startsWith('/') }
                ?: fail("FILE_METADATA_INVALID", "Canonical path returned by the system is invalid")
        } else absolute
    }

    override fun stat(path: String): FileToolStat {
        val result = run(statScript(shellQuote(path)), maxOutputBytes = MAX_STAT_BYTES)
        return parseStat(path, decode(result.output))
    }

    private fun statScript(quotedPath: String): String {
        val format = shellQuote("%s\n%d:%i:%s:%Y:%Z:%y:%z")
        return """
            if [ -L $quotedPath ]; then printf 'symlink\n'
            elif [ -f $quotedPath ]; then printf 'file\n'
            elif [ -d $quotedPath ]; then printf 'directory\n'
            elif [ -e $quotedPath ]; then printf 'other\n'
            else exit 41; fi
            LC_ALL=C stat -c $format -- $quotedPath
        """.trimIndent()
    }

    private fun parseStat(path: String, output: String): FileToolStat {
        val fields = output.split('\n', limit = 3)
        val kind = FileToolKind.entries.firstOrNull { it.wireName == fields.firstOrNull() }
            ?: fail("FILE_METADATA_INVALID", "Cannot recognize the file type returned by the system")
        val size = fields.getOrNull(1)?.toLongOrNull()
            ?: fail("FILE_METADATA_INVALID", "Cannot recognize the file size returned by the system")
        val metadata = fields.getOrNull(2)?.trimEnd('\n').orEmpty()
        val versionFields = metadata.split(':', limit = 6)
        if (size < 0 || versionFields.size != 6 ||
            versionFields.take(2).any { !it.matches(Regex("[0-9]+")) } ||
            versionFields[2].toLongOrNull() != size ||
            versionFields[3].toLongOrNull() == null || versionFields[4].toLongOrNull() == null ||
            versionFields[5].isBlank() || '?' in metadata || '%' in metadata || '\n' in metadata
        ) fail("FILE_METADATA_INVALID", "File revision information returned by the system is invalid")
        return FileToolStat(path, File(path).name.ifEmpty { "/" }, kind, size, digest(metadata.toByteArray()))
    }

    override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
        if (offset < 0 || limit !in 1..MAX_READ_BYTES) fail("INVALID_ARGUMENT", "Read range exceeds the limit")
        val blockOffset = offset / BLOCK_BYTES
        val prefix = (offset % BLOCK_BYTES).toInt()
        val blocks = (prefix + limit + BLOCK_BYTES - 1) / BLOCK_BYTES
        val quoted = shellQuote(path)
        val result = run(
            "[ -f $quoted ] || exit 42; [ -r $quoted ] || exit 43; " +
                "dd if=$quoted bs=$BLOCK_BYTES skip=$blockOffset count=$blocks",
            maxOutputBytes = blocks * BLOCK_BYTES,
        )
        val from = prefix.coerceAtMost(result.output.size)
        return result.output.copyOfRange(from, (from + limit).coerceAtMost(result.output.size))
    }

    override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
        if (offset < 0 || limit !in 1..200) fail("INVALID_ARGUMENT", "Directory pagination range is invalid")
        val before = stat(path)
        if (before.kind != FileToolKind.DIRECTORY) fail("NOT_DIRECTORY", "Target is not a directory")
        if (expectedRevision != null && expectedRevision != before.revision) {
            fail("STALE_CURSOR", "Directory has changed; re-read from the first page")
        }
        val quoted = shellQuote(path)
        val result = run(
            "[ -r $quoted ] && [ -x $quoted ] || exit 43; " +
                "find $quoted -mindepth 1 -maxdepth 1 -print0",
            maxOutputBytes = MAX_DIRECTORY_BYTES,
        )
        if (result.output.isNotEmpty() && result.output.last() != 0.toByte()) {
            fail("DIRECTORY_TOO_LARGE", "Directory enumeration exceeded the budget; narrow the directory scope")
        }
        val names = decode(result.output).split('\u0000').filter(String::isNotEmpty)
        if (names.size > MAX_DIRECTORY_ENTRIES) fail("DIRECTORY_TOO_LARGE", "Too many directory entries; narrow the scope")
        val sorted = names.sorted()
        val selected = statPage(sorted.drop(offset).take(limit))
        if (stat(path).revision != before.revision) fail("STALE_CURSOR", "Directory changed during read; read again")
        val next = (offset.toLong() + selected.size).coerceAtMost(sorted.size.toLong()).toInt()
        return FileToolDirectoryPage(selected, next, next < sorted.size, before.revision)
    }

    /** Metadata for one page of entries shares a single namespace; paths go through stdin to avoid overflowing exec arguments. */
    private fun statPage(paths: List<String>): List<FileToolStat> {
        if (paths.isEmpty()) return emptyList()
        val script = buildString {
            append("atom_file_stat() {\n")
            append(statScript("\"${'$'}1\""))
            append("\n}\n")
            paths.forEach { path ->
                append("atom_file_stat ").append(shellQuote(path)).append(" || exit ${'$'}?\n")
                append("printf '\\000'\n")
            }
        }
        val result = run(
            "sh -s",
            stdin = script.toByteArray(Charsets.UTF_8),
            maxOutputBytes = minOf(paths.size * MAX_STAT_BYTES, MAX_DIRECTORY_BYTES),
        )
        val records = decode(result.output).split('\u0000')
        if (records.size != paths.size + 1 || records.last().isNotEmpty()) {
            fail("FILE_METADATA_INVALID", "Directory entry metadata is incomplete; read again")
        }
        return paths.mapIndexed { index, path -> parseStat(path, records[index]) }
    }

    override fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String?,
        expectedSha256: String?,
    ): FileToolWriteResult {
        if (bytes.size > MAX_READ_BYTES) fail("FILE_TOO_LARGE", "Single write content exceeds the limit")
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
            fail("INVALID_ARGUMENT", "Content revision format is invalid")
        }
        val quoted = shellQuote(path)
        val parent = shellQuote(File(path).parent ?: "/")
        expectedRevision?.let {
            if (stat(path).revision != it) fail("FILE_CHANGED", "File has changed; re-read before editing")
        }
        val hashCheck = expectedSha256?.let {
            "atom_hash=${'$'}(sha256sum < $quoted) || exit 45; " +
                "[ \"${'$'}{atom_hash%% *}\" = ${shellQuote(it)} ] || exit 44; "
        }.orEmpty()
        // Preserve the existing inode's owner, mode, and SELinux label; honestly report non-atomic writes.
        run(
            "mkdir -p -- $parent || exit 43; " +
                "if [ -e $quoted ] && [ ! -f $quoted ]; then exit 42; fi; " +
                hashCheck + "cat ${if (append) ">>" else ">"} $quoted",
            stdin = bytes,
            maxOutputBytes = 8192,
        )
        return FileToolWriteResult(stat(path), atomic = false)
    }

    private fun run(
        command: String,
        stdin: ByteArray? = null,
        maxOutputBytes: Int,
        allowFailure: Boolean = false,
    ): OneShotShellResult {
        checkActive()
        val result = execute(command, stdin, maxOutputBytes)
        checkActive()
        when (result.exitCode) {
            SHELL_STDIN_FAILED_EXIT_CODE -> fail("FILE_INPUT_FAILED", "File write content was not fully transferred; re-read and confirm the file state")
            -3 -> fail("CANCELLED", "File operation cancelled")
            -2 -> fail("FILE_TIMEOUT", "File operation timed out")
            -1 -> fail("FILE_PROCESS_FAILED", "Unable to start file operation")
            41 -> fail("FILE_NOT_FOUND", "File does not exist or is inaccessible with the current identity")
            42 -> fail("NOT_REGULAR_FILE", "Target is not a regular file")
            43 -> fail("FILE_ACCESS_DENIED", "Current identity has no file access permission")
            44 -> fail("FILE_CHANGED", "File content has changed; re-read before editing")
            45 -> fail("FILE_HASH_UNAVAILABLE", "Current environment cannot verify the file content revision")
            0 -> Unit
            else -> if (!allowFailure || result.exitCode != 1) fail("FILE_COMMAND_FAILED", "File operation failed(exit=${result.exitCode})")
        }
        if (result.outputTruncated) fail("FILE_OUTPUT_LIMIT", "File operation result exceeded the budget; narrow the scope")
        return result
    }

    private fun decode(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        fail("UNSUPPORTED_ENCODING", "File name or metadata is not valid UTF-8")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(code: String, message: String): Nothing = throw FileToolException(code, message)

    private companion object {
        const val BLOCK_BYTES = 4096
        const val MAX_READ_BYTES = 512 * 1024
        const val MAX_DIRECTORY_BYTES = 1024 * 1024
        const val MAX_DIRECTORY_ENTRIES = 20_000
        const val MAX_STAT_BYTES = 8192
    }
}
