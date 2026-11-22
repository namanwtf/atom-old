package io.github.namanwtf.atom.ui.markdown

import androidx.compose.runtime.mutableStateMapOf

/** Streaming state is kept only across item mounts; finished documents that leave composition go to a bounded static cache. */
internal class StreamingMarkdownStateCache {
    private val retainedStates = mutableStateMapOf<String, StreamingMarkdownState>()
    val states: Map<String, StreamingMarkdownState> get() = retainedStates

    fun getOrCreate(messageId: String): StreamingMarkdownState =
        retainedStates.getOrPut(messageId) { StreamingMarkdownState() }

    fun unusedMessageIds(streamingMessageIds: Set<String>): List<String> =
        retainedStates.entries.mapNotNull { (id, state) ->
            id.takeIf { it !in streamingMessageIds && !state.isAttachedToContent }
        }

    fun discardUnused(streamingMessageIds: Set<String>) {
        unusedMessageIds(streamingMessageIds).forEach { id ->
            retainedStates.remove(id)?.snapshot?.let(staticMarkdownDocumentCache::put)
        }
    }
}
