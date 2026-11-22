package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface MathLayoutEngine {
    fun layout(latex: String, displayMode: Boolean): DisplayList?
}

/**
 * Bridge to RaTeX native typesetting.
 *
 * Pass a fully transparent color as the default color sentinel (`a == 0f`), so the parsed [DisplayList] depends only on the formula text and
 * `displayMode`, cacheable across light/dark themes and body/heading/quote/thinking tones; before drawing, bind the
 * sentinel color to the current context ink color, while preserving colors explicitly set via `\color` inside the formula.
 */
internal object RaTeXMathLayoutEngine : MathLayoutEngine {
    private const val SENTINEL_TRANSPARENT_ARGB = 0
    private val nativeUnavailable = AtomicBoolean(false)

    override fun layout(latex: String, displayMode: Boolean): DisplayList? {
        val normalized = latex.trim()
        if (normalized.isEmpty() || '\u0000' in normalized || nativeUnavailable.get()) return null
        return try {
            RaTeXEngine.parseBlocking(
                latex = normalized,
                displayMode = displayMode,
                color = SENTINEL_TRANSPARENT_ARGB,
            )
        } catch (_: RaTeXException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (error: LinkageError) {
            if (nativeUnavailable.compareAndSet(false, true)) {
                AndroidAgentLogger.warn(
                    "Eta markdown math native engine unavailable: type=${error.safeLogType()}"
                )
            }
            null
        }
    }
}

internal val markdownMathLayoutCache = MarkdownMathLayoutCache()

/**
 * Bounded in-process formula layout cache.
 *
 * When streaming a long paragraph, inline formulas already closed in the first half repeatedly pass through inline construction as trailing text appends;
 * caching both success and failure so the same formula invokes native parsing only once and returns the same [DisplayList] instance for composition to skip.
 */
internal class MarkdownMathLayoutCache(
    private val engine: MathLayoutEngine = RaTeXMathLayoutEngine,
    private val maxEntries: Int = 256,
    private val maxCacheableChars: Int = 4_096,
) {
    init {
        require(maxEntries > 0 && maxCacheableChars > 0)
    }

    private data class Key(val latex: String, val displayMode: Boolean)

    private sealed interface Entry {
        data class Hit(val displayList: DisplayList) : Entry
        data object Miss : Entry
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<Key, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > maxEntries
    }

    fun layout(latex: String, displayMode: Boolean): DisplayList? {
        val trimmed = latex.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxCacheableChars) {
            return engine.layout(trimmed, displayMode)
        }
        val key = Key(trimmed, displayMode)
        synchronized(lock) {
            when (val cached = map[key]) {
                is Entry.Hit -> return cached.displayList
                Entry.Miss -> return null
                null -> Unit
            }
        }
        val computed = engine.layout(trimmed, displayMode)
        val entry = if (computed != null) Entry.Hit(computed) else Entry.Miss
        synchronized(lock) {
            map[key] = entry
        }
        return computed
    }
}

internal fun DisplayList.bindDefaultColor(defaultColor: Color): DisplayList {
    if (items.isEmpty()) return this
    val fallback = RaTeXColor(
        r = defaultColor.red,
        g = defaultColor.green,
        b = defaultColor.blue,
        a = defaultColor.alpha,
    )
    val mapped = items.map { item ->
        when (item) {
            is DisplayItem.GlyphPath -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Line -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Rect -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Path -> item.copy(color = item.color.resolveWith(fallback))
        }
    }
    return copy(items = mapped)
}

private fun RaTeXColor.resolveWith(fallback: RaTeXColor): RaTeXColor = when {
    a <= 0f -> fallback
    fallback.a >= 0.999f -> this
    else -> copy(a = (a * fallback.a).coerceIn(0f, 1f))
}

internal object RaTeXFontRegistry {
    private const val ASSET_FONT_DIR = "fonts"
    private val SUPPLEMENTAL_FONTS = listOf(
        "Caligraphic-Bold" to "KaTeX_Caligraphic-Bold.ttf",
    )

    private val loaded = AtomicBoolean(false)
    private val loadLock = Any()
    private val supplementalCache = ConcurrentHashMap<String, Typeface>()
    private val readyState = mutableStateOf(false)

    @Composable
    fun rememberFontsReady(): Boolean {
        val ready by readyState
        if (ready) return true
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(appContext) {
            withContext(Dispatchers.IO) {
                ensureLoadedBlocking(appContext)
            }
        }
        return ready
    }

    fun ensureLoadedBlocking(context: Context) {
        if (loaded.get()) return
        synchronized(loadLock) {
            if (loaded.get()) return
            val appContext = context.applicationContext ?: context
            RaTeXFontLoader.ensureLoaded(appContext, ASSET_FONT_DIR)
            for ((fontId, fileName) in SUPPLEMENTAL_FONTS) {
                runCatching {
                    Typeface.createFromAsset(appContext.assets, "$ASSET_FONT_DIR/$fileName")
                }.getOrNull()?.let { typeface ->
                    supplementalCache[fontId] = typeface
                }
            }
            loaded.set(true)
            readyState.value = true
        }
    }

    fun resolveTypeface(fontId: String): Typeface? =
        RaTeXFontLoader.getTypeface(fontId)
            ?: supplementalCache[fontId]
            ?: Typeface.DEFAULT
}
