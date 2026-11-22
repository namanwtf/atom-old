package io.github.namanwtf.atom.agent.runtime

import android.os.Binder
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** Large text is transferred through a read-only file whose directory link has been removed; the Binder carries only the descriptor and length. */
internal object AgentWireText {
    private const val INLINE_CHARS = 16_384
    private const val FD_SUFFIX = "_atom_text_fd"
    private const val SIZE_SUFFIX = "_atom_text_bytes"
    // Memory protection for a single materialization; refuse delivery when exceeded, without truncating history already persisted to disk.
    private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024

    fun put(bundle: Bundle, key: String, text: String, directory: File?) {
        if (directory == null || text.length <= INLINE_CHARS) {
            bundle.putString(key, text)
            return
        }
        require(text.length <= MAX_TRANSFER_BYTES) { "History transfer exceeds the single memory budget; the original record is retained" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TRANSFER_BYTES) { "History transfer exceeds the single memory budget; the original record is retained" }
        val file = File.createTempFile("atom-context-", ".json", directory)
        try {
            file.outputStream().use { it.write(bytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            bundle.putParcelable(key + FD_SUFFIX, descriptor)
            bundle.putLong(key + SIZE_SUFFIX, bytes.size.toLong())
        } finally {
            check(file.delete()) { "Cannot unlink history transfer file" }
        }
    }

    fun hasDescriptor(bundle: Bundle, key: String): Boolean = bundle.containsKey(key + FD_SUFFIX)

    fun read(bundle: Bundle, key: String): String? {
        val descriptor = bundle.getParcelable(key + FD_SUFFIX, ParcelFileDescriptor::class.java)
            ?: return bundle.getString(key)
        bundle.remove(key + FD_SUFFIX)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size = bundle.getLong(key + SIZE_SUFFIX, -1)
            require(size in 0..MAX_TRANSFER_BYTES && descriptor.statSize == size) {
                "Invalid history transfer descriptor"
            }
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                check(read > 0) { "Incomplete history transfer" }
                offset += read
            }
            check(input.read() == -1) { "History transfer size mismatch" }
            bytes.toString(Charsets.UTF_8)
        }
    }

    /** A same-process Messenger does not go through Parcel; the receiver's descriptor ownership must be duplicated explicitly. */
    fun send(target: Messenger?, message: Message) {
        val original = message.data
        var localCopy: Bundle? = null
        try {
            if (target == null) return
            if (target.binder is Binder) {
                val copy = Bundle(original)
                localCopy = copy
                original.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
                    copy.putParcelable(key, original.getParcelable(key, ParcelFileDescriptor::class.java)!!.dup())
                }
                message.data = copy
            }
            target.send(message)
            localCopy = null
        } finally {
            localCopy?.let(::close)
            close(original)
        }
    }

    fun close(bundle: Bundle) {
        bundle.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
            bundle.getParcelable(key, ParcelFileDescriptor::class.java)?.close()
            bundle.remove(key)
        }
    }
}
