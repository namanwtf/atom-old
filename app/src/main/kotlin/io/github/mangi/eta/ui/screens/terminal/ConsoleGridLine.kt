package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.mangi.eta.agent.terminal.SgrStyle
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.terminalCharWidth

/** Theme colors for grid rendering; reverse video, cursor, and composition preview need explicit default foreground and background. */
@Immutable
internal data class ConsolePalette(
    val foreground: Color,
    val background: Color,
    val composing: Color,
)

/** Cell size and baseline, all in pixels; row height and column width come from one metric, keeping grid and PTY sizes consistent. */
@Immutable
internal data class ConsoleCellMetrics(
    val width: Float,
    val height: Float,
    val baseline: Float,
)

/**
 * Glyph layout cache: the same character and weight/style combination is measured only once; color is overridden at draw time.
 * Row content may change every frame, but the set of appearing characters is small; after a cache hit, drawing is just rectangle and bitmap compositing.
 */
internal class ConsoleGlyphCache(
    private val measurer: TextMeasurer,
    private val baseStyle: TextStyle,
) {
    private data class Key(val text: String, val bold: Boolean, val italic: Boolean)

    private val layouts = HashMap<Key, TextLayoutResult>()

    /**
     * Column width takes the width of digit 0: under a true monospace font all glyphs are equal; when some systems replace monospace with proportional,
     * digits stay monospaced, and letters wider than them are horizontally squashed into the cell at draw time, never disrupting the grid.
     */
    fun metrics(): ConsoleCellMetrics {
        val layout = measurer.measure("0", baseStyle, softWrap = false, maxLines = 1)
        return ConsoleCellMetrics(
            width = layout.size.width.toFloat().coerceAtLeast(1f),
            height = layout.size.height.toFloat().coerceAtLeast(1f),
            baseline = layout.firstBaseline,
        )
    }

    fun layout(text: String, bold: Boolean, italic: Boolean): TextLayoutResult {
        val key = Key(text, bold, italic)
        layouts[key]?.let { return it }
        if (layouts.size >= MAX_CACHED_GLYPHS) layouts.clear()
        val style = baseStyle.copy(
            fontWeight = if (bold) FontWeight.Bold else baseStyle.fontWeight,
            fontStyle = if (italic) FontStyle.Italic else baseStyle.fontStyle,
        )
        return measurer.measure(text, style, softWrap = false, maxLines = 1).also { layouts[key] = it }
    }

    private companion object {
        const val MAX_CACHED_GLYPHS = 4096
    }
}

/**
 * Draw a row cell by cell: each glyph lands at `column × column width`, independent of the font's own advance,
 * so CJK fallback fonts and non-monospace system fonts never misalign a TUI's borders, alignment, or cursor.
 */
@Composable
internal fun ConsoleGridLine(
    line: TerminalScreenBuffer.RenderLine,
    cursorCol: Int?,
    composing: String,
    composingCol: Int,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    val cells = line.cells
    val heightDp = with(LocalDensity.current) { metrics.height.toDp() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
    ) {
        drawCells(cells, cursorCol, palette, metrics, glyphs)
        if (composing.isNotEmpty()) drawComposing(composing, composingCol, palette, metrics, glyphs)
    }
}

private fun DrawScope.drawCells(
    cells: List<TerminalScreenBuffer.Cell>,
    cursorCol: Int?,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    var col = 0
    while (col < cells.size) {
        val cell = cells[col]
        if (cell.continuation) {
            col++
            continue
        }
        val span = if (col + 1 < cells.size && cells[col + 1].continuation) 2 else 1
        val x = col * metrics.width
        val cellWidth = span * metrics.width
        var (foreground, background) = resolveColors(cell.style, palette)
        if (col == cursorCol) {
            foreground = palette.background
            background = palette.foreground
        }
        if (background != Color.Unspecified) {
            drawRect(background, topLeft = Offset(x, 0f), size = Size(cellWidth, metrics.height))
        }
        if (cell.text.isNotBlank()) {
            drawGlyph(cell.text, cell.style, foreground, x, cellWidth, metrics, glyphs)
        }
        col += span
    }
}

private fun DrawScope.drawGlyph(
    text: String,
    style: SgrStyle,
    color: Color,
    x: Float,
    cellWidth: Float,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    val layout = glyphs.layout(text, style.bold, style.italic)
    val glyphWidth = layout.size.width.toFloat()
    val top = metrics.baseline - layout.firstBaseline
    val decoration = when {
        style.underline && style.strikethrough -> TextDecoration.combine(
            listOf(TextDecoration.Underline, TextDecoration.LineThrough),
        )
        style.underline -> TextDecoration.Underline
        style.strikethrough -> TextDecoration.LineThrough
        else -> null
    }
    if (glyphWidth > cellWidth * GLYPH_OVERFLOW_TOLERANCE) {
        scale(scaleX = cellWidth / glyphWidth, scaleY = 1f, pivot = Offset(x, 0f)) {
            drawText(layout, color = color, topLeft = Offset(x, top), textDecoration = decoration)
        }
    } else {
        val inset = ((cellWidth - glyphWidth) / 2f).coerceAtLeast(0f)
        drawText(layout, color = color, topLeft = Offset(x + inset, top), textDecoration = decoration)
    }
}

/** Composition preview overlays the cursor, its width computed by character cell count, and doesn't enter the grid before commit. */
private fun DrawScope.drawComposing(
    composing: String,
    col: Int,
    palette: ConsolePalette,
    metrics: ConsoleCellMetrics,
    glyphs: ConsoleGlyphCache,
) {
    var x = col * metrics.width
    var index = 0
    while (index < composing.length) {
        val codePoint = composing.codePointAt(index)
        val value = String(Character.toChars(codePoint))
        val span = terminalCharWidth(codePoint).coerceAtLeast(1)
        val cellWidth = span * metrics.width
        drawRect(palette.background, topLeft = Offset(x, 0f), size = Size(cellWidth, metrics.height))
        drawGlyph(value, COMPOSING_STYLE, palette.composing, x, cellWidth, metrics, glyphs)
        x += cellWidth
        index += Character.charCount(codePoint)
    }
}

/** Explicit colors take priority, falling back to theme defaults when unspecified; reverse swaps foreground and background, dim attenuates via alpha. */
private fun resolveColors(style: SgrStyle, palette: ConsolePalette): Pair<Color, Color> {
    val foreground = style.fg?.let { Color(it) } ?: palette.foreground
    val background = style.bg?.let { Color(it) }
    val (text, fill) = if (style.inverse) {
        (background ?: palette.background) to foreground
    } else {
        foreground to (background ?: Color.Unspecified)
    }
    return (if (style.dim) text.copy(alpha = text.alpha * DIM_ALPHA) else text) to fill
}

/** Proportional glyphs slightly wider than the column stay as-is; only clearly overflowing ones are compressed, so ordinary letters aren't needlessly distorted. */
private const val GLYPH_OVERFLOW_TOLERANCE = 1.08f
private const val DIM_ALPHA = 0.6f
private val COMPOSING_STYLE = SgrStyle(underline = true)
