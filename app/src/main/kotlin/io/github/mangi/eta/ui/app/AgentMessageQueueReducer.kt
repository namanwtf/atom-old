package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi

/**
 * Pure state reducer for in-conversation queued send (Queue) and interjection send (Steer).
 *
 * - Queued sends append to the tail and auto-send in FIFO order after the current turn ends naturally.
 * - Interjection sends prefer to inject a steering supplement into the currently running Run (without interrupting tools/requests, keeping cache);
 *   during IPC submission they temporarily hold the queue head with [QueuedMessageUi.immediateInterrupt] and are removed once accepted,
 *   and if the current Run has ended or can't accept them, they fall back to the queue head to send after wrap-up.
 * - When the user taps stop, keep the queue without auto-sending, so follow-up queued commands don't keep running when the user wants to stop.
 */
internal object AgentMessageQueueReducer {

    fun enqueue(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val nextQueue = if (item.immediateInterrupt) {
            listOf(item) + state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        } else {
            state.queuedMessages + item
        }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = nextQueue,
        )
    }

    /** Moves the entry to the queue head and clears the interjection flag, used to send it first after the current Run ends when runtime Steer isn't supported (e.g. with images). */
    fun promoteToQueueHead(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return state.copy(
            queuedMessages = listOf(target.copy(immediateInterrupt = false)) + rest,
        )
    }

    /** Moves a just-enqueued entry that can't Steer immediately to the queue head (clearing the draft, not interrupting the current run). */
    fun enqueueAtHead(
        state: AgentChatUiState,
        item: QueuedMessageUi,
    ): AgentChatUiState {
        val normalized = item.copy(immediateInterrupt = false)
        val rest = state.queuedMessages.map { it.copy(immediateInterrupt = false) }
        return state.copy(
            input = "",
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messageEdit = null,
            queuedMessages = listOf(normalized) + rest,
        )
    }

    /**
     * Promotes a specified queued message into an immediate interjection.
     *
     * - If still streaming (not compacting): move it to the queue head and mark [QueuedMessageUi.immediateInterrupt], auto-sending after the current run is cancelled.
     * - If already idle: pop it from the queue and return, letting the caller start a new run immediately.
     */
    fun promoteToInterrupt(
        state: AgentChatUiState,
        id: String,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return state to null
        val rest = state.queuedMessages
            .filterNot { it.id == id }
            .map { it.copy(immediateInterrupt = false) }
        return if (state.isStreaming && !state.isCompacting) {
            val promoted = target.copy(immediateInterrupt = true)
            state.copy(queuedMessages = listOf(promoted) + rest) to promoted
        } else {
            state.copy(queuedMessages = rest) to target.copy(immediateInterrupt = false)
        }
    }

    /**
     * Recalls a queued message to the input box for editing; if the box already has an unsent draft, keep and merge it rather than overwriting what the user is typing.
     */
    fun recallToDraft(
        state: AgentChatUiState,
        currentInputText: String,
        id: String,
    ): AgentChatUiState? {
        val target = state.queuedMessages.firstOrNull { it.id == id } ?: return null
        val rest = state.queuedMessages.filterNot { it.id == id }
        val mergedInput = when {
            currentInputText.isBlank() -> target.content
            target.content.isBlank() -> currentInputText
            else -> currentInputText.trimEnd() + "\n" + target.content
        }
        val mergedImages = (state.pendingImages + target.pendingImages).distinctBy { it.id }
        val mergedFiles = (state.pendingFileReferences + target.pendingFileReferences).distinctBy { it.id }
        return state.copy(
            input = mergedInput,
            pendingImages = mergedImages,
            pendingFileReferences = mergedFiles,
            queuedMessages = rest,
            inputSyncVersion = state.inputSyncVersion + 1,
            focusInputOnSync = true,
        )
    }

    fun remove(
        state: AgentChatUiState,
        id: String,
    ): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.filterNot { it.id == id },
        )
    }

    fun clearInterruptFlags(state: AgentChatUiState): AgentChatUiState {
        if (state.queuedMessages.none { it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { it.copy(immediateInterrupt = false) },
        )
    }

    fun clearInterruptFlag(state: AgentChatUiState, id: String): AgentChatUiState {
        if (state.queuedMessages.none { it.id == id && it.immediateInterrupt }) return state
        return state.copy(
            queuedMessages = state.queuedMessages.map { item ->
                if (item.id == id && item.immediateInterrupt) item.copy(immediateInterrupt = false) else item
            },
        )
    }

    /**
     * After the current run wraps up, decide whether to auto-pop the next queued message:
     * - If the queue head is [QueuedMessageUi.immediateInterrupt], pop and run it immediately regardless of whether this run ended by cancellation.
     * - When the user taps stop ([stoppedByUser]), keep the remaining queue and don't auto-send.
     * - Otherwise (normal end), pop the first queue-head message and auto-send.
     */
    fun pollNextToSendAfterRun(
        state: AgentChatUiState,
        stoppedByUser: Boolean,
    ): Pair<AgentChatUiState, QueuedMessageUi?> {
        val first = state.queuedMessages.firstOrNull() ?: return state to null
        if (!first.immediateInterrupt && stoppedByUser) {
            return clearInterruptFlags(state) to null
        }
        val remaining = state.queuedMessages.drop(1)
        return state.copy(queuedMessages = remaining) to first.copy(immediateInterrupt = false)
    }
}
