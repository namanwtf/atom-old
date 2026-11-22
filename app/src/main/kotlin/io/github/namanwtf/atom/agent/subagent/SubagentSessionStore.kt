package io.github.namanwtf.atom.agent.subagent

import io.github.namanwtf.atom.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-session cache of Subagent instance contexts, used by the main Agent to resume existing Subagents via `resume`.
 */
internal object SubagentSessionStore {
    private const val MAX_CONVERSATIONS = 16
    private const val MAX_AGENTS_PER_CONVERSATION = 16
    private const val MAX_HISTORY_MESSAGES_PER_AGENT = 64

    class Entry(
        val subagentId: String,
        val profile: SubagentProfile,
        @Volatile var history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val running: AtomicBoolean = AtomicBoolean(false),
    )

    private class ConversationBucket {
        val sequence = AtomicInteger(0)
        val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > MAX_AGENTS_PER_CONVERSATION && eldest?.value?.running?.get() != true
        }
    }

    private val lock = Any()
    private val buckets = object : LinkedHashMap<String, ConversationBucket>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ConversationBucket>?,
        ): Boolean = size > MAX_CONVERSATIONS
    }

    fun create(conversationKey: String, profile: SubagentProfile): Entry = synchronized(lock) {
        val bucket = buckets.getOrPut(conversationKey) { ConversationBucket() }
        val id = "sub_${bucket.sequence.incrementAndGet()}"
        val entry = Entry(subagentId = id, profile = profile, running = AtomicBoolean(true))
        bucket.entries[id] = entry
        entry
    }

    fun get(conversationKey: String, subagentId: String): Entry? = synchronized(lock) {
        buckets[conversationKey]?.entries?.get(subagentId)
    }

    fun complete(
        conversationKey: String,
        subagentId: String,
        updatedHistory: List<AgentModelClient.ConversationMessage>,
    ) {
        val boundedHistory = boundHistory(updatedHistory)
        synchronized(lock) {
            val entry = buckets[conversationKey]?.entries?.get(subagentId) ?: return
            entry.history = boundedHistory
            entry.running.set(false)
        }
    }

    fun releaseRunning(conversationKey: String, subagentId: String) {
        synchronized(lock) {
            buckets[conversationKey]?.entries?.get(subagentId)?.running?.set(false)
        }
    }

    fun clearConversation(conversationKey: String) {
        if (conversationKey.isBlank()) return
        synchronized(lock) {
            buckets.remove(conversationKey)
        }
    }

    /** Keep the recent window and align it to complete user boundaries, so orphaned tool messages don't break the next resume protocol. */
    private fun boundHistory(
        history: List<AgentModelClient.ConversationMessage>,
    ): List<AgentModelClient.ConversationMessage> {
        if (history.size <= MAX_HISTORY_MESSAGES_PER_AGENT) return history
        val dropTarget = history.size - MAX_HISTORY_MESSAGES_PER_AGENT
        val startIndex = (dropTarget until history.size)
            .firstOrNull { history[it].role == "user" }
            ?: dropTarget
        return history.subList(startIndex, history.size).toList()
    }
}
