package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# Core memory\nlong-term preference\n## relationship\nfamily\n# detailed background\nmust not be auto-injected"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# Core memory\nlong-term preference\n## relationship\nfamily", context.coreContent)
        assertFalse(context.coreContent.contains("must not be auto-injected"))
        assertEquals("# Core memory\n## relationship\n# detailed background", context.headingIndex)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val content = "# Core memory\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# item\ndetails that should only be read on demand"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# item", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
