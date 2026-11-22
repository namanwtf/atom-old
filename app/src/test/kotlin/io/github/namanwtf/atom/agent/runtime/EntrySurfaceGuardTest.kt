package io.github.namanwtf.atom.agent.runtime

import io.github.namanwtf.atom.agent.accessibility.PackageWindowVisibility
import io.github.namanwtf.atom.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EntrySurfaceGuardTest {
    @Test
    fun disabledHandoffDoesNotCreateGuard() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE, dismiss = false),
            logger = NoOpLogger,
        )

        assertNull(guard)
    }

    @Test
    fun etaVoiceGuardExcludesAtomUntilTheVoiceWindowIsDismissed() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            etaVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet()
                true
            },
        )

        assertNotNull(guard)
        assertEquals("io.github.namanwtf.atom", guard?.targetPackageName)
        assertTrue(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(1, dismissCalls.get())
        assertEquals(setOf("io.github.namanwtf.atom"), guard?.consumeScreenshotExcludedPackages())
    }

    @Test
    fun etaVoiceOwnedDismissalCanRetryWithoutSendingBackToTheUnderlyingApp() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            etaVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet() >= 2
            },
        )

        assertFalse(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(2, dismissCalls.get())
    }

    @Test
    fun unknownEntryStillCreatesDismissGuardWithoutGuessingAPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "future_entry", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertNull(guard?.targetPackageName)
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun knownEntryAlreadyGoneMustNotSendBackIntoUnderlyingApp() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "io.github.namanwtf.atom",
                visibility = PackageWindowVisibility.GONE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "io.github.namanwtf.atom",
                visibility = PackageWindowVisibility.VISIBLE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = null,
                visibility = null,
            ),
        )
    }

    @Test
    fun unknownVisibilityDefersWithoutClearingScreenshotExclusion() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.DEFER,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "io.github.namanwtf.atom",
                visibility = PackageWindowVisibility.UNKNOWN,
            ),
        )
    }

    private fun handoff(source: String, dismiss: Boolean) =
        AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = source,
            payload = "{}",
            dismissEntrySurfaceOnForegroundOperation = dismiss,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
