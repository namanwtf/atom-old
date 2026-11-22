package io.github.namanwtf.atom.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import io.github.namanwtf.atom.agent.voice.AtomAssistantOverlayService
import io.github.namanwtf.atom.ui.app.ArchivedConversationRequest
import io.github.namanwtf.atom.ui.voice.ACTION_SPEECH_SETTINGS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MainActivityLaunchRequestsTest {
    @Test
    fun `reconstruction does not re-execute an already-consumed launch session`() {
        val originalIntent = Intent(AtomAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .putExtra(AtomAssistantOverlayService.EXTRA_CONVERSATION_KEY, "old-conversation")
        val pending = MainActivityLaunchRequests.restore(null, originalIntent)
        assertEquals("old-conversation", pending.assistantConversationKey)
        val consumed = pending.copy(assistantConversationKey = null)

        val restored = MainActivityLaunchRequests.restore(savedState(consumed), originalIntent)

        assertEquals(MainActivityLaunchRequests(), restored)
    }

    @Test
    fun `reconstruction keeps the pending navigation and is not overridden by the original Intent`() {
        val pending = MainActivityLaunchRequests(
            assistantConversationKey = "assistant",
            conversationId = "conversation",
            archivedConversation = ArchivedConversationRequest("source", "archive"),
            speechSettings = true,
            terminal = true,
        )

        val restored = MainActivityLaunchRequests.restore(
            savedState(pending),
            Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "original"),
        )

        assertEquals(pending, restored)
    }

    @Test
    fun `re-entering from the launcher produces no navigation and does not clear the pending request`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pending = MainActivityLaunchRequests(conversationId = "conversation")

        assertEquals(MainActivityLaunchRequests(), MainActivityLaunchRequests().accept(launcher))
        assertEquals(pending, pending.accept(launcher))
    }

    @Test
    fun `a genuinely new Intent still opens the target entry after recovery`() {
        val restored = MainActivityLaunchRequests.restore(savedState(MainActivityLaunchRequests()), null)

        assertEquals(
            MainActivityLaunchRequests(terminal = true),
            restored.accept(Intent(MainActivity.ACTION_OPEN_TERMINAL)),
        )
        assertEquals(
            MainActivityLaunchRequests(speechSettings = true),
            restored.accept(Intent(ACTION_SPEECH_SETTINGS)),
        )
        assertEquals(
            MainActivityLaunchRequests(archivedConversation = ArchivedConversationRequest("source", "archive")),
            restored.accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source")
                    .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, "archive"),
            ),
        )
    }

    @Test
    fun `invalid session parameters produce no navigation`() {
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, " "),
            ),
        )
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source"),
            ),
        )
    }

    @Test
    fun `single-file and multi-file share extracts deduplicated URIs and attached text, and does not replay after being consumed and reconstructed`() {
        val fileUri = Uri.parse("content://provider/files/report.pdf")
        val imageUri = Uri.parse("content://provider/images/photo.png")
        val singleShare = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_TEXT, "help me summarize this document")
        }
        val acceptedSingle = MainActivityLaunchRequests.restore(null, singleShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString()),
                mimeType = "application/pdf",
                text = "help me summarize this document",
            ),
            acceptedSingle.sharedContent,
        )

        val consumed = acceptedSingle.copy(sharedContent = null)
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests.restore(savedState(consumed), singleShare),
        )

        val multiShare = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(fileUri, imageUri))
            clipData = ClipData.newRawUri("shared", fileUri).apply {
                addItem(ClipData.Item(imageUri))
            }
            putExtra(Intent.EXTRA_TEXT, fileUri.toString())
        }
        val acceptedMulti = MainActivityLaunchRequests().accept(multiShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString(), imageUri.toString()),
                mimeType = "*/*",
                text = null,
            ),
            acceptedMulti.sharedContent,
        )
    }

    private fun savedState(requests: MainActivityLaunchRequests): Bundle {
        val state = Bundle().also(requests::saveTo)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
