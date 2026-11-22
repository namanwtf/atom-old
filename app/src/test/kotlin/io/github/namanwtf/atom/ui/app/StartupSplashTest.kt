package io.github.namanwtf.atom.ui.app

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StartupSplashTest {
    @Test
    fun `blocks drawing only when the required first-frame configuration is not ready`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        try {
            val activity = controller.get()
            var ready = false
            var checks = 0
            activity.installStartupSplash {
                checks++
                ready
            }
            val observer = activity.findViewById<View>(android.R.id.content).viewTreeObserver

            assertTrue(observer.dispatchOnPreDraw())
            ready = true
            assertFalse(observer.dispatchOnPreDraw())

            ready = false
            assertFalse(observer.dispatchOnPreDraw())
            assertEquals(2, checks)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `destroying the page during preparation removes the draw interception`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        var checks = 0
        activity.installStartupSplash {
            checks++
            false
        }
        val observer = activity.findViewById<View>(android.R.id.content).viewTreeObserver
        assertTrue(observer.dispatchOnPreDraw())

        controller.destroy()

        assertFalse(observer.dispatchOnPreDraw())
        assertEquals(1, checks)
    }
}
