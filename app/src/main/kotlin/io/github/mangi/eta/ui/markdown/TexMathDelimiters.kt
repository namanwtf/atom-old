package io.github.mangi.eta.ui.markdown

/**
 * Models commonly write formulas using LaTeX's `\(...\)` and `\[...\]`; GFM doesn't recognize them, the backslash gets consumed as an escape,
 * and `_`, `\{` inside formulas would also be rewritten. Before parsing, replace paired delimiters with the GFM-supported `$$` at equal length:
 * source offsets stay unchanged, so block cache, reveal key, and streaming snapshots are all unaffected.
 *
 * Only replace formulas that can be confirmed: skip code blocks and inline code, don't span blank lines; `\[...\]` must occupy a line by itself,
 * so as not to mistake Markdown-escaped brackets (like `arr\[0\]`) for a formula. Unclosed delimiters are kept as-is.
 */
internal object TexMathDelimiters {
    fun normalize(source: String): String {
        if ("\\(" !in source && "\\[" !in source) return source
        val output = StringBuilder(source)
        var fence: String? = null
        var lineStart = 0
        var index = 0
        while (index < source.length) {
            val char = source[index]
            // Consume the newline first so the cursor always advances when the fence branch jumps to line end.
            if (char == '\n') {
                index += 1
                lineStart = index
                continue
            }

            if (index == lineStart) {
                val lineEnd = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                val marker = fenceMarker(source.substring(index, lineEnd))
                val open = fence
                if (open == null && marker != null) {
                    fence = marker
                } else if (open != null && marker != null && marker.first() == open.first() &&
                    marker.length >= open.length && source.substring(index, lineEnd).trim() == marker) {
                    fence = null
                    index = lineEnd
                    continue
                }
                if (fence != null) {
                    index = lineEnd
                    continue
                }
            }
            when {
                char == '`' -> {
                    val run = source.runLength(index, '`')
                    index = codeSpanEnd(source, index + run, run) ?: (index + run)
                }
                char == '\\' && source.getOrNull(index + 1) == '(' -> {
                    val close = findClose(source, index + 2, ')', allowBlankLines = false)
                    if (close == null) {
                        index += 2
                    } else {
                        output.replaceDelimiters(index, close)
                        index = close + 2
                    }
                }
                char == '\\' && source.getOrNull(index + 1) == '[' && source.isLineStart(lineStart, index) -> {
                    val close = findClose(source, index + 2, ']', allowBlankLines = true)
                        ?.takeIf { source.isLineEnd(it + 2) }
                    if (close == null) {
                        index += 2
                    } else {
                        output.replaceDelimiters(index, close)
                        index = close + 2
                    }
                }
                // Other backslash escapes (including `\\`) are skipped wholesale, so `\\(` is not mistaken for a delimiter.
                char == '\\' -> index += 2
                else -> index += 1
            }
        }
        return output.toString()
    }

    /** Finds an unescaped `\` + [closer]; inline formulas don't span blank lines, block formulas don't cross into code fences, and content must not be blank. */
    private fun findClose(source: String, from: Int, closer: Char, allowBlankLines: Boolean): Int? {
        var index = from
        while (index < source.length - 1) {
            when {
                source[index] == '\n' -> {
                    if (!allowBlankLines && source.isBlankLineAfter(index)) return null
                    val nextLineStart = index + 1
                    val nextLineEnd = source.indexOf('\n', nextLineStart).let { if (it < 0) source.length else it }
                    if (fenceMarker(source.substring(nextLineStart, nextLineEnd)) != null) return null
                    index += 1
                }
                source[index] == '\\' && source[index + 1] == closer ->
                    return index.takeIf { source.substring(from, index).isNotBlank() }
                source[index] == '\\' -> index += 2
                else -> index += 1
            }
        }
        return null
    }

    /** Inline code closes with an equal-length run of backticks; if none is found the opening backtick is just an ordinary character. */
    private fun codeSpanEnd(source: String, from: Int, run: Int): Int? {
        var index = from
        while (index < source.length) {
            when {
                source[index] == '\n' && source.isBlankLineAfter(index) -> return null
                source[index] == '`' -> {
                    val length = source.runLength(index, '`')
                    if (length == run) return index + length
                    index += length
                }
                else -> index += 1
            }
        }
        return null
    }

    private fun StringBuilder.replaceDelimiters(open: Int, close: Int) {
        replace(open, open + 2, "$$")
        replace(close, close + 2, "$$")
    }

    private fun fenceMarker(line: String): String? {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return null
        val char = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = trimmed.runLength(0, char)
        return if (run >= 3) char.toString().repeat(run) else null
    }

    private fun String.runLength(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun String.isLineStart(lineStart: Int, index: Int): Boolean =
        substring(lineStart, index).isBlank()

    private fun String.isLineEnd(index: Int): Boolean {
        val lineEnd = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(index, lineEnd).isBlank()
    }

    private fun String.isBlankLineAfter(newline: Int): Boolean {
        val nextEnd = indexOf('\n', newline + 1).let { if (it < 0) length else it }
        return substring(newline + 1, nextEnd).isBlank()
    }
}
