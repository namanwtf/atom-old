package io.github.namanwtf.atom.agent.voice

import android.app.Application
import android.content.ComponentName
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import io.github.namanwtf.atom.core.AssistantContextFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AtomAssistantScreenContextTest {
    @Test
    fun replacingEntryClosesOldContextAndOldOwnerCannotClearNewEntry() = runBlocking {
        val old = AtomAssistantScreenContexts.begin(0, listOf(ComponentName("old.app", "OldActivity")))
        val current = AtomAssistantScreenContexts.begin(0, listOf(ComponentName("current.app", "CurrentActivity")))
        try {
            AtomAssistantScreenContexts.release(old.id)
            assertSame(current, AtomAssistantScreenContexts.find(current.id))
            assertNull(AtomAssistantScreenContexts.find(old.id))
            assertEquals("", old.snapshot().text)
            assertTrue(current.snapshot().text.contains("current.app"))
            assertFalse(current.snapshot().text.contains("old.app"))
        } finally {
            AtomAssistantScreenContexts.release(current.id)
        }
    }

    @Test
    fun deniedScreenshotStillProvidesApplicationIdentity() = runBlocking {
        val capture = AtomAssistantScreenContext(
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
            listOf(ComponentName("current.app", "CurrentActivity")),
        )
        try {
            capture.acceptScreenshot(null)
            val snapshot = capture.snapshot()
            assertNull(snapshot.image)
            assertTrue(snapshot.text.contains("current.app"))
            assertTrue(snapshot.text.contains("no usable screenshot provided"))
        } finally { capture.close() }
    }

    @Test
    fun closedEntryRejectsLateScreenshotAndReleasesContext() = runBlocking {
        val capture = AtomAssistantScreenContext(0, listOf(ComponentName("closed.app", "Activity")))
        capture.close()
        capture.acceptScreenshot(null)
        assertEquals(AtomAssistantScreenContext.Snapshot(), capture.snapshot())
    }

    @Test
    fun flagsRequestBothChannelsAndAndroid17ScreenContent() {
        val older = AssistantContextFlags.forSdk(34)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_ASSIST != 0)
        assertTrue(older and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0)
        assertEquals(0, older and VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT)
        assertTrue(AssistantContextFlags.forSdk(37) and VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT != 0)
    }

    @Test
    fun passwordNodesAreExcludedWithoutExcludingOrdinaryTextFields() {
        assertTrue(AtomAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(AtomAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(AtomAssistContentReader.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(AtomAssistContentReader.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }

    @Test
    fun freshVisibleEntryCoalescesDuplicateShowsUntilDismissedOrExpired() {
        assertTrue(
            AtomAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 3_500L,
            ),
        )
        assertFalse(
            AtomAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = true,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            AtomAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = true,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_500L,
            ),
        )
        assertFalse(
            AtomAssistantOverlayService.shouldCoalesceFreshEntry(
                hasWindow = true,
                isDetaching = false,
                isDismissing = false,
                hiddenForForegroundOperation = false,
                handoffInProgress = false,
                handoffExitRequested = false,
                hasActiveRun = false,
                hasMessages = false,
                entryShownUptimeMs = 1_000L,
                nowUptimeMs = 1_000L + AtomAssistantOverlayService.FRESH_ENTRY_COALESCE_WINDOW_MS + 1L,
            ),
        )
    }
}
