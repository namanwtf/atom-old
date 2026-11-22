package io.github.mangi.eta.ui.layout

import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val WideScreenMinWidth = 600.dp
private val MaxPageContentWidth = 800.dp
private const val FLOATING_BOTTOM_CORNER_LIFT_RATIO = 0.42f
private val FloatingBottomMinTotalPadding = 12.dp
private val FloatingBottomMaxCornerTotalPadding = 26.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

internal fun isWideScreen(containerWidthPx: Int, density: androidx.compose.ui.unit.Density): Boolean =
    with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/**
 * The list itself stays full-width; only the content is constrained to a centered max width, avoiding scroll dead zones on either side of wide screens.
 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (isWideScreen) {
            ((maxWidth - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(sidePadding)
    }
}

/**
 * Secondary-page content only fills the horizontal screen notch and gesture area; the top is handled by the TopAppBar and the bottom by end-of-page spacing.
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)

/**
 * Computes safe WindowInsets for the bottom-floating capsule (excluding the outer self-held [fixedBottomPadding]):
 * when the gesture hint bar is hidden the system nav bar height is 0, and navigationBarsPadding alone would leave the capsule's
 * bottom-left/right corners hugging the physical glass arc. Read the window's actual
 * adjacent bottom-corner radius via rootWindowInsets.getRoundedCorner (top half of split-screen or a floating window returns 0), lift the bottom edge by 0.42x (capped at 26.dp),
 * take maxOf with the system-bar height to avoid double-stacking when the gesture pill or three-button nav is on, while staying continuous with the outer imePadding
 * consumption.
 */
@Composable
fun rememberFloatingBottomBarInsets(
    baseInsets: WindowInsets = WindowInsets.navigationBars,
    fixedBottomPadding: Dp = 10.dp,
    minTotalPadding: Dp = FloatingBottomMinTotalPadding,
    maxCornerTotalPadding: Dp = FloatingBottomMaxCornerTotalPadding,
): WindowInsets {
    val view = LocalView.current
    var attachVersion by remember(view) {
        mutableIntStateOf(if (view.isAttachedToWindow) 1 else 0)
    }
    DisposableEffect(view) {
        if (view.isAttachedToWindow && attachVersion == 0) {
            attachVersion = 1
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachVersion++
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    // With the gesture bar hidden on full-screen, navigationBars is always 0; unchanged values around attach may not trigger a Snapshot update,
    // so attachVersion ensures the full rootWindowInsets corner data is readable after the first attach.
    return remember(
        view,
        attachVersion,
        baseInsets,
        fixedBottomPadding,
        minTotalPadding,
        maxCornerTotalPadding,
    ) {
        FloatingBottomBarWindowInsets(
            view = view,
            baseInsets = baseInsets,
            fixedBottomPadding = fixedBottomPadding,
            minTotalPadding = minTotalPadding,
            maxCornerTotalPadding = maxCornerTotalPadding,
        )
    }
}

internal fun computeFloatingBottomExtraInsetPx(
    baseBottomInsetPx: Int,
    bottomCornerRadiusPx: Int,
    fixedBottomPaddingPx: Int,
    maxCornerTotalPaddingPx: Int,
    minTotalPaddingPx: Int,
): Int {
    val cornerTargetTotalPx = if (bottomCornerRadiusPx > 0) {
        (bottomCornerRadiusPx * FLOATING_BOTTOM_CORNER_LIFT_RATIO)
            .roundToInt()
            .coerceAtMost(maxCornerTotalPaddingPx)
    } else {
        0
    }
    val targetTotalPx = maxOf(
        baseBottomInsetPx + fixedBottomPaddingPx,
        cornerTargetTotalPx,
        minTotalPaddingPx,
    )
    return (targetTotalPx - fixedBottomPaddingPx).coerceAtLeast(0)
}

private fun readBottomScreenCornerRadiusPx(view: View): Int {
    val rootInsets = view.rootWindowInsets ?: return 0
    val leftRadius = rootInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    val rightRadius = rootInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
    return maxOf(leftRadius, rightRadius)
}

private class FloatingBottomBarWindowInsets(
    private val view: View,
    private val baseInsets: WindowInsets,
    private val fixedBottomPadding: Dp,
    private val minTotalPadding: Dp,
    private val maxCornerTotalPadding: Dp,
) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        baseInsets.getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int =
        baseInsets.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        baseInsets.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int {
        val baseBottomPx = baseInsets.getBottom(density)
        val cornerRadiusPx = readBottomScreenCornerRadiusPx(view)
        val fixedBottomPx = with(density) { fixedBottomPadding.roundToPx() }
        val minTotalPx = with(density) { minTotalPadding.roundToPx() }
        val maxCornerTotalPx = with(density) { maxCornerTotalPadding.roundToPx() }
        return computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = baseBottomPx,
            bottomCornerRadiusPx = cornerRadiusPx,
            fixedBottomPaddingPx = fixedBottomPx,
            maxCornerTotalPaddingPx = maxCornerTotalPx,
            minTotalPaddingPx = minTotalPx,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatingBottomBarWindowInsets) return false
        return view === other.view &&
            baseInsets == other.baseInsets &&
            fixedBottomPadding == other.fixedBottomPadding &&
            minTotalPadding == other.minTotalPadding &&
            maxCornerTotalPadding == other.maxCornerTotalPadding
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(view)
        result = 31 * result + baseInsets.hashCode()
        result = 31 * result + fixedBottomPadding.hashCode()
        result = 31 * result + minTotalPadding.hashCode()
        result = 31 * result + maxCornerTotalPadding.hashCode()
        return result
    }
}
