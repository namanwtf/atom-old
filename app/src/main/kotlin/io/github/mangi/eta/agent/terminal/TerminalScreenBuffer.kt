package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Console screen buffer: parses the PTY byte stream into a row/column grid plus scrollback history.
 *
 * Implements only the VT subset TUI apps actually use: SGR, cursor movement, line/screen erase, scroll regions, insert/delete lines and characters,
 * REP, the alternate screen, the DEC line-drawing character set, plus cursor keys, autowrap, bracketed paste, and synchronized output mode.
 * Parameters follow ECMA-48's parameter/intermediate/final byte split; unimplemented sequences are discarded whole, so leftover parameters are never shown as text.
 *
 * PTY output may be chunked at any byte: incomplete UTF-8 bytes, escape sequences, and OSC/DCS strings continue across chunks.
 * Replies to terminal queries (DSR/CPR, DA, DECRQM, window size, OSC 10/11 default colors) are retrieved via [drainResponses] and written back to stdin by the caller;
 * programs that get no reply will hang on a timeout at startup, and some CLIs exit directly because they can't read the cursor position.
 */
internal class TerminalScreenBuffer(
    cols: Int,
    rows: Int,
    private val maxScrollback: Int = 500,
) {
    init {
        require(cols > 0 && rows > 0) { "cols/rows must be positive" }
    }

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    data class Cell(
        val text: String = " ",
        val style: SgrStyle = SgrStyle.PLAIN,
        /** The second placeholder cell of a wide character. */
        val continuation: Boolean = false,
    )

    /**
     * An immutable whole-line snapshot for rendering: it never changes after creation, and equality compares only id and version.
     * The UI must not hold [Line] directly: an in-place-mutated line keeps its identity, so Compose would treat it as unchanged and skip redrawing;
     * after a full-screen redraw the old content stays on screen, and the draw thread reading mutable cells would also interleave with PTY-thread writes.
     */
    class RenderLine internal constructor(val id: Long, val version: Long, val cells: List<Cell>) {
        override fun equals(other: Any?): Boolean =
            other is RenderLine && other.id == id && other.version == version

        override fun hashCode(): Int = 31 * id.hashCode() + version.hashCode()
    }

    /** A grid row; object identity is stable and it converts to [RenderLine] when published to the UI. */
    class Line internal constructor(val id: Long, width: Int) {
        internal var cells = Array(width) { Cell() }
        var version = 0L
            internal set

        internal fun set(index: Int, cell: Cell) {
            cells[index] = cell
            version++
        }

        internal fun fill(cell: Cell) {
            cells.fill(cell)
            version++
        }

        internal fun resize(width: Int) {
            if (width == cells.size) return
            val previous = cells
            cells = Array(width) { index -> previous.getOrNull(index) ?: Cell() }
            // When the cut point falls inside a wide character, the first cell loses its placeholder and can only be cleared to blank.
            if (width < previous.size && previous[width].continuation) cells[width - 1] = Cell()
            version++
        }

        internal fun isBlank(): Boolean = cells.all { (it.text == " " || it.text.isEmpty()) && it.style.bg == null }

        fun snapshot(): List<Cell> = cells.toList()

        private var rendered: RenderLine? = null

        /** Reuses the previous snapshot when the version is unchanged, copying cells each frame only for rows that actually changed. */
        internal fun render(): RenderLine =
            rendered?.takeIf { it.version == version } ?: RenderLine(id, version, cells.toList()).also { rendered = it }
    }

    private var nextLineId = 0L
    private var screen = Array(rows) { Line(nextLineId++, cols) }
    private val scrollback = ArrayDeque<Line>()

    /** Incremented each time process produces a change; the render layer pulls a new snapshot accordingly. */
    var version = 0L
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    /** DECCKM: when on, arrow keys send SS3 (ESC O A) instead of CSI (ESC [ A). */
    var applicationCursorKeys = false
        private set

    /** When on, pasted content must be wrapped between ESC [200~ / ESC [201~ so programs can tell paste from per-key input. */
    var bracketedPaste = false
        private set

    /** ?2026: the program declares a frame is not finished, so the render layer should hold publication to avoid showing a half-drawn frame. */
    var synchronizedOutput = false
        private set

    private var autoWrap = true
    private var style = SgrStyle.PLAIN
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var wrapPending = false
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false
    private var lastPrinted: String? = null
    private var lastPrintedWidth = 1
    private var saved = SavedCursor()
    private var altActive = false
    private var mainScreen: Array<Line>? = null
    private var mainCursorRow = 0
    private var mainCursorCol = 0

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var undecoded = EMPTY_BYTES

    /** An unfinished escape sequence or split surrogate pair, waiting to be completed by the next output chunk. */
    private var pendingInput = ""

    /** Inside an OSC/DCS/APC/PM/SOS string until BEL or ST ends it; the content is not rendered. */
    private var inControlString = false
    private var controlStringKind = ' '
    private val controlString = StringBuilder()

    /**
     * The UI's default foreground/background colors (0xAARRGGBB). TUIs query them via OSC 10/11 to decide between light and dark themes;
     * without a reply most programs assume a dark background, and under a light theme they pick illegible light-gray/light-yellow colors.
     */
    var defaultForeground: Long = 0xFF000000
    var defaultBackground: Long = 0xFFFFFFFF
    private val responses = StringBuilder()

    private data class SavedCursor(
        val row: Int = 0,
        val col: Int = 0,
        val style: SgrStyle = SgrStyle.PLAIN,
        val g0Graphics: Boolean = false,
        val g1Graphics: Boolean = false,
        val shiftOut: Boolean = false,
    )

    /** Scrollback history + current screen; Line identity is stable across snapshots. */
    fun lines(): List<Line> = scrollback.toList() + screen.toList()

    /** Immutable frame published to the UI; callers must hold the same lock as [process]/[resize]. */
    fun renderLines(): List<RenderLine> {
        val out = ArrayList<RenderLine>(scrollback.size + screen.size)
        scrollback.forEach { out += it.render() }
        screen.forEach { out += it.render() }
        return out
    }

    /** For testing and diagnostics: the current screen's plain-text rows (trailing spaces trimmed). */
    fun dump(): List<String> = screen.map { line ->
        line.cells.joinToString("") { it.text }.trimEnd()
    }

    /** Row [row] of the current screen (excluding scrollback). */
    fun screenLine(row: Int): Line = screen[row]

    /** Retrieves the query reply to write back to the program; returns null when there is none. */
    fun drainResponses(): String? {
        if (responses.isEmpty()) return null
        return responses.toString().also { responses.setLength(0) }
    }

    /** Feeds raw PTY bytes; a UTF-8 multibyte sequence truncated by chunking is kept for the next continuation. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val source = if (undecoded.isEmpty()) bytes else undecoded + bytes.copyOfRange(0, length)
        val size = if (undecoded.isEmpty()) length else source.size
        val input = ByteBuffer.wrap(source, 0, size)
        val output = CharBuffer.allocate(size + 1)
        decoder.decode(input, output, false)
        undecoded = if (input.hasRemaining()) source.copyOfRange(input.position(), size) else EMPTY_BYTES
        output.flip()
        if (output.hasRemaining()) process(output.toString())
    }

    fun process(chunk: String) {
        val text = if (pendingInput.isEmpty()) chunk else pendingInput + chunk
        pendingInput = ""
        var i = 0
        while (i < text.length) {
            if (inControlString) {
                i = skipControlString(text, i)
                continue
            }
            val c = text[i]
            when {
                c.code == 0x1B -> {
                    val next = consumeEscape(text, i)
                    if (next == INCOMPLETE) {
                        // A sequence that runs too long without ending is treated as corrupt and dropped rather than buffered forever.
                        pendingInput = text.substring(i).takeIf { it.length <= MAX_PENDING_INPUT }.orEmpty()
                        break
                    }
                    i = next
                }
                c == '\n' || c.code == 0x0B || c.code == 0x0C -> {
                    linefeed()
                    wrapPending = false
                    i++
                }
                c == '\r' -> {
                    cursorCol = 0
                    wrapPending = false
                    i++
                }
                c == '\b' -> {
                    if (cursorCol > 0) cursorCol--
                    wrapPending = false
                    i++
                }
                c == '\t' -> {
                    tabForward(1)
                    i++
                }
                c.code == 0x0E -> {
                    shiftOut = true
                    i++
                }
                c.code == 0x0F -> {
                    shiftOut = false
                    i++
                }
                c.code < 0x20 || c.code in 0x7F..0x9F -> i++
                c.isHighSurrogate() && i + 1 >= text.length -> {
                    pendingInput = c.toString()
                    break
                }
                else -> {
                    val cp = if (c.isHighSurrogate() && text[i + 1].isLowSurrogate()) {
                        Character.toCodePoint(c, text[i + 1])
                    } else {
                        c.code
                    }
                    printCodePoint(cp)
                    i += Character.charCount(cp)
                }
            }
        }
        version++
    }

    /**
     * Resizes the grid. When shrinking, drop empty rows below the cursor first, and only if that's not enough push top rows into scrollback;
     * when growing, pull rows back from scrollback so the view stays bottom-anchored and reversible across soft-keyboard show/hide.
     */
    fun resize(newCols: Int, newRows: Int) {
        require(newCols > 0 && newRows > 0) { "cols/rows must be positive" }
        if (newCols == cols && newRows == rows) return
        if (altActive) {
            mainScreen?.let { main ->
                val (lines, row) = resizeScreen(main, mainCursorRow, newCols, newRows, retainHistory = true)
                mainScreen = lines
                mainCursorRow = row
            }
            val (lines, row) = resizeScreen(screen, cursorRow, newCols, newRows, retainHistory = false)
            screen = lines
            cursorRow = row
        } else {
            val (lines, row) = resizeScreen(screen, cursorRow, newCols, newRows, retainHistory = true)
            screen = lines
            cursorRow = row
        }
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceAtMost(cols - 1)
        mainCursorCol = mainCursorCol.coerceAtMost(cols - 1)
        saved = saved.copy(row = saved.row.coerceAtMost(rows - 1), col = saved.col.coerceAtMost(cols - 1))
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        version++
    }

    private fun resizeScreen(
        lines: Array<Line>,
        cursor: Int,
        newCols: Int,
        newRows: Int,
        retainHistory: Boolean,
    ): Pair<Array<Line>, Int> {
        val current = ArrayDeque(lines.asList())
        var row = cursor
        current.forEach { it.resize(newCols) }
        while (current.size > newRows) {
            val belowCursor = current.size - 1 > row
            if (belowCursor && (current.last().isBlank() || row == 0)) {
                current.removeLast()
            } else {
                val top = current.removeFirst()
                row--
                if (retainHistory) pushScrollback(top)
            }
        }
        while (current.size < newRows) {
            val restored = if (retainHistory) scrollback.removeLastOrNull() else null
            if (restored != null) {
                restored.resize(newCols)
                current.addFirst(restored)
                row++
            } else {
                current.addLast(Line(nextLineId++, newCols))
            }
        }
        return current.toTypedArray() to row.coerceIn(0, newRows - 1)
    }

    private fun printCodePoint(cp: Int) {
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        val mapped = if (graphics && cp < 0x80) decSpecialGraphic(cp.toChar()) else null
        val value = mapped?.toString() ?: String(Character.toChars(cp))
        val width = if (mapped != null) 1 else terminalCharWidth(cp)
        if (width == 0) {
            appendToPreviousCell(value)
            return
        }
        putChar(value, width)
        lastPrinted = value
        lastPrintedWidth = width
    }

    /** Combining marks and zero-width joiners merge into the previous glyph's cell. */
    private fun appendToPreviousCell(value: String) {
        var col = if (wrapPending) cursorCol else cursorCol - 1
        if (col < 0) return
        val line = screen[cursorRow]
        if (line.cells[col].continuation && col > 0) col--
        val cell = line.cells[col]
        line.set(col, cell.copy(text = cell.text + value))
    }

    private fun putChar(value: String, requestedWidth: Int) {
        val width = if (cols < 2) 1 else requestedWidth
        if (wrapPending && autoWrap) {
            cursorCol = 0
            linefeed()
        }
        wrapPending = false
        if (width == 2 && cursorCol == cols - 1) {
            // Wide characters don't squeeze the right edge: wrap to the next line when autowrap is on, otherwise back up one cell and write at the line end.
            if (autoWrap) {
                cursorCol = 0
                linefeed()
            } else {
                cursorCol = cols - 2
            }
        }
        val line = screen[cursorRow]
        clearWideCharAt(line, cursorCol)
        if (width == 2) clearWideCharAt(line, cursorCol + 1)
        line.set(cursorCol, Cell(value, style))
        if (width == 2) {
            line.set(cursorCol + 1, Cell("", style, continuation = true))
        }
        if (cursorCol + width >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        } else {
            cursorCol += width
        }
    }

    /** When overwriting either half of a wide character, clear the other half to avoid a stray half-glyph. */
    private fun clearWideCharAt(line: Line, col: Int) {
        val cell = line.cells[col]
        if (cell.continuation && col > 0) {
            line.set(col - 1, Cell(" ", cell.style))
        } else if (!cell.continuation && col + 1 < line.cells.size && line.cells[col + 1].continuation) {
            line.set(col + 1, Cell(" ", cell.style))
        }
    }

    private fun linefeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun tabForward(count: Int) {
        repeat(count) {
            cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
        }
        wrapPending = false
    }

    private fun tabBackward(count: Int) {
        repeat(count) {
            cursorCol = if (cursorCol == 0) 0 else ((cursorCol - 1) / 8) * 8
        }
        wrapPending = false
    }

    private fun pushScrollback(line: Line) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val top = screen[scrollTop]
            for (row in scrollTop until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            screen[scrollBottom] = top
            if (scrollTop == 0 && !altActive) {
                pushScrollback(top)
                screen[scrollBottom] = Line(nextLineId++, cols)
            } else {
                top.fill(eraseCell())
            }
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            val regionHeight = scrollBottom - scrollTop + 1
            if (regionHeight <= 0) return
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo scrollTop + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[scrollTop] = bottom
        }
    }

    /** Erase inherits the background color (bce): erased blanks keep the current SGR background. */
    private fun eraseCell(): Cell =
        if (style.bg != null) Cell(" ", SgrStyle(bg = style.bg)) else Cell()

    /** Returns the index after the sequence; returns [INCOMPLETE] when the sequence doesn't finish within this chunk. */
    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) return INCOMPLETE
        return when (val kind = text[start + 1]) {
            '[' -> consumeCsi(text, start)
            ']', 'P', '_', '^', 'X' -> {
                inControlString = true
                controlStringKind = kind
                controlString.setLength(0)
                start + 2
            }
            '(', ')' -> {
                if (start + 2 >= text.length) return INCOMPLETE
                val graphics = text[start + 2] == '0'
                if (kind == '(') g0Graphics = graphics else g1Graphics = graphics
                start + 3
            }
            '*', '+', '-', '.', '/', '#', '%', ' ' ->
                if (start + 2 >= text.length) INCOMPLETE else start + 3
            '7' -> {
                saveCursor()
                start + 2
            }
            '8' -> {
                restoreCursor()
                start + 2
            }
            'D' -> {
                linefeed()
                wrapPending = false
                start + 2
            }
            'E' -> {
                cursorCol = 0
                linefeed()
                wrapPending = false
                start + 2
            }
            'M' -> {
                if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
                wrapPending = false
                start + 2
            }
            'c' -> {
                reset()
                start + 2
            }
            // ESC \ is the string terminator ST and is a no-op on its own; keyboard modes like ESC = / ESC > don't affect the grid.
            else -> start + 2
        }
    }

    /** Skips string content: BEL ends it; ESC returns to the main loop, ESC \ is consumed as ST, and any other ESC is treated as a new sequence interrupting it. */
    private fun skipControlString(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            when (text[j].code) {
                0x07 -> {
                    finishControlString(terminator = "\u0007")
                    return j + 1
                }
                0x18, 0x1A -> {
                    inControlString = false
                    return j + 1
                }
                0x1B -> {
                    // When ESC sits at the end of the chunk we can't tell if it's ST; defer it to the next chunk.
                    if (j + 1 >= text.length) {
                        pendingInput = "\u001B"
                        return text.length
                    }
                    if (text[j + 1] == '\\') finishControlString(terminator = "\u001B\\")
                    inControlString = false
                    return j
                }
            }
            if (controlString.length < MAX_CONTROL_STRING) controlString.append(text[j])
            j++
        }
        return j
    }

    private fun finishControlString(terminator: String) {
        inControlString = false
        if (controlStringKind != ']') return
        val color = when (controlString.toString()) {
            "10;?" -> 10 to defaultForeground
            "11;?" -> 11 to defaultBackground
            else -> return
        }
        responses.append("\u001B]${color.first};${xtermColor(color.second)}$terminator")
    }

    /** xterm's rgb:rrrr/gggg/bbbb form; each 8-bit component is replicated to 16 bits. */
    private fun xtermColor(argb: Long): String {
        fun channel(shift: Int): String = "%02x".format((argb shr shift) and 0xFF).repeat(2)
        return "rgb:${channel(16)}/${channel(8)}/${channel(0)}"
    }

    private fun consumeCsi(text: String, start: Int): Int {
        var i = start + 2
        val paramsStart = i
        while (i < text.length && text[i] in '0'..'?') i++
        val params = text.substring(paramsStart, i)
        val intermediatesStart = i
        while (i < text.length && text[i] in ' '..'/') i++
        if (i >= text.length) return INCOMPLETE
        val final = text[i]
        // An illegal byte appears mid-sequence: abandon the sequence and reprocess this byte as ordinary input.
        if (final !in '@'..'~') return i
        dispatchCsi(params, text.substring(intermediatesStart, i), final)
        return i + 1
    }

    private fun dispatchCsi(params: String, intermediates: String, final: Char) {
        val prefix = params.firstOrNull()?.takeIf { it in '<'..'?' }
        val body = if (prefix != null) params.substring(1) else params
        val args = body.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        when {
            intermediates.isNotEmpty() -> dispatchIntermediateCsi(prefix, args, intermediates, final)
            prefix == '?' -> dispatchPrivateCsi(args, final)
            prefix == '>' -> if (final == 'c') responses.append(SECONDARY_DEVICE_ATTRIBUTES)
            prefix != null -> Unit
            final == 'm' -> style = AnsiSgr.apply(body, style)
            else -> dispatchStandardCsi(args, final)
        }
    }

    private fun dispatchIntermediateCsi(prefix: Char?, args: List<Int>, intermediates: String, final: Char) {
        when {
            prefix == '?' && intermediates == "$" && final == 'p' -> {
                val mode = args.first()
                responses.append("\u001B[?$mode;${privateModeState(mode)}\$y")
            }
            prefix == null && intermediates == "!" && final == 'p' -> softReset()
        }
    }

    private fun dispatchPrivateCsi(args: List<Int>, final: Char) {
        when (final) {
            'h', 'l' -> args.forEach { setPrivateMode(it, final == 'h') }
            'n' -> if (args.first() == 6) responses.append("\u001B[?${cursorRow + 1};${cursorCol + 1}R")
            'J' -> eraseInDisplay(args.first())
            'K' -> eraseInLine(args.first())
        }
    }

    private fun dispatchStandardCsi(args: List<Int>, final: Char) {
        fun arg(index: Int, default: Int): Int = args.getOrNull(index)?.takeIf { it != 0 } ?: default
        val count = arg(0, 1)
        when (final) {
            'A' -> moveCursorRow(-count)
            'B', 'e' -> moveCursorRow(count)
            'C', 'a' -> moveCursorCol(count)
            'D' -> moveCursorCol(-count)
            'E' -> {
                moveCursorRow(count)
                cursorCol = 0
            }
            'F' -> {
                moveCursorRow(-count)
                cursorCol = 0
            }
            'G', '`' -> setCursor(cursorRow, count - 1)
            'd' -> setCursor(count - 1, cursorCol)
            'H', 'f' -> setCursor(arg(0, 1) - 1, arg(1, 1) - 1)
            'I' -> tabForward(count)
            'Z' -> tabBackward(count)
            'J' -> eraseInDisplay(args.first())
            'K' -> eraseInLine(args.first())
            'L' -> insertLines(count)
            'M' -> deleteLines(count)
            'P' -> deleteChars(count)
            '@' -> insertChars(count)
            'X' -> eraseChars(count)
            'S' -> scrollUp(count)
            'T' -> scrollDown(count)
            'b' -> repeatLastChar(count)
            'r' -> {
                val top = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                }
                setCursor(0, 0)
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> when (args.first()) {
                5 -> responses.append("\u001B[0n")
                6 -> responses.append("\u001B[${cursorRow + 1};${cursorCol + 1}R")
            }
            'c' -> if (args.first() == 0) responses.append(PRIMARY_DEVICE_ATTRIBUTES)
            't' -> if (args.first() == 18) responses.append("\u001B[8;$rows;${cols}t")
        }
    }

    /** Relative movement is constrained by the scroll region, but once the cursor is already outside it only the screen bounds apply. */
    private fun moveCursorRow(delta: Int) {
        val top = if (cursorRow >= scrollTop) scrollTop else 0
        val bottom = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = (cursorRow + delta).coerceIn(top, bottom)
        wrapPending = false
    }

    private fun moveCursorCol(delta: Int) {
        cursorCol = (cursorCol + delta).coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun repeatLastChar(count: Int) {
        val value = lastPrinted ?: return
        repeat(count.coerceAtMost(cols * rows)) { putChar(value, lastPrintedWidth) }
    }

    private fun setPrivateMode(mode: Int, enabled: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = enabled
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            47, 1047 -> if (enabled) enterAltScreen() else exitAltScreen()
            1048 -> if (enabled) saveCursor() else restoreCursor()
            1049 -> if (enabled) {
                saveCursor()
                enterAltScreen()
            } else {
                exitAltScreen()
                restoreCursor()
            }
            2004 -> bracketedPaste = enabled
            2026 -> synchronizedOutput = enabled
        }
    }

    /** DECRQM reply values: 1 set, 2 reset, 0 unknown. */
    private fun privateModeState(mode: Int): Int {
        val enabled = when (mode) {
            1 -> applicationCursorKeys
            7 -> autoWrap
            25 -> cursorVisible
            47, 1047, 1049 -> altActive
            2004 -> bracketedPaste
            2026 -> synchronizedOutput
            else -> return 0
        }
        return if (enabled) 1 else 2
    }

    private fun saveCursor() {
        saved = SavedCursor(cursorRow, cursorCol, style, g0Graphics, g1Graphics, shiftOut)
    }

    private fun restoreCursor() {
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        style = saved.style
        g0Graphics = saved.g0Graphics
        g1Graphics = saved.g1Graphics
        shiftOut = saved.shiftOut
        wrapPending = false
    }

    private fun enterAltScreen() {
        if (altActive) return
        altActive = true
        mainScreen = screen
        mainCursorRow = cursorRow
        mainCursorCol = cursorCol
        screen = Array(rows) { Line(nextLineId++, cols) }
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun exitAltScreen() {
        if (!altActive) return
        altActive = false
        mainScreen?.let { screen = it }
        mainScreen = null
        cursorRow = mainCursorRow
        cursorCol = mainCursorCol
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (row in cursorRow + 1..rows - 1) screen[row].fill(eraseCell())
            }
            1 -> {
                for (row in 0 until cursorRow) screen[row].fill(eraseCell())
                eraseInLine(1)
            }
            2 -> for (row in 0 until rows) screen[row].fill(eraseCell())
            3 -> scrollback.clear()
        }
    }

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        when (mode) {
            0 -> for (col in cursorCol until cols) line.set(col, blank)
            1 -> for (col in 0..cursorCol) line.set(col, blank)
            2 -> line.fill(blank)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val bottom = screen[scrollBottom]
            for (row in scrollBottom downTo cursorRow + 1) {
                screen[row] = screen[row - 1]
            }
            bottom.fill(eraseCell())
            screen[cursorRow] = bottom
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            val top = screen[cursorRow]
            for (row in cursorRow until scrollBottom) {
                screen[row] = screen[row + 1]
            }
            top.fill(eraseCell())
            screen[scrollBottom] = top
        }
    }

    private fun insertChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cols - 1 downTo cursorCol + 1) {
                line.set(col, line.cells[col - 1])
            }
            line.set(cursorCol, blank)
        }
    }

    private fun deleteChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        repeat(count.coerceAtMost(cols - cursorCol)) {
            for (col in cursorCol until cols - 1) {
                line.set(col, line.cells[col + 1])
            }
            line.set(cols - 1, blank)
        }
    }

    private fun eraseChars(count: Int) {
        val line = screen[cursorRow]
        val blank = eraseCell()
        val end = (cursorCol + count).coerceAtMost(cols)
        for (col in cursorCol until end) line.set(col, blank)
    }

    /** DECSTR: restores modes and styles without clearing the screen or scrollback. */
    private fun softReset() {
        cursorVisible = true
        style = SgrStyle.PLAIN
        scrollTop = 0
        scrollBottom = rows - 1
        autoWrap = true
        applicationCursorKeys = false
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        saved = SavedCursor()
        wrapPending = false
    }

    private fun reset() {
        softReset()
        screen = Array(rows) { Line(nextLineId++, cols) }
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        bracketedPaste = false
        synchronizedOutput = false
        lastPrinted = null
        altActive = false
        mainScreen = null
    }

    private companion object {
        const val INCOMPLETE = -1
        const val MAX_PENDING_INPUT = 256
        const val MAX_CONTROL_STRING = 64
        val EMPTY_BYTES = ByteArray(0)

        /** VT220 + ANSI colors; doesn't declare unimplemented capabilities like sixel. */
        const val PRIMARY_DEVICE_ATTRIBUTES = "\u001B[?62;22c"
        const val SECONDARY_DEVICE_ATTRIBUTES = "\u001B[>1;10;0c"
    }
}
