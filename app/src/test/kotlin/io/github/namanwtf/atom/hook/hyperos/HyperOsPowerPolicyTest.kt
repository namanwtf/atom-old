package io.github.namanwtf.atom.hook.hyperos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsPowerPolicyTest {
    @Test
    fun `takes over only power-key assistant actions`() {
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "long_press_power_key"))
        assertTrue(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", "imperceptible_press_power_key"))
        for (action in listOf("show_power_menu", "launch_camera", "sos", "launch_smarthome", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut(action, "long_press_power_key"))
        }
        for (source in listOf("long_press_home_key", "double_click_power_key", "", null)) {
            assertFalse(HyperOsPowerPolicy.isAssistantShortcut("launch_voice_assistant", source))
        }
    }

    @Test
    fun `the dedup gate suppresses duplicate triggers within the window and failure does not deadlock`() {
        val gate = HyperOsPowerDispatchGate(dedupWindowMs = 1_000L)

        // The first trigger must pass even if the boot uptime is smaller than the window.
        assertFalse(gate.shouldSuppressDuplicate(200L))
        // Until the first launch is successfully recorded, subsequent retries or fallback triggers must not be suppressed.
        assertFalse(gate.shouldSuppressDuplicate(400L))

        gate.markLaunched(400L)
        assertTrue(gate.shouldSuppressDuplicate(400L))
        assertTrue(gate.shouldSuppressDuplicate(900L))
        assertTrue(gate.shouldSuppressDuplicate(1_400L))

        // After the dedup window elapses, a new round of presses is allowed to launch.
        assertFalse(gate.shouldSuppressDuplicate(1_401L))
        gate.markLaunched(1_401L)
        assertTrue(gate.shouldSuppressDuplicate(2_000L))

        gate.reset()
        assertFalse(gate.shouldSuppressDuplicate(2_000L))
    }
}
