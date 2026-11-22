package io.github.namanwtf.atom.ui.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Restore-animation bounds are determined by already-laid-out content; layout callbacks from the old page cannot release a new round of pausing. */
internal class StreamingMarkdownRestoreState {
    var generation by mutableIntStateOf(0)
        private set
    private var baseline: String? = null

    fun begin(content: String) {
        generation += 1
        baseline = content
    }

    fun pause() {
        generation += 1
        baseline = null
    }

    fun completeLayout(generation: Int, renderedContent: String, currentContent: String): Boolean {
        val pending = baseline ?: return false
        if (generation != this.generation) return false
        val caughtUp = renderedContent == currentContent ||
            (renderedContent.startsWith(pending) && currentContent.startsWith(renderedContent))
        if (!caughtUp) return false
        baseline = null
        return true
    }
}

internal fun isStreamingMarkdownTargetComplete(
    content: String,
    isStreaming: Boolean,
    snapshotContent: String?,
    snapshotComplete: Boolean,
): Boolean = !isStreaming && snapshotComplete && snapshotContent == content
