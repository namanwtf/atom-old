package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/** The byte cursor may only advance past characters that were fully decoded and output as text, never counting a half-UTF-8 character buffered inside the decoder as read. */
internal class FileToolTextReader(
    private val backend: FileToolBackend,
    private val decoderFactory: () -> CharsetDecoder = {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    },
) {
    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = 16_000): FileToolTextChunk {
        val bytes = backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, 16_000))
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "File contains binary content; use file export or terminal tools to handle it")
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "File changed during reading")
        val eof = offset + bytes.size >= stat.sizeBytes
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(maxChars.coerceIn(2, 16_000))
        val decoder = decoderFactory()
        var result = decoder.decode(input, output, eof)
        if (eof && result.isUnderflow) {
            result = decoder.flush(output)
        }
        if (result.isError) throw FileToolException("NOT_UTF8_TEXT", "Content is not valid UTF-8 text, or the byte cursor is in the middle of a character")
        output.flip()
        val text = output.toString()
        val consumedBytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes.isNotEmpty() && consumedBytes == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "Byte limit is not enough to read one complete character; use at least 4 bytes")
        }
        return FileToolTextChunk(text, consumedBytes, eof && consumedBytes == bytes.size)
    }
}
