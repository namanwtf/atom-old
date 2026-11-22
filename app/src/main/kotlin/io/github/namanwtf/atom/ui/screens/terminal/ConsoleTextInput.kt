package io.github.namanwtf.atom.ui.screens.terminal

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import io.github.namanwtf.atom.agent.terminal.TerminalKey
import io.github.namanwtf.atom.agent.terminal.TerminalModifiers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Destination of console input: committed text, function keys, and IME composition preview are dispatched via separate callbacks. */
internal interface ConsoleInputSink {
    fun onText(text: String, modifiers: TerminalModifiers)
    fun onKey(key: TerminalKey, modifiers: TerminalModifiers)
    fun onComposingChange(text: String)
}

/** Held by the UI; requests the mounted input node to show or hide the soft keyboard. */
@Stable
internal class ConsoleKeyboardController {
    internal var node: ConsoleTextInputNode? = null

    fun show() {
        node?.requestKeyboard(show = true)
    }

    fun hide() {
        node?.requestKeyboard(show = false)
    }
}

/**
 * Makes the region a terminal input target: on focus it connects to the IME directly, without a hidden input box.
 * A text-box model would swallow backspaces, treat composing pinyin as input, and miss arrow keys;
 * here only committed text and key events are handed to [sink], keeping the editor always empty.
 */
internal fun Modifier.consoleTextInput(
    sink: ConsoleInputSink,
    controller: ConsoleKeyboardController,
): Modifier = this then ConsoleTextInputElement(sink, controller) then Modifier.focusTarget()

private data class ConsoleTextInputElement(
    val sink: ConsoleInputSink,
    val controller: ConsoleKeyboardController,
) : ModifierNodeElement<ConsoleTextInputNode>() {
    override fun create() = ConsoleTextInputNode(sink, controller)

    override fun update(node: ConsoleTextInputNode) {
        node.update(sink, controller)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "consoleTextInput"
    }
}

