package io.github.mangi.eta.agent.terminal

/** revision is an opaque backend-generated value compared only within the same backend; directory content changes must invalidate it. */
internal data class FileToolStat(
    val path: String,
    val name: String,
    val kind: FileToolKind,
    val sizeBytes: Long,
    val revision: String,
)

internal enum class FileToolKind(val wireName: String) {
    FILE("file"), DIRECTORY("directory"), SYMLINK("symlink"), OTHER("other"),
}

internal data class FileToolDirectoryPage(
    val entries: List<FileToolStat>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val revision: String,
)

internal data class FileToolWriteResult(
    val stat: FileToolStat,
    val atomic: Boolean,
)

internal class FileToolException(val code: String, message: String) : Exception(message)

/** All paths in parameters belong to the backend's own namespace; implementations must re-check access boundaries on every operation. */
internal interface FileToolBackend {
    val environment: String
    val identity: String
    fun checkActive() = Unit
    fun resolve(path: String): String
    fun stat(path: String): FileToolStat
    fun readBytes(path: String, offset: Long, limit: Int): ByteArray

    /** offset is the raw directory enumeration position and must not be filtered before paging. Order is maintained by the backend, and changes are detected via revision. */
    fun list(path: String, offset: Int, limit: Int, expectedRevision: String? = null): FileToolDirectoryPage

    /**
     * expectedSha256 validates the full old content read for an edit; on mismatch it must return FILE_CHANGED without writing.
     * expectedRevision is an optional metadata precondition; atomic only describes replacement visibility and promises no cross-process mutual exclusion.
     */
    fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String? = null,
        expectedSha256: String? = null,
    ): FileToolWriteResult
}
