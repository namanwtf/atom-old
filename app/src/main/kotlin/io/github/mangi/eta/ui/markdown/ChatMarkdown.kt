package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * Markdown for completed content: shared by historical messages, full thinking text, and the static view after streaming ends.
 *
 * [parsed] is the terminal snapshot of the streaming session and can be reused directly; switching neither re-parses nor passes through placeholder text.
 * Other cases parse in the background; on a cache hit the first frame is already the final layout, so scrolling back and forth through long sessions never re-parses.
 */
@Composable
internal fun StaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    parsed: MarkdownDocument? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val reusable = parsed?.takeIf { it.inlineStyle == style.inline }
    val document = reusable ?: produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value
    if (document == null) {
        // Until parsing completes, show a same-size source-text placeholder whose height is close to the final layout, avoiding out-of-bounds drawing in the list.
        Text(text = content, style = style.body, modifier = modifier)
    } else {
        MarkdownContent(document = document, style = style, modifier = modifier)
    }
}

/**
 * Long static Markdown within an independent scroll viewport (e.g. a MEMORY.md preview).
 *
 * Before parsing completes, don't render the whole source as a placeholder (avoiding a tens-of-KiB single-node MultiParagraph blocking the main thread during page entry animation),
 * and render virtualized per viewport via [LazyMarkdownContent] after parsing.
 */
@Composable
internal fun LazyStaticMarkdown(
    content: String,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    overscrollEffect: OverscrollEffect? = null,
) {
    val style = rememberMarkdownStyle(tone)
    val document = produceState(
        initialValue = staticMarkdownDocumentCache.get(content, style.inline),
        content,
        style.inline,
    ) {
        value = staticMarkdownDocumentCache.get(content, style.inline)
            ?: withContext(Dispatchers.Default) { staticMarkdownDocumentCache.parse(content, style.inline) }
    }.value ?: return
    LazyMarkdownContent(
        document = document,
        style = style,
        modifier = modifier,
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    )
}

/**
 * Markdown for streaming content. Parsing runs serially in the background; the latest target supersedes an old target that hasn't started;
 * the body tone feeds character-by-character reveal, while the thinking tone shows each parse result directly.
 *
 * [onRevealCompleteChange] calls back true after the terminal snapshot of the full text finishes layout and the reveal animation drains,
 * letting callers switch to the selectable static view.
 */
@Composable
internal fun StreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    tone: MarkdownTone,
    modifier: Modifier = Modifier,
    onRevealCompleteChange: (Boolean) -> Unit = {},
) {
    val style = rememberMarkdownStyle(tone)
    // Thinking follows the increments already received, so high-rate thinking doesn't first lay out a placeholder and then pile up constrained by the body's reveal speed.
    val reveal = state.revealCoordinator.takeIf { tone == MarkdownTone.Answer }
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    val currentIsStreaming by rememberUpdatedState(isStreaming)
    val currentCallback by rememberUpdatedState(onRevealCompleteChange)
    val restoreGeneration = state.restoreState.generation

    LifecycleResumeEffect(state) {
        state.revealCoordinator.pauseAnimationsAndCatchUp()
        state.restoreState.begin(currentContent)
        onPauseOrDispose {
            state.restoreState.pause()
            state.revealCoordinator.pauseAnimationsAndCatchUp()
        }
    }
    LaunchedEffect(reveal) {
        reveal?.runFrameClock()
    }
    LaunchedEffect(state) {
        state.parseUpdates()
    }
    LaunchedEffect(content, isStreaming, style.inline) {
        state.parseTargets.trySend(StreamingMarkdownTarget(content, isStreaming, style.inline))
        if (isStreaming) currentCallback(false)
    }
    LaunchedEffect(content, isStreaming, snapshot, reveal) {
        val target = snapshot
        if (!isStreamingMarkdownTargetComplete(content, isStreaming, target?.originalSource, target?.isComplete == true)) {
            currentCallback(false)
            return@LaunchedEffect
        }
        // After the terminal document finishes composition and layout, wait for the trailing characters' reveal to close out.
        withFrameNanos { }
        if (reveal != null && !reveal.drained.value) reveal.drained.filter { it }.first()
        if (isStreamingMarkdownTargetComplete(
                currentContent,
                currentIsStreaming,
                target?.originalSource,
                target?.isComplete == true,
            )
        ) {
            currentCallback(true)
        }
    }

    val parsed = snapshot ?: return
    if (reveal != null) {
        SideEffect { reveal.retainBlocks(parsed.document.revealKeys) }
    }
    MarkdownContent(
        document = parsed.document,
        style = style,
        reveal = reveal,
        modifier = modifier.onGloballyPositioned {
            // Open incremental animation only after the document for the restored baseline actually lays out; parse time isn't limited by frame count.
            if (state.restoreState.completeLayout(
                    generation = restoreGeneration,
                    renderedContent = parsed.originalSource,
                    currentContent = currentContent,
                )
            ) {
                state.revealCoordinator.resumeAnimationsAfterCatchUp()
            }
        },
    )
}
