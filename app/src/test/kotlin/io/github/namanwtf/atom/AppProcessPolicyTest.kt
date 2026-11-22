package io.github.namanwtf.atom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `only the main process initializes full Runtime dependencies`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.namanwtf.atom", "io.github.namanwtf.atom"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.namanwtf.atom:voice", "io.github.namanwtf.atom"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.namanwtf.atom:voice_session", "io.github.namanwtf.atom"))
    }
}
