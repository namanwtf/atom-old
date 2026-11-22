package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.provider.BuiltinProviders
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        AppStrings.resetForTest()
    }

    @Test
    fun supportedLocalesSelectExpectedResourcesAndOthersFallBackToEnglish() {
        assertEquals("Settings", localizedString("en-US", R.string.route_settings))
        assertEquals("Settings", localizedString("zh-CN", R.string.route_settings))
        assertEquals("Settings", localizedString("zh-SG", R.string.route_settings))
        assertEquals("Settings", localizedString("zh-TW", R.string.route_settings))
        assertEquals("Settings", localizedString("zh-HK", R.string.route_settings))
        assertEquals("Yesterday", localizedString("zh-TW", R.string.time_yesterday))
        assertEquals("Settings", localizedString("fr-FR", R.string.route_settings))
        assertEquals("Appearance & Theme", localizedString("en-US", R.string.appearance_title))
        assertEquals("appearance and theme", localizedString("zh-CN", R.string.appearance_title))
        assertEquals("appearance and theme", localizedString("zh-TW", R.string.appearance_title))
        assertEquals("Appearance & Theme", localizedString("fr-FR", R.string.appearance_title))
        assertEquals("1 model", localizedQuantity("en-US", R.plurals.provider_models_count, 1))
        assertEquals("2 models", localizedQuantity("en-US", R.plurals.provider_models_count, 2))
    }

    @Test
    fun supplementalUiResourcesFollowLocaleWhileTechnicalTermsStayEnglish() {
        assertEquals("Current device", localizedString("en-US", R.string.capability_current_device))
        assertEquals("current device", localizedString("zh-CN", R.string.capability_current_device))
        assertEquals("current device", localizedString("zh-TW", R.string.capability_current_device))
        assertEquals("Current device", localizedString("fr-FR", R.string.capability_current_device))
        assertEquals("Eta is running", localizedString("en-US", R.string.execution_title))
        assertEquals("Eta is running", localizedString("zh-CN", R.string.execution_title))
        assertEquals("Eta is running", localizedString("zh-TW", R.string.execution_title))
        assertEquals("Environment configuration", localizedString("en-US", R.string.linux_environment_configuration))
        assertEquals("environment configuration", localizedString("zh-CN", R.string.linux_environment_configuration))
        assertEquals("environment configuration", localizedString("zh-TW", R.string.linux_environment_configuration))
        assertEquals("View description", localizedString("en-US", R.string.ui_view_description))
        assertEquals("view description", localizedString("zh-CN", R.string.ui_view_description))
        assertEquals("view description", localizedString("zh-TW", R.string.ui_view_description))
        assertEquals("Retrying model request", localizedString("en-US", R.string.system_notice_model_retry))
        assertEquals("model request retry", localizedString("zh-CN", R.string.system_notice_model_retry))
        assertEquals("model request retry", localizedString("zh-TW", R.string.system_notice_model_retry))
        assertEquals("Auto-generate titles", localizedString("en-US", R.string.conversation_title_settings_title))
        assertEquals("auto-generate conversation titles", localizedString("zh-CN", R.string.conversation_title_settings_title))
        assertEquals("auto-generate conversation titles", localizedString("zh-TW", R.string.conversation_title_settings_title))
        assertEquals("Floating assistant", localizedString("en-US", R.string.assistant_overlay_settings_title))
        assertEquals("global floating assistant", localizedString("zh-CN", R.string.assistant_overlay_settings_title))
        assertEquals("global floating assistant", localizedString("zh-TW", R.string.assistant_overlay_settings_title))
        assertEquals(
            "1 task is running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 1),
        )
        assertEquals(
            "2 tasks are running. Return to Eta to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 2),
        )
        assertEquals(
            "imported 1 file, the rest could not be read or exceed 32 MiB",
            localizedQuantity("zh-CN", R.plurals.capability_workspace_partial_import, 1),
        )
        assertEquals(
            "imported 2 files, the rest could not be read or exceed 32 MiB",
            localizedQuantity("zh-TW", R.plurals.capability_workspace_partial_import, 2),
        )
        for (locale in listOf("en-US", "zh-CN", "zh-TW", "fr-FR")) {
            assertEquals("Skills", localizedString(locale, R.string.route_skills))
            assertEquals("API Key", localizedString(locale, R.string.speech_api_key))
        }
    }

    @Test
    fun externalizedModuleResourcesAndDynamicResolversFollowLocale() {
        AppStrings.init(context)

        assertEquals("Quick start", localizedString("en-US", R.string.scheduled_section_quick_start))
        assertEquals("quick start", localizedString("zh-CN", R.string.scheduled_section_quick_start))
        assertEquals("quick start", localizedString("zh-TW", R.string.scheduled_section_quick_start))

        assertEquals("Characters", localizedString("en-US", R.string.character_library_title))
        assertEquals("character", localizedString("zh-CN", R.string.character_library_title))
        assertEquals("character", localizedString("zh-TW", R.string.character_library_title))

        assertEquals("Add from models.dev", localizedString("en-US", R.string.catalog_i18n_title))
        assertEquals("add from models.dev", localizedString("zh-CN", R.string.catalog_i18n_title))
        assertEquals("add from models.dev", localizedString("zh-TW", R.string.catalog_i18n_title))

        assertEquals("Search the web", localizedString("en-US", R.string.trace_arg_web_search))
        assertEquals("search the web", localizedString("zh-CN", R.string.trace_arg_web_search))
        assertEquals("search the web", localizedString("zh-TW", R.string.trace_arg_web_search))

        assertEquals("Eta is listening", localizedString("en-US", R.string.voice_i18n_fg_listening))
        assertEquals("Eta is listening", localizedString("zh-CN", R.string.voice_i18n_fg_listening))
        assertEquals("Eta is listening", localizedString("zh-TW", R.string.voice_i18n_fg_listening))

        assertEquals("Background command completed", localizedString("en-US", R.string.execution_bg_cmd_completed))
        assertEquals("background command completed", localizedString("zh-CN", R.string.execution_bg_cmd_completed))
        assertEquals("background command completed", localizedString("zh-TW", R.string.execution_bg_cmd_completed))

        // verify that the dynamic resolver updates in real time when the language changes, rather than being statically frozen in a companion object or default list.
        applyLocale("en-US")
        assertEquals(
            context.getString(R.string.subagent_i18n_explore_summary),
            SubagentProfile.EXPLORE.localizedSummary(),
        )
        assertEquals("Alibaba Bailian", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "Alibaba Bailian"))
        assertEquals("Custom Bailian", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "Custom Bailian"))

        applyLocale("zh-CN")
        assertEquals("read-only exploration and multi-source information retrieval", SubagentProfile.EXPLORE.localizedSummary())
        assertEquals("Alibaba Bailian", BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "Alibaba Bailian"))

        applyLocale("zh-TW")
        assertEquals(
            context.getString(R.string.subagent_i18n_explore_summary),
            SubagentProfile.EXPLORE.localizedSummary(),
        )
        assertEquals(
            context.getString(R.string.provider_i18n_builtin_bailian),
            BuiltinProviders.localizedDisplayName(BuiltinProviders.BAILIAN_ID, "Alibaba Bailian"),
        )
    }

    @Suppress("DEPRECATION")
    private fun applyLocale(languageTag: String) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun localizedString(languageTag: String, resourceId: Int): String {
        applyLocale(languageTag)
        return context.resources.getString(resourceId)
    }

    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String {
        applyLocale(languageTag)
        return context.resources.getQuantityString(resourceId, quantity, quantity)
    }
}