internal class ConsoleTextInputNode(
    private var sink: ConsoleInputSink,
    private var controller: ConsoleKeyboardController,
) : Modifier.Node(),
    PlatformTextInputModifierNode,
    FocusRequesterModifierNode,
    FocusEventModifierNode,
    KeyInputModifierNode {

    private var session: Job? = null

    /** Keeps only the most recent show/hide request; if the session isn't established yet, defer until it is. */
    private val keyboardRequests = Channel<Boolean>(Channel.CONFLATED)

    fun update(sink: ConsoleInputSink, controller: ConsoleKeyboardController) {
        this.sink = sink
        if (this.controller !== controller) {
            if (this.controller.node === this) this.controller.node = null
            this.controller = controller
            controller.node = this
        }
    }

    override fun onAttach() {
        controller.node = this
    }

    override fun onDetach() {
        if (controller.node === this) controller.node = null
        session = null
    }

    fun requestKeyboard(show: Boolean) {
        keyboardRequests.trySend(show)
        if (show) requestFocus()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == (session != null)) return
        session?.cancel()
        session = if (focusState.isFocused) startSession() else null
    }

    private fun startSession(): Job = coroutineScope.launch {
        establishTextInputSession {
            val inputMethodManager = view.context.getSystemService(InputMethodManager::class.java)
            launch {
                for (show in keyboardRequests) {
                    if (show) {
                        inputMethodManager.showSoftInput(view, 0)
                    } else {
                        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                }
            }
            startInputMethod { outAttributes ->
                configureEditorInfo(outAttributes)
                ConsoleInputConnection(view) { sink }
            }
        }
    }

    // Hardware-keyboard keys are dispatched through the view to the focused node; soft-keyboard keys go via InputConnection.sendKeyEvent.
    override fun onKeyEvent(event: KeyEvent): Boolean =
        dispatchKeyEvent(event.nativeKeyEvent, sink)

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * Plain text type rather than password type: password type makes most CJK IMEs switch to English;
 * disable suggestions and personalized learning, so autocorrect doesn't rewrite commands and the IME doesn't remember what was typed in the terminal.
 * Multiline with no editor action: Enter appears as a newline key and is converted by the connection into a terminal carriage return.
 */
private fun configureEditorInfo(outAttributes: EditorInfo) {
    outAttributes.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE
    outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_ENTER_ACTION or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    outAttributes.initialSelStart = 0
    outAttributes.initialSelEnd = 0
}

/**
 * The editor carries only the composition region: while composing, content stays in the editable buffer for the IME to rewrite,
 * and is sent and cleared immediately after commit. Delete requests with an empty buffer are forwarded as backspace/delete keys.
 */
private class ConsoleInputConnection(
    view: View,
    private val sink: () -> ConsoleInputSink,
) : BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        editable?.clear()
        sink().onComposingChange("")
        text?.toString()?.takeIf { it.isNotEmpty() }?.let { sink().onText(it, TerminalModifiers.NONE) }
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.setComposingText(text, newCursorPosition)
        sink().onComposingChange(editable?.toString().orEmpty())
        return true
    }

    override fun finishComposingText(): Boolean {
        val pending = editable?.toString().orEmpty()
        editable?.clear()
        sink().onComposingChange("")
        if (pending.isNotEmpty()) sink().onText(pending, TerminalModifiers.NONE)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (!editable.isNullOrEmpty()) {
            val handled = super.deleteSurroundingText(beforeLength, afterLength)
            sink().onComposingChange(editable?.toString().orEmpty())
            return handled
        }
        repeat(beforeLength) { sink().onKey(TerminalKey.BACKSPACE, TerminalModifiers.NONE) }
        repeat(afterLength) { sink().onKey(TerminalKey.DELETE, TerminalModifiers.NONE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
        dispatchKeyEvent(event, sink())
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        sink().onKey(TerminalKey.ENTER, TerminalModifiers.NONE)
        return true
    }

    // Don't let the IME infer sentence start from an empty buffer and auto-capitalize.
    override fun getCursorCapsMode(reqModes: Int): Int = 0
}

/** Returns whether it was consumed; sends only on press, and up events for recognized keys are also consumed to avoid triggering focus movement. */
private fun dispatchKeyEvent(event: AndroidKeyEvent, sink: ConsoleInputSink): Boolean {
    val modifiers = TerminalModifiers(
        ctrl = event.isCtrlPressed,
        alt = event.isAltPressed,
        shift = event.isShiftPressed,
    )
    val key = TERMINAL_KEYS[event.keyCode]
    if (key != null) {
        if (event.action == AndroidKeyEvent.ACTION_DOWN) sink.onKey(key, modifiers)
        return true
    }
    // Strip Ctrl/Alt to get the base character, then apply modifiers via the encoder, sharing the same encoding path as soft-keyboard modifier keys.
    val unicode = event.getUnicodeChar(event.metaState and MODIFIER_META_MASK.inv())
    if (unicode <= 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return false
    if (event.action == AndroidKeyEvent.ACTION_DOWN) {
        // Shift is already reflected in the character itself (uppercase, symbols), so it isn't passed as a modifier.
        sink.onText(String(Character.toChars(unicode)), modifiers.copy(shift = false))
    }
    return true
}

private const val MODIFIER_META_MASK = AndroidKeyEvent.META_CTRL_MASK or AndroidKeyEvent.META_ALT_MASK

private val TERMINAL_KEYS: Map<Int, TerminalKey> = mapOf(
    AndroidKeyEvent.KEYCODE_ENTER to TerminalKey.ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER to TerminalKey.ENTER,
    AndroidKeyEvent.KEYCODE_TAB to TerminalKey.TAB,
    AndroidKeyEvent.KEYCODE_DEL to TerminalKey.BACKSPACE,
    AndroidKeyEvent.KEYCODE_FORWARD_DEL to TerminalKey.DELETE,
    AndroidKeyEvent.KEYCODE_ESCAPE to TerminalKey.ESCAPE,
    AndroidKeyEvent.KEYCODE_DPAD_UP to TerminalKey.UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN to TerminalKey.DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to TerminalKey.LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to TerminalKey.RIGHT,
    AndroidKeyEvent.KEYCODE_MOVE_HOME to TerminalKey.HOME,
    AndroidKeyEvent.KEYCODE_MOVE_END to TerminalKey.END,
    AndroidKeyEvent.KEYCODE_PAGE_UP to TerminalKey.PAGE_UP,
    AndroidKeyEvent.KEYCODE_PAGE_DOWN to TerminalKey.PAGE_DOWN,
    AndroidKeyEvent.KEYCODE_INSERT to TerminalKey.INSERT,
    AndroidKeyEvent.KEYCODE_F1 to TerminalKey.F1,
    AndroidKeyEvent.KEYCODE_F2 to TerminalKey.F2,
    AndroidKeyEvent.KEYCODE_F3 to TerminalKey.F3,
    AndroidKeyEvent.KEYCODE_F4 to TerminalKey.F4,
    AndroidKeyEvent.KEYCODE_F5 to TerminalKey.F5,
    AndroidKeyEvent.KEYCODE_F6 to TerminalKey.F6,
    AndroidKeyEvent.KEYCODE_F7 to TerminalKey.F7,
    AndroidKeyEvent.KEYCODE_F8 to TerminalKey.F8,
    AndroidKeyEvent.KEYCODE_F9 to TerminalKey.F9,
    AndroidKeyEvent.KEYCODE_F10 to TerminalKey.F10,
    AndroidKeyEvent.KEYCODE_F11 to TerminalKey.F11,
    AndroidKeyEvent.KEYCODE_F12 to TerminalKey.F12,
)
