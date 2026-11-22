package io.github.namanwtf.atom.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("safe content")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# Core memory\nLikes Kotlin\n## Projects\nAtom Agent\nOther")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## Projects"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Atom Agent"))
        assertTrue(search.content.contains("3: ## Projects"))
        assertTrue(search.content.contains("5: Other"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# Core memory\nold preference\n## Projects\nold project")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "new preference",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# Core memory\nnew preference\n## Projects\nold project", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## conflicting append"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("conflicting append"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## new section\ncontent"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("old project"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
