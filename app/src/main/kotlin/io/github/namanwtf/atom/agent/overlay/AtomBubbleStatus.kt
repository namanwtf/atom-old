package io.github.namanwtf.atom.agent.overlay

import android.content.res.Resources
import io.github.namanwtf.atom.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the pill beside the bubble says.
 *
 * Buddy puts a small status pill next to its orb (working, using the phone,
 * paused) so the bubble explains itself without opening the chat. This is Atom's
 * version of it: the copy comes from the same [AgentOverlayStatus] the capsule
 * and the ongoing notification already render, so the three can never disagree.
 */
internal data class AtomBubbleStatus(
    val text: String = "",
    val tone: Tone = Tone.IDLE,
) {
    enum class Tone {
        /** Nothing is running: the pill is not shown at all. */
        IDLE,

        /** A run is thinking or working. */
        WORKING,

        /** The run is stopped and waiting for the user. */
        PAUSED,
    }

    val visible: Boolean get() = tone != Tone.IDLE && text.isNotBlank()

    companion object {
        val Idle = AtomBubbleStatus()

        /** Projects the runtime's overlay state onto the pill. */
        fun from(state: AgentOverlayState, resources: Resources): AtomBubbleStatus = when (state.phase) {
            AgentOverlayPhase.PAUSED ->
                AtomBubbleStatus(resources.getString(R.string.overlay_paused), Tone.PAUSED)

            AgentOverlayPhase.FAILED ->
                AtomBubbleStatus(resources.getString(R.string.overlay_run_failed), Tone.PAUSED)

            // Done: the pill goes away instead of keeping a stale result on screen.
            AgentOverlayPhase.FINISHED -> Idle

            AgentOverlayPhase.RUNNING -> when (val status = state.status) {
                AgentOverlayStatus.ResultReady,
                AgentOverlayStatus.Stopped,
                AgentOverlayStatus.Finishing,
                -> Idle

                else -> AtomBubbleStatus(status.localizedText(resources), Tone.WORKING)
            }
        }
    }
}

/** The phase the bubble's face and working ring should draw for this status. */
internal val AtomBubbleStatus.phase: AgentOverlayPhase
    get() = when (tone) {
        AtomBubbleStatus.Tone.IDLE -> AgentOverlayPhase.FINISHED
        AtomBubbleStatus.Tone.WORKING -> AgentOverlayPhase.RUNNING
        AtomBubbleStatus.Tone.PAUSED -> AgentOverlayPhase.PAUSED
    }

/**
 * Process-wide feed for the bubble's status pill.
 *
 * [AgentRuntimeService] is the only writer: it publishes the state it already
 * renders in the capsule. Both services live in the main process (neither
 * declares `android:process`), so this is a plain in-memory hand-off with no IPC
 * and no polling.
 */
internal object AtomBubbleStatusFeed {
    private val mutableState = MutableStateFlow(AtomBubbleStatus.Idle)

    val status: StateFlow<AtomBubbleStatus> = mutableState.asStateFlow()

    fun update(status: AtomBubbleStatus) {
        if (mutableState.value != status) mutableState.value = status
    }

    fun clear() = update(AtomBubbleStatus.Idle)
}
