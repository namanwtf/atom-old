package io.github.mangi.eta.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Resolves string resources by the current app language for non-Compose layers (Runtime, Formatter, Repository, Store, etc.).
 *
 * - Android runtime and Robolectric tests query Resources live through the Application Context bound by [init],
 *   following Per-App Language changes and caching no resolved strings.
 * - Pure JVM unit tests without an initialized Android Context fall back to the provided [fallback] template,
 *   keeping the existing pure JVM unit tests' behavioral contract unchanged.
 * - Compose UI layers should use `stringResource` / `pluralStringResource` directly and not call this object while bypassing the composition-time configuration dependency.
 */
internal object AppStrings {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    internal fun resetForTest() {
        appContext = null
    }

    fun get(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val ctx = appContext
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
            }.getOrElse { formatFallback(fallback, *args) }
        }
        return formatFallback(fallback, *args)
    }

    fun quantity(
        @PluralsRes resId: Int,
        quantity: Int,
        fallbackOne: String,
        fallbackOther: String,
        vararg args: Any,
    ): String {
        val ctx = appContext
        if (ctx != null) {
            return runCatching {
                if (args.isEmpty()) {
                    ctx.resources.getQuantityString(resId, quantity)
                } else {
                    ctx.resources.getQuantityString(resId, quantity, *args)
                }
            }.getOrElse {
                val template = if (quantity == 1) fallbackOne else fallbackOther
                formatFallback(template, *args)
            }
        }
        val template = if (quantity == 1) fallbackOne else fallbackOther
        return formatFallback(template, *args)
    }

    private fun formatFallback(template: String, vararg args: Any): String {
        if (args.isEmpty()) return template
        return runCatching { String.format(Locale.US, template, *args) }.getOrDefault(template)
    }
}
