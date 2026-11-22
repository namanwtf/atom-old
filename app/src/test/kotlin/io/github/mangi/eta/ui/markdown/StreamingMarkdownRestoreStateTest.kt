package io.github.mangi.eta.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownRestoreStateTest {
    @Test
    fun restoredContentWaitsForMatchingLayoutRegardlessOfEarlierLayoutCount() {
        val state = StreamingMarkdownRestoreState()
        state.begin("background-completed content")

        repeat(10) {
            assertFalse(state.completeLayout(state.generation, "background", "background-completed content"))
        }
        assertTrue(state.completeLayout(state.generation, "background-completed content", "background-completed content"))
        assertFalse(state.completeLayout(state.generation, "background-completed content plus increments", "background-completed content plus increments"))
    }

    @Test
    fun delayedOldLayoutCannotResumeAfterAnotherBackgroundCycle() {
        val state = StreamingMarkdownRestoreState()
        state.begin("existing content")
        val oldGeneration = state.generation
        state.pause()
        assertFalse(state.completeLayout(oldGeneration, "existing content", "existing content"))
        state.begin("existing content and background increments")

        assertFalse(state.completeLayout(oldGeneration, "existing content and background increments", "existing content and background increments"))
        assertFalse(state.completeLayout(state.generation, "existing content", "existing content and background increments"))
        assertTrue(state.completeLayout(state.generation, "existing content and background increments", "existing content and background increments"))
    }

    @Test
    fun newNetworkTextDoesNotKeepMovingTheRestoreBaseline() {
        val state = StreamingMarkdownRestoreState()
        state.begin("history")

        assertTrue(state.completeLayout(state.generation, "history", "history and new content"))
    }

    @Test
    fun authoritativeReplacementCanCompleteRestoreButStaleLayoutCannot() {
        val state = StreamingMarkdownRestoreState()
        state.begin("old content")

        assertFalse(state.completeLayout(state.generation, "old content", "corrected content"))
        assertTrue(state.completeLayout(state.generation, "corrected content", "corrected content"))
    }

    @Test
    fun onlyCurrentTerminalSnapshotMayFinishReveal() {
        assertFalse(isStreamingMarkdownTargetComplete("body and increments", false, "body", true))
        assertFalse(isStreamingMarkdownTargetComplete("body", true, "body", true))
        assertFalse(isStreamingMarkdownTargetComplete("body", false, "body", false))
        assertFalse(isStreamingMarkdownTargetComplete("body", false, null, false))
        assertTrue(isStreamingMarkdownTargetComplete("body", false, "body", true))
    }
}
