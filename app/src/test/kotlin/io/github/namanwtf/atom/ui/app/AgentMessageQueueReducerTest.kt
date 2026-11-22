package io.github.namanwtf.atom.ui.app

import io.github.namanwtf.atom.agent.model.AgentFileReference
import io.github.namanwtf.atom.agent.model.AgentFileReferenceKind
import io.github.namanwtf.atom.ui.model.AgentChatUiState
import io.github.namanwtf.atom.ui.model.PendingFileReferenceUi
import io.github.namanwtf.atom.ui.model.PendingImageUi
import io.github.namanwtf.atom.ui.model.QueuedMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageQueueReducerTest {

    @Test
    fun enqueueAppendsInOrderAndClearsCurrentDraft() {
        val image = PendingImageUi(id = "img-1", uri = "file:///1.jpg", dataUrl = "data:image/jpeg;base64,1", mimeType = "image/jpeg")
        val initial = baseState(
            isStreaming = true,
            input = "draft",
            images = listOf(image),
        )

        val firstState = AgentMessageQueueReducer.enqueue(
            state = initial,
            item = QueuedMessageUi(id = "q-1", content = "first item", pendingImages = listOf(image)),
        )
        val secondState = AgentMessageQueueReducer.enqueue(
            state = firstState,
            item = QueuedMessageUi(id = "q-2", content = "second item"),
        )

        assertEquals("", secondState.input)
        assertTrue(secondState.pendingImages.isEmpty())
        assertEquals(listOf("q-1", "q-2"), secondState.queuedMessages.map { it.id })
    }

    @Test
    fun immediateInterruptPreemptsQueueHeadAndDemotesPreviousInterrupt() {
        val state = baseState(isStreaming = true).let {
            val s1 = AgentMessageQueueReducer.enqueue(it, QueuedMessageUi(id = "q-1", content = "ordinary queued"))
            AgentMessageQueueReducer.enqueue(s1, QueuedMessageUi(id = "q-2", content = "first interruption", immediateInterrupt = true))
        }
        assertEquals(listOf("q-2", "q-1"), state.queuedMessages.map { it.id })
        assertTrue(state.queuedMessages[0].immediateInterrupt)

        val next = AgentMessageQueueReducer.enqueue(
            state = state,
            item = QueuedMessageUi(id = "q-3", content = "second interruption", immediateInterrupt = true),
        )
        assertEquals(listOf("q-3", "q-2", "q-1"), next.queuedMessages.map { it.id })
        assertTrue(next.queuedMessages[0].immediateInterrupt)
        assertFalse(next.queuedMessages[1].immediateInterrupt)
    }

    @Test
    fun promoteToInterruptMovesItemToFrontWhenStreamingAndPopsImmediatelyWhenIdle() {
        val queued = listOf(
            QueuedMessageUi(id = "q-1", content = "first item"),
            QueuedMessageUi(id = "q-2", content = "second item"),
        )
        val streamingState = baseState(isStreaming = true).copy(queuedMessages = queued)
        val (promotedStreaming, promotedItem) = AgentMessageQueueReducer.promoteToInterrupt(streamingState, "q-2")
        assertNotNull(promotedItem)
        assertEquals(listOf("q-2", "q-1"), promotedStreaming.queuedMessages.map { it.id })
        assertTrue(promotedStreaming.queuedMessages.first().immediateInterrupt)

        val idleState = baseState(isStreaming = false).copy(queuedMessages = queued)
        val (afterIdlePop, poppedItem) = AgentMessageQueueReducer.promoteToInterrupt(idleState, "q-2")
        assertNotNull(poppedItem)
        assertFalse(poppedItem!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterIdlePop.queuedMessages.map { it.id })
    }

    @Test
    fun recallToDraftMergesWithUnsentTextAndAttachmentsAndBumpsSyncVersion() {
        val existingImage = PendingImageUi(id = "img-1", uri = "u1", dataUrl = "d1", mimeType = "image/png")
        val queuedImage = PendingImageUi(id = "img-2", uri = "u2", dataUrl = "d2", mimeType = "image/png")
        val queuedFile = PendingFileReferenceUi(
            id = "file-1",
            reference = AgentFileReference(
                displayName = "doc.txt",
                absolutePath = "/sdcard/doc.txt",
                kind = AgentFileReferenceKind.File,
            ),
        )
        val state = baseState(
            isStreaming = true,
            input = "",
            images = listOf(existingImage),
        ).copy(
            queuedMessages = listOf(
                QueuedMessageUi(
                    id = "q-1",
                    content = "recalled content",
                    pendingImages = listOf(existingImage, queuedImage),
                    pendingFileReferences = listOf(queuedFile),
                ),
                QueuedMessageUi(id = "q-2", content = "retained content"),
            ),
        )

        val recalled = AgentMessageQueueReducer.recallToDraft(
            state = state,
            currentInputText = "the half sentence being typed",
            id = "q-1",
        )
        assertNotNull(recalled)
        assertEquals("the half sentence being typed\nrecalled content", recalled!!.input)
        assertEquals(listOf("img-1", "img-2"), recalled.pendingImages.map { it.id })
        assertEquals(listOf("file-1"), recalled.pendingFileReferences.map { it.id })
        assertEquals(listOf("q-2"), recalled.queuedMessages.map { it.id })
        assertEquals(state.inputSyncVersion + 1, recalled.inputSyncVersion)
    }

    @Test
    fun pollNextToSendAfterRunRespectsUserStopExceptForImmediateInterrupt() {
        val normalQueue = listOf(
            QueuedMessageUi(id = "q-1", content = "first item"),
            QueuedMessageUi(id = "q-2", content = "second item"),
        )
        val state = baseState(isStreaming = false).copy(queuedMessages = normalQueue)

        // user actively taps the stop button: the queue is retained and not sent automatically
        val (stoppedState, stoppedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = true,
        )
        assertNull(stoppedNext)
        assertEquals(2, stoppedState.queuedMessages.size)

        // natural completion: automatically pop the first queued item
        val (completedState, completedNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = state,
            stoppedByUser = false,
        )
        assertEquals("q-1", completedNext?.id)
        assertEquals(listOf("q-2"), completedState.queuedMessages.map { it.id })

        // interrupt-triggered abort: even if the run ends due to cancellation, the head interrupt item is still immediately popped and sent
        val interruptState = state.copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-interrupt", content = "interrupt immediately", immediateInterrupt = true),
                QueuedMessageUi(id = "q-1", content = "first item"),
            ),
        )
        val (afterInterruptState, interruptNext) = AgentMessageQueueReducer.pollNextToSendAfterRun(
            state = interruptState,
            stoppedByUser = true,
        )
        assertEquals("q-interrupt", interruptNext?.id)
        assertFalse(interruptNext!!.immediateInterrupt)
        assertEquals(listOf("q-1"), afterInterruptState.queuedMessages.map { it.id })
    }

    @Test
    fun promoteToQueueHeadEnqueueAtHeadAndClearInterruptFlagPreserveQueueWithoutInterrupt() {
        val initial = baseState(isStreaming = true).copy(
            queuedMessages = listOf(
                QueuedMessageUi(id = "q-1", content = "first item"),
                QueuedMessageUi(id = "q-2", content = "second item", immediateInterrupt = true),
            ),
        )
        val cleared = AgentMessageQueueReducer.clearInterruptFlag(initial, "q-2")
        assertFalse(cleared.queuedMessages[1].immediateInterrupt)

        val promotedHead = AgentMessageQueueReducer.promoteToQueueHead(cleared, "q-2")
        assertEquals(listOf("q-2", "q-1"), promotedHead.queuedMessages.map { it.id })
        assertFalse(promotedHead.queuedMessages.any { it.immediateInterrupt })

        val enqueuedHead = AgentMessageQueueReducer.enqueueAtHead(
            state = promotedHead.copy(input = "interrupt with image"),
            item = QueuedMessageUi(id = "q-3", content = "interrupt with image", immediateInterrupt = true),
        )
        assertEquals("", enqueuedHead.input)
        assertEquals(listOf("q-3", "q-2", "q-1"), enqueuedHead.queuedMessages.map { it.id })
        assertFalse(enqueuedHead.queuedMessages.first().immediateInterrupt)
    }

    private fun baseState(
        isStreaming: Boolean,
        input: String = "",
        images: List<PendingImageUi> = emptyList(),
    ) = AgentChatUiState(
        messages = emptyList(),
        input = input,
        isStreaming = isStreaming,
        thinkingEnabled = true,
        pendingImages = images,
    )
}
