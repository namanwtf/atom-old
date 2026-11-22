package io.github.namanwtf.atom.agent.device

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Streams the authorized file copy without trusting the file size declared by the document provider. */
internal object BoundedFileCopy {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long) {
        require(maxBytes >= 0L)
        val buffer = ByteArray(8 * 1024)
        var copied = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val read = input.read(buffer)
            if (read < 0) return
            if (copied + read > maxBytes) throw TooLargeException()
            output.write(buffer, 0, read)
            copied += read
        }
    }

    class TooLargeException : IOException("File exceeds the import size limit")
}
