package io.github.namanwtf.atom.ui.markdown

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StreamingMarkdownStateCacheTest {
    @Test
    fun completedOffscreenTurnsDoNotAccumulateStreamingSessions() {
        val cache = StreamingMarkdownStateCache()
        repeat(200) { index ->
            val id = "answer-$index"
            val state = cache.getOrCreate(id)
            state.attachContent()
            cache.discardUnused(emptySet())
            assertSame(state, cache.states[id])

            state.detachContent()
            cache.discardUnused(emptySet())
            assertTrue(cache.states.isEmpty())
        }
    }

    @Test
    fun detachedLiveMessageKeepsItsParsingAndRevealState() {
        val cache = StreamingMarkdownStateCache()
        val state = cache.getOrCreate("live")
        state.revealedContent = "already-revealed prefix"

        cache.discardUnused(setOf("live"))

        assertSame(state, cache.getOrCreate("live"))
        assertEquals("already-revealed prefix", cache.states["live"]?.revealedContent)
        assertTrue(cache.unusedMessageIds(setOf("live")).isEmpty())
    }

    @Test
    fun everyMountedOwnerMustDetachBeforeACompletedMessageCanBeEvicted() {
        val cache = StreamingMarkdownStateCache()
        val state = cache.getOrCreate("visible")
        state.attachContent()
        state.attachContent()
        state.detachContent()

        cache.discardUnused(emptySet())
        assertSame(state, cache.states["visible"])

        state.detachContent()
        cache.discardUnused(emptySet())
        assertNull(cache.states["visible"])
    }

    @Test
    fun evictionHandsTheFinalDocumentToTheBoundedStaticCache() {
        val cache = StreamingMarkdownStateCache()
        val content = "**finalized answer: cache handoff**"
        val snapshot = StreamingGfmParserSession().parse(content, isComplete = true)
        cache.getOrCreate("completed").snapshot = snapshot

        cache.discardUnused(emptySet())

        assertTrue(cache.states.isEmpty())
        assertSame(snapshot.document, staticMarkdownDocumentCache.get(content, snapshot.document.inlineStyle))
    }

    @Test
    fun anIncompleteDetachedSnapshotNeverBecomesAStaticDocument() {
        val cache = StreamingMarkdownStateCache()
        val content = "**unfinished snapshot: not entering the static cache"
        val snapshot = StreamingGfmParserSession().parse(content, isComplete = false)
        cache.getOrCreate("unfinished").snapshot = snapshot

        cache.discardUnused(emptySet())

        assertFalse(cache.states.containsKey("unfinished"))
        assertNull(staticMarkdownDocumentCache.get(content, snapshot.document.inlineStyle))
    }

    @Test
    fun movingCompletedDocumentsToStaticRenderingStillHasABoundedHistoryCache() {
        val cache = StreamingMarkdownStateCache()
        repeat(128) { index ->
            val content = "bounded document cache regression test $index"
            cache.getOrCreate("answer-$index").snapshot =
                StreamingGfmParserSession().parse(content, isComplete = true)
            cache.discardUnused(emptySet())
        }

        assertTrue(cache.states.isEmpty())
        assertNull(staticMarkdownDocumentCache.get("bounded document cache regression test 0", MarkdownInlineStyle.Default))
        assertNotNull(staticMarkdownDocumentCache.get("bounded document cache regression test 127", MarkdownInlineStyle.Default))
    }
}
