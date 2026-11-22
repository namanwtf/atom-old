package io.github.namanwtf.atom.core

import android.content.Context
import android.content.res.Configuration
import io.github.namanwtf.atom.R
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
class AppStringsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        AppStrings.resetForTest()
    }

    @Test
    fun fallsBackToTemplateWhenContextIsNotInitialized() {
        AppStrings.resetForTest()
        assertEquals(
            "field model",
            AppStrings.get(R.string.provider_param_field, "field %1\$s", "model"),
        )
        assertEquals(
            "1 item(s)",
            AppStrings.quantity(R.plurals.provider_models_count, 1, "%1\$d item(s)", "%1\$d item(s)", 1),
        )
        assertEquals(
            "3 item(s)",
            AppStrings.quantity(R.plurals.provider_models_count, 3, "%1\$d item(s)", "%1\$d item(s)", 3),
        )
    }

    @Test
    fun resolvesUpdatedLocaleDynamicallyWithoutCachingOldLanguage() {
        AppStrings.init(context)

        applyLocale("en-US")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Invalid Vertex AI endpoint URL."),
        )
        assertEquals(
            "1 model",
            AppStrings.quantity(R.plurals.provider_models_count, 1, "%1\$d model(s)", "%1\$d model(s)", 1),
        )
        assertEquals(
            "2 models",
            AppStrings.quantity(R.plurals.provider_models_count, 2, "%1\$d model(s)", "%1\$d model(s)", 2),
        )

        applyLocale("zh-CN")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Invalid Vertex AI endpoint URL."),
        )

        applyLocale("zh-TW")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Invalid Vertex AI endpoint URL."),
        )

        applyLocale("fr-FR")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Invalid Vertex AI endpoint URL."),
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
}
