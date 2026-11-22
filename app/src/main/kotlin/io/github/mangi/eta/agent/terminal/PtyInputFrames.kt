package io.github.mangi.eta.agent.terminal

/**
 * stdin frame format of the native PTY component: 1-byte type + 2-byte big-endian length + payload.
 * stdin is the only channel that reaches both Root and normal-identity processes, so terminal bytes and window size share it,
 * distinguished by frame type, to avoid smuggling control sequences into the terminal byte stream that user input could forge.
 */
internal object PtyInputFrames {
    private const val TYPE_DATA = 0
    private const val TYPE_RESIZE = 1
    private const val HEADER_SIZE = 3
    const val MAX_PAYLOAD = 4096

    /** Terminal input bytes; split into consecutive data frames when exceeding the single-frame limit. */
    fun data(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val frameCount = (bytes.size + MAX_PAYLOAD - 1) / MAX_PAYLOAD
        val out = ByteArray(bytes.size + frameCount * HEADER_SIZE)
        var source = 0
        var target = 0
        while (source < bytes.size) {
            val size = minOf(MAX_PAYLOAD, bytes.size - source)
            target = writeHeader(out, target, TYPE_DATA, size)
            bytes.copyInto(out, target, source, source + size)
            source += size
            target += size
        }
        return out
    }

    /** Window size; the native side rejects values outside 1..1000. */
    fun resize(rows: Int, cols: Int): ByteArray {
        require(rows in 1..1000 && cols in 1..1000) { "Terminal size is out of range" }
        val out = ByteArray(HEADER_SIZE + 4)
        val offset = writeHeader(out, 0, TYPE_RESIZE, 4)
        out[offset] = (rows ushr 8).toByte()
        out[offset + 1] = rows.toByte()
        out[offset + 2] = (cols ushr 8).toByte()
        out[offset + 3] = cols.toByte()
        return out
    }

    private fun writeHeader(out: ByteArray, offset: Int, type: Int, size: Int): Int {
        out[offset] = type.toByte()
        out[offset + 1] = (size ushr 8).toByte()
        out[offset + 2] = size.toByte()
        return offset + HEADER_SIZE
    }
}
