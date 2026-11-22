package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContextualSearchCallerPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `allows system launcher and Xiaoai to invoke navigation search`() {
        for (name in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES + ModuleConfig.XIAOAI_PACKAGE) {
            install(name, ApplicationInfo.FLAG_SYSTEM)
            assertTrue(allowed(name))
        }
    }

    @Test
    fun `updated system app still works but disabling the switch immediately revokes permission`() {
        install(ModuleConfig.XIAOAI_PACKAGE, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        assertTrue(allowed(ModuleConfig.XIAOAI_PACKAGE))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf(ModuleConfig.XIAOAI_PACKAGE),
            gestureEnabled = false,
        ))
    }

    @Test
    fun `same-name ordinary installs and other system packages cannot gain permission`() {
        for (name in ModuleConfig.XIAOMI_LAUNCHER_PACKAGES + ModuleConfig.XIAOAI_PACKAGE) {
            install(name, 0)
            assertFalse(allowed(name))
        }
        install("com.example.other", ApplicationInfo.FLAG_SYSTEM)
        assertFalse(allowed("com.example.other"))
    }

    @Test
    fun `rejects when the package is missing or the caller list is empty`() {
        assertFalse(allowed(ModuleConfig.XIAOAI_PACKAGE))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(context, emptyArray(), true))
    }

    @Test
    fun `a shared UID still requires a trusted system package among them`() {
        install(ModuleConfig.XIAOAI_PACKAGE, ApplicationInfo.FLAG_SYSTEM)
        assertTrue(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf("com.example.other", ModuleConfig.XIAOAI_PACKAGE),
            true,
        ))
        assertFalse(ContextualSearchCallerPolicy.allowsHyperOsCaller(
            context,
            arrayOf("com.example.other", "com.miui.voiceassist.fake"),
            true,
        ))
    }

    private fun allowed(name: String): Boolean =
        ContextualSearchCallerPolicy.allowsHyperOsCaller(context, arrayOf(name), true)

    private fun install(name: String, flags: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.flags = flags
            }
        })
    }
}
