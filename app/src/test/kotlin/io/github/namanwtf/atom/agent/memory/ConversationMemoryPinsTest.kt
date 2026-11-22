package io.github.namanwtf.atom.agent.memory

import io.github.namanwtf.atom.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationMemoryPinsTest {
    private fun snapshot(content: String, revision: String) =
        AgentMemorySnapshot(content = content, revision = revision, byteSize = content.length, lineCount = 1)

    @Test
    fun ownWritesKeepTheSessionSnapshotSoTheSystemPromptDoesNotChange() {
        val id = "conv-own-${System.nanoTime()}"
        val first = ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nold", "r1"), 128_000)
        ConversationMemoryPins.recordOwnWrite(id, "r2")
        val next = ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nnew", "r2"), 128_000)
        assertSame(first, next)
    }

    @Test
    fun externalChangesCompactionOrModelSwitchReinjectTheLatestMemory() {
        val id = "conv-ext-${System.nanoTime()}"
        ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nold", "r1"), 128_000)
        val external = ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nmodified elsewhere", "r9"), 128_000)
        assertEquals("r9", external.revision)

        ConversationMemoryPins.recordOwnWrite(id, "r10")
        ConversationMemoryPins.forget(id)
        assertEquals("r10", ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nwrite", "r10"), 128_000).revision)

        val switched = ConversationMemoryPins.contextFor(id, snapshot("# Core memory\nwrite", "r10"), 32_000)
        assertEquals(AgentMemoryContextBuilder.coreBudgetChars(32_000), switched.coreBudgetChars)
    }
}
