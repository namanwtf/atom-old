package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** A character's identity info in conversations and lists; when the avatar path is empty, a monogram avatar is generated from the name. */
@Immutable
internal data class RoleplayChatUi(
    val characterName: String,
    val avatarPath: String?,
    val userName: String,
)

/** The character bound to the current conversation; null for ordinary Agent conversations, and messages/empty state switch to the character style accordingly. */
internal val LocalRoleplayChat = staticCompositionLocalOf<RoleplayChatUi?> { null }

/**
 * Character image decode cache. Character card sources are often PNGs above 1024px; they're downsampled to display size and cached,
 * so list scrolling and message recomposition only hit memory; the key includes the target size, avoiding small avatars reusing large images and wasting memory.
 */
private object CharacterImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(path: String, maxPx: Int): ImageBitmap? = cache.get(key(path, maxPx))

    suspend fun load(path: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key(path, maxPx))?.let { return@withContext it }
        val file = File(path)
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bitmap.asImageBitmap().also { cache.put(key(path, maxPx), it) }
    }

    // Editing the avatar generates a new filename; same-path content never changes, and mtime is included in the key only to guard against external overwrites.
    private fun key(path: String, maxPx: Int) = "$path#${File(path).lastModified()}#$maxPx"
}

/** Asynchronously decodes the character image at display size; on a cache hit the first frame already has the image, with no monogram-placeholder flash. */
@Composable
internal fun rememberCharacterImage(path: String?, displaySize: Dp): ImageBitmap? {
    val maxPx = with(LocalDensity.current) { displaySize.roundToPx() }.coerceIn(32, 1024)
    val image by produceState(initialValue = path?.let { CharacterImageCache.peek(it, maxPx) }, path, maxPx) {
        value = path?.let { CharacterImageCache.load(it, maxPx) }
    }
    return image
}

/**
 * Character avatar: when there's an image, crop to fill; faces are usually in the upper part, so align to the top vertically;
 * without an image, generate a monogram from the name's first character with a stable palette, keeping the same color for a character across all pages.
 */
@Composable
internal fun CharacterAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.Unspecified,
    image: ImageBitmap? = rememberCharacterImage(avatarPath, size),
) {
    // size determines the decode resolution and default square size; when the caller passes fillMaxSize/matchParentSize/explicit dimensions, follow the outer constraints,
    // and when only non-size modifiers are attached, still fall back stably to size, so an internal matchParentSize doesn't collapse the container to 0x0.
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (borderWidth > 0.dp && borderColor != Color.Unspecified) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            CharacterMonogram(name = name, size = size, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun CharacterMonogram(name: String, size: Dp, modifier: Modifier = Modifier.size(size)) {
    val gradient = monogramGradient(name)
    Box(
        modifier = modifier.background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Monogram palette: a low-saturation two-color gradient keeping white text legible in both themes; colors are chosen by hashing the name, so results are stable. */
private val MonogramPalette = listOf(
    listOf(Color(0xFFF58FA8), Color(0xFFC56CD6)),
    listOf(Color(0xFF7FA7F5), Color(0xFF6A6FE0)),
    listOf(Color(0xFF6CC9B4), Color(0xFF3E9FB8)),
    listOf(Color(0xFFF5B36C), Color(0xFFE07A5F)),
    listOf(Color(0xFFA48BEB), Color(0xFF6C63C9)),
    listOf(Color(0xFF8CC97A), Color(0xFF4FA37C)),
)

internal fun monogramGradient(name: String): List<Color> =
    MonogramPalette[Math.floorMod(name.trim().hashCode(), MonogramPalette.size)]
