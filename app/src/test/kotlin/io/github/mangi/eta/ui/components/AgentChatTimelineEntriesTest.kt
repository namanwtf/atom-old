package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatTimelineEntriesTest {

    @Test
    fun unchangedWorkProcessReusesPreviousEntry() {
        val thinking = ThinkingMessageUi(id = "think-1", content = "analysis", isStreaming = false)
        val first = listOf(
            UserMessageUi(id = "user-1", content = "question"),
            thinking,
            AgentMessageUi(id = "agent-1", content = "answer", isStreaming = true),
        )
        val previous = first.toTimelineEntries()
        val next = listOf(
            first[0],
            thinking,
            AgentMessageUi(id = "agent-1", content = "answer", isStreaming = true),
        ).toTimelineEntries(previous)

        assertSame(previous[1], next[1])
    }

    @Test
    fun changedWorkProcessMemberCreatesNewEntry() {
        val first = listOf(ThinkingMessageUi(id = "think-1", content = "analysis", isStreaming = true))
        val previous = first.toTimelineEntries()
        val next = listOf(
            ThinkingMessageUi(id = "think-1", content = "analyzing", isStreaming = true),
        ).toTimelineEntries(previous)

        assertNotSame(previous[0], next[0])
    }

    @Test
    fun prependingTheStartOfAWorkProcessPreservesItsVisibleKey() {
        val partialThinking = ThinkingMessageUi("think-tail", "follow-up thinking", isStreaming = false)
        val answer = AgentMessageUi("answer", "result")
        val previous = listOf(partialThinking, answer).toTimelineEntries()
        val next = listOf(
            UserMessageUi("user", "question"),
            ThinkingMessageUi("think-start", "initial thinking", isStreaming = false),
            partialThinking,
            answer,
        ).toTimelineEntries(previous)

        assertEquals(previous[0].key, next[1].key)
        assertEquals(previous[1].key, next[2].key)
        assertEquals(2, (next[1] as AgentTimelineEntry.WorkProcess).messages.size)
    }

    @Test
    fun splittingAPreviouslyPrependedGroupDoesNotReuseItsKeyTwice() {
        val first = ThinkingMessageUi("think-first", "initial thinking", isStreaming = false)
        val last = ThinkingMessageUi("think-last", "follow-up thinking", isStreaming = false)
        val previous = listOf(first, last).toTimelineEntries(listOf(last).toTimelineEntries())

        val next = listOf(first, AgentMessageUi("answer", "intermediate result"), last).toTimelineEntries(previous)

        assertEquals(next.size, next.map { it.key }.distinct().size)
    }
}
