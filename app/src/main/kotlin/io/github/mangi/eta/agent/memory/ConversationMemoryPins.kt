package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

/**
 * The memory context is pinned per conversation, keeping the system prompt byte-for-byte identical across turns.
 *
 * Memory lives inside the system prompt, which is the start of the request prefix: rebuilding it on every write would invalidate the cache for all of this conversation's prior
 * history. What this conversation itself wrote already stays in history as tool calls, so the model does not need to re-read it from the system
 * prompt; therefore, as long as memory has not been changed by another source (another conversation, a user edit on the settings page), keep reusing
 * the snapshot injected the first time. Re-inject only when it was changed externally or the context was compacted (writes in history may have been summarized away).
 *
 * Saved only in-process: after a process restart the first turn re-injects once, at the cost of one cache miss, with no effect on correctness;
 * writes still use optimistic concurrency checks via revision, so reusing the old snapshot never overwrites changes made elsewhere.
 */
internal object ConversationMemoryPins {
    private class Pin(
        val context: AgentMemoryContext,
        val contextWindow: Int?,
        var knownRevision: String,
    )

    private const val MAX_PINS = 32

    private val pins = object : LinkedHashMap<String, Pin>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pin>?): Boolean = size > MAX_PINS
    }

    @Synchronized
    fun contextFor(conversationId: String?, snapshot: AgentMemorySnapshot, contextWindow: Int?): AgentMemoryContext {
        if (conversationId != null) {
            val pin = pins[conversationId]
            if (pin != null && pin.knownRevision == snapshot.revision && pin.contextWindow == contextWindow) {
                return pin.context
            }
        }
        val context = AgentMemoryContextBuilder.build(snapshot, contextWindow)
        if (conversationId != null) pins[conversationId] = Pin(context, contextWindow, snapshot.revision)
        return context
    }

    /** Called after this conversation's memory_write succeeds: this change is already in history and is not counted as an external change. */
    @Synchronized
    fun recordOwnWrite(conversationId: String?, revision: String) {
        if (conversationId == null || revision.isBlank()) return
        pins[conversationId]?.knownRevision = revision
    }

    @Synchronized
    fun forget(conversationId: String?) {
        if (conversationId != null) pins.remove(conversationId)
    }
}
