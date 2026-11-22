package io.github.namanwtf.atom.ui.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext

/** The list keeps sessions by message identity, so remounted items reuse existing parse and reveal progress. */
internal class StreamingMarkdownState {
    private var attachedContentCount by mutableIntStateOf(0)
    val isAttachedToContent: Boolean get() = attachedContentCount > 0
    var revealedContent by mutableStateOf<String?>(null)
    private val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)

    fun attachContent() {
        attachedContentCount += 1
    }

    fun detachContent() {
        check(attachedContentCount > 0)
        attachedContentCount -= 1
    }

    suspend fun parseUpdates() = consumeStreamingMarkdownTargets(
        targets = parseTargets,
        parse = { target ->
            withContext(Dispatchers.Default) {
                parserSession.parse(target.content, isComplete = !target.isStreaming, style = target.style)
            }
        },
        publish = { snapshot = it },
    )
}

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val style: MarkdownInlineStyle = MarkdownInlineStyle.Default,
)

internal suspend fun consumeStreamingMarkdownTargets(
    targets: ReceiveChannel<StreamingMarkdownTarget>,
    parse: suspend (StreamingMarkdownTarget) -> StreamingGfmSnapshot,
    publish: (StreamingGfmSnapshot) -> Unit,
) {
    var target = targets.receiveCatching().getOrNull() ?: return
    while (true) {
        while (true) {
            target = targets.tryReceive().getOrNull() ?: break
        }
        val parsed = parse(target)
        val newerTarget = targets.tryReceive().getOrNull()
        // Appended chunks do not invalidate the already-parsed prefix. If results were dropped on every new target, a sustained high-rate stream would
        // starve the display; only when upstream corrects the whole text or reopens a terminal state do we skip snapshots that no longer apply.
        if (newerTarget == null ||
            (newerTarget.content.startsWith(parsed.originalSource) &&
                (!parsed.isComplete || newerTarget == target))
        ) {
            publish(parsed)
        }
        target = newerTarget ?: targets.receiveCatching().getOrNull() ?: return
    }
}
