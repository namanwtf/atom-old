package io.github.namanwtf.atom.agent.overlay

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single source of truth for the orb's pixel geometry.
 *
 * The previous implementation hard-coded a 228dp window and then drew an arc
 * whose right-most satellite and its label fell outside that box, so both were
 * clipped by the window edge. Sizes are now derived from the drawing itself:
 * the window is exactly the bounding box of the orb, its halo, the satellite
 * arc and the labels, and the orb is anchored [orbFromEdge] from the bottom
 * right corner.
 */
internal object AtomOrbSpec {
    /** Core bubble diameter is 2x this, i.e. 48dp. */
    const val ORB_RADIUS_DP = 24f
    const val SATELLITE_RADIUS_DP = 17f
    const val ORBIT_DP = 58f
    const val HALO_FACTOR = 1.75f
    /** Transparent margin between the halo and the window edge. */
    const val EDGE_INSET_DP = 4f
    const val LABEL_GAP_DP = 5f
    const val LABEL_HEIGHT_DP = 14f
    const val LABEL_HALF_WIDTH_DP = 30f
    const val SCREEN_MARGIN_DP = 10f

    /** Arc angles in screen degrees (y down): 200, 240, 280. */
    const val ARC_START_DEGREES = 200f
    const val ARC_STEP_DEGREES = 40f

    fun orbRadius(density: Float): Float = ORB_RADIUS_DP * density
    fun satelliteRadius(density: Float): Float = SATELLITE_RADIUS_DP * density
    fun orbit(density: Float): Float = ORBIT_DP * density
    fun haloRadius(density: Float): Float = orbRadius(density) * HALO_FACTOR
    fun edgeInset(density: Float): Float = EDGE_INSET_DP * density

    /** Distance from the window's bottom/right edge to the orb centre. */
    fun orbFromEdge(density: Float): Float = haloRadius(density) + edgeInset(density)

    fun margin(density: Float): Float = SCREEN_MARGIN_DP * density

    fun satelliteOffset(index: Int, orbitPx: Float): Offset {
        val rad = Math.toRadians((ARC_START_DEGREES + index * ARC_STEP_DEGREES).toDouble())
        return Offset(
            x = (cos(rad) * orbitPx).toFloat(),
            y = (sin(rad) * orbitPx).toFloat(),
        )
    }

    fun orbCenter(width: Float, height: Float, density: Float): Offset {
        val edge = orbFromEdge(density)
        return Offset(width - edge, height - edge)
    }

    /** Expanded bounding box of everything the orb can draw, in dp. */
    fun expandedSizeDp(): Pair<Float, Float> {
        val orbRadius = ORB_RADIUS_DP
        val satelliteRadius = SATELLITE_RADIUS_DP
        val orbit = ORBIT_DP
        val halo = orbRadius * HALO_FACTOR
        val inset = EDGE_INSET_DP

        var left = -halo
        var right = halo
        var top = -halo
        var bottom = halo
        repeat(3) { index ->
            val rad = Math.toRadians((ARC_START_DEGREES + index * ARC_STEP_DEGREES).toDouble())
            val dx = (cos(rad) * orbit).toFloat()
            val dy = (sin(rad) * orbit).toFloat()
            left = minOf(left, dx - satelliteRadius, dx - LABEL_HALF_WIDTH_DP)
            right = maxOf(right, dx + satelliteRadius, dx + LABEL_HALF_WIDTH_DP)
            top = minOf(top, dy - satelliteRadius)
            bottom = maxOf(bottom, dy + satelliteRadius + LABEL_GAP_DP + LABEL_HEIGHT_DP)
        }
        return (right - left + inset * 2f) to (bottom - top + inset * 2f)
    }

    /** Collapsed window: the orb plus its halo, nothing else. */
    fun collapsedSizeDp(): Float = orbFromEdgeDp() * 2f

    private fun orbFromEdgeDp(): Float = ORB_RADIUS_DP * HALO_FACTOR + EDGE_INSET_DP
}

/**
 * Window placement for the orb: sizes in pixels plus safe-area clamping, so the
 * bubble can never be dragged under the status bar, the navigation bar or off
 * the edge of the display.
 */
internal class AtomOrbGeometry(
    val density: Float,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val safeTop: Int,
    val safeBottomInset: Int,
) {
    val orbFromEdge: Int = AtomOrbSpec.orbFromEdge(density).toInt()
    private val margin: Int = AtomOrbSpec.margin(density).toInt()
    private val orbRadius: Int = AtomOrbSpec.orbRadius(density).toInt()

    private val collapsed: Int = (AtomOrbSpec.collapsedSizeDp() * density).toInt()
    private val expanded: Pair<Int, Int> = AtomOrbSpec.expandedSizeDp().let { (w, h) ->
        (w * density).toInt() to (h * density).toInt()
    }

    fun width(expandedWindow: Boolean): Int = if (expandedWindow) expanded.first else collapsed
    fun height(expandedWindow: Boolean): Int = if (expandedWindow) expanded.second else collapsed

    /** Opens near the bottom right, in thumb reach, fully inside the safe area. */
    fun initialPlacement(expandedWindow: Boolean): Pair<Int, Int> {
        val x = screenWidth - width(expandedWindow) - margin
        // Put the orb itself around 72% down, which sits above the gesture bar
        // on tall screens and stays reachable on short ones.
        val y = (screenHeight * 0.72f).toInt() - orbFromEdge
        return clamp(x, y, expandedWindow)
    }

    /**
     * Keeps the whole window on screen: the orb hangs from the window's bottom
     * right corner, so clamping the orb also has to reserve the window's own
     * extent towards the top left.
     */
    fun clamp(x: Int, y: Int, expandedWindow: Boolean): Pair<Int, Int> {
        val w = width(expandedWindow)
        val h = height(expandedWindow)
        val leftOverhang = w - orbFromEdge
        val topOverhang = h - orbFromEdge

        val minX = maxOf(margin + orbRadius, leftOverhang)
        val maxX = maxOf(minX, screenWidth - margin - orbRadius)
        val minY = maxOf(safeTop + margin + orbRadius, safeTop + topOverhang)
        val maxY = maxOf(
            minY,
            screenHeight - safeBottomInset - margin - orbRadius,
        )

        val orbX = (x + w - orbFromEdge).coerceIn(minX, maxX)
        val orbY = (y + h - orbFromEdge).coerceIn(minY, maxY)
        return (orbX - leftOverhang) to (orbY - topOverhang)
    }
}

/** Builds the geometry from the live display metrics, with a resource fallback. */
internal fun WindowManager.atomOrbGeometry(context: Context, density: Float): AtomOrbGeometry {
    val metrics = runCatching { currentWindowMetrics }.getOrNull()
    val screenWidth = metrics?.bounds?.width() ?: 1080
    val screenHeight = metrics?.bounds?.height() ?: 2400

    var safeTop = 0
    var safeBottom = 0
    runCatching {
        val insets = metrics?.windowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        if (insets != null) {
            safeTop = insets.top
            safeBottom = insets.bottom
        }
    }
    // Overlay windows do not always receive insets; fall back to the resource value.
    if (safeBottom <= 0) safeBottom = dimensionPx(context, "navigation_bar_height") ?: (density * 48f).toInt()

    return AtomOrbGeometry(density, screenWidth, screenHeight, safeTop, safeBottom)
}

private fun dimensionPx(context: Context, name: String): Int? {
    val id = context.resources.getIdentifier(name, "dimen", "android")
    if (id == 0) return null
    return runCatching { context.resources.getDimensionPixelSize(id) }
        .getOrNull()
        ?.takeIf { it > 0 }
}
