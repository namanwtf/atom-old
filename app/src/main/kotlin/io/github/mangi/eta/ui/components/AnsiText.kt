package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.mangi.eta.agent.terminal.AnsiSgr
import io.github.mangi.eta.agent.terminal.SgrStyle

/** Mapping of neutral styles to Compose; dim is expressed as alpha attenuation, reverse swaps the explicitly specified foreground and background. */
internal fun SgrStyle.toSpanStyle(): SpanStyle {
    val foreground = fg?.let { Color(it) } ?: Color.Unspecified
    val background = bg?.let { Color(it) } ?: Color.Unspecified
    val (text, fill) = if (inverse) background to foreground else foreground to background
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { underline },
        TextDecoration.LineThrough.takeIf { strikethrough },
    )
    return SpanStyle(
        color = if (dim && text.isSpecified) text.copy(alpha = text.alpha * 0.6f) else text,
        background = fill,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
    )
}

/**
 * ANSI escape rendering for terminal output: SGR colors and weight/italic/underline become [AnnotatedString] spans,
 * while other control sequences (cursor addressing, OSC, etc.) can't be expressed in the block UI and are dropped.
 * `\r` is treated per terminal semantics as line overwrite (a progress bar keeps only the last refresh).
 * Streaming output may truncate a sequence anywhere; an incomplete trailing sequence is dropped and naturally recovered on the next full re-parse.
 */
internal fun ansiToAnnotatedString(text: String): AnnotatedString {
    if (!text.contains(ESC) && !text.contains('\r')) return AnnotatedString(text)
    val out = StringBuilder(text.length)
    val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    var style = SgrStyle.PLAIN
    var runStart = 0

    fun flushRun() {
        if (!style.isPlain && out.length > runStart) {
            spans += Triple(runStart, out.length, style.toSpanStyle())
        }
        runStart = out.length
    }

    var i = 0
    // \r only records the line start; the line is overwritten only when new text follows (CRLF newlines aren't deleted by mistake).
    var pendingOverwriteFrom = -1

    fun applyPendingOverwrite() {
        if (pendingOverwriteFrom < 0) return
        val lineStart = pendingOverwriteFrom
        pendingOverwriteFrom = -1
        if (out.length > lineStart) {
            out.setLength(lineStart)
            while (spans.isNotEmpty() && spans.last().first >= lineStart) {
                spans.removeAt(spans.lastIndex)
            }
            if (spans.isNotEmpty() && spans.last().second > lineStart) {
                val last = spans.removeAt(spans.lastIndex)
                spans += Triple(last.first, lineStart, last.third)
            }
        }
        runStart = out.length
    }

    while (i < text.length) {
        when (text[i]) {
            ESC -> {
                flushRun()
                val result = consumeEscape(text, i, style)
                style = result.style
                i = result.next
            }
            '\r' -> {
                flushRun()
                pendingOverwriteFrom = out.lastIndexOf('\n') + 1
                i++
            }
            '\n' -> {
                pendingOverwriteFrom = -1
                out.append('\n')
                i++
            }
            else -> {
                applyPendingOverwrite()
                out.append(text[i])
                i++
            }
        }
    }
    flushRun()

    return buildAnnotatedString {
        append(out.toString())
        spans.forEach { (start, end, spanStyle) -> addStyle(spanStyle, start, end) }
    }
}

/** Plain text with all escape sequences stripped (for copy, log display, etc.). */
internal fun ansiPlainText(text: String): String = ansiToAnnotatedString(text).text

private const val ESC = '\u001B'

private class EscapeResult(val style: SgrStyle, val next: Int)

/** Consumes one escape sequence; an incomplete sequence is consumed to the end of the text. */
private fun consumeEscape(text: String, start: Int, style: SgrStyle): EscapeResult {
    val kind = text.getOrNull(start + 1) ?: return EscapeResult(style, text.length)
    return when (kind) {
        '[' -> {
            var j = start + 2
            while (j < text.length && text[j] !in '@'..'~') j++
            if (j >= text.length) return EscapeResult(style, text.length)
            val newStyle = if (text[j] == 'm') {
                AnsiSgr.apply(text.substring(start + 2, j), style)
            } else {
                style
            }
            EscapeResult(newStyle, j + 1)
        }
        ']' -> {
            var j = start + 2
            while (j < text.length) {
                if (text[j] == '\u0007') {
                    j++
                    break
                }
                if (text[j] == ESC && j + 1 < text.length && text[j + 1] == '\\') {
                    j += 2
                    break
                }
                j++
            }
            EscapeResult(style, j)
        }
        '(', ')', '#', '%' -> EscapeResult(style, (start + 3).coerceAtMost(text.length))
        else -> EscapeResult(style, start + 2)
    }
}
