package io.github.namanwtf.atom.agent.virtualdisplay

import android.view.Display
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Macro touch visual feedback inside the virtual display canvas.
 * Coordinates are normalized to [0f, 1f], decoupled from the canvas's current render size, so they can be drawn precisely in both the micro-lens and the gallery stage.
 */
@Immutable
internal sealed interface VirtualTouchEffect {
    val sequence: Long
    val createdAtMs: Long

    @Immutable
    data class Tap(
        override val sequence: Long,
        override val createdAtMs: Long,
        val normalizedX: Float,
        val normalizedY: Float,
        val longPress: Boolean = false,
    ) : VirtualTouchEffect

    @Immutable
    data class Swipe(
        override val sequence: Long,
        override val createdAtMs: Long,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Int = 420,
    ) : VirtualTouchEffect
}

/**
 * Real-time state snapshot of the virtual display, shared and subscribed by the top capsule micro-lens, the gallery stage, and the fullscreen handoff transition.
 */
@Immutable
internal data class VirtualDisplaySnapshot(
    val active: Boolean = false,
    val displayId: Int = Display.DEFAULT_DISPLAY,
    val width: Int = 1080,
    val height: Int = 2400,
    val appName: String = "",
    val packageName: String = "",
    /** Whether the user has enabled manual touch takeover inside the gallery stage. */
    val userControlling: Boolean = false,
    /** Whether the fullscreen handoff zoom animation from the virtual display to the physical main display is in progress. */
    val materializingToMain: Boolean = false,
    /** The most recently captured frame. */
    val frame: ImageBitmap? = null,
    val frameSequence: Long = 0L,
    /** The most recent gesture glow effect that occurred inside the virtual display. */
    val touchEffect: VirtualTouchEffect? = null,
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) {
            (width.toFloat() / height.toFloat()).coerceIn(0.38f, 0.75f)
        } else {
            9f / 19.5f
        }

    companion object {
        val Inactive = VirtualDisplaySnapshot()
    }
}
