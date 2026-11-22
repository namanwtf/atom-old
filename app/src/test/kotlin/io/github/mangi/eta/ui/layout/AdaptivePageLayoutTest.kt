package io.github.mangi.eta.ui.layout

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePageLayoutTest {
    @Test
    fun wideScreenDecisionUsesWindowDensity() {
        val widthPx = 1200

        assertTrue(isWideScreen(widthPx, Density(density = 2f)))
        assertFalse(isWideScreen(widthPx, Density(density = 2.2f)))
    }

    @Test
    fun floatingBottomExtraInsetLiftsCapsuleAboveLargeRoundedCornersWhenNavBarHidden() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        // 203px corner radius * 0.42 ≈ 85px (about 24.3dp); after deducting the existing 10dp (35px), an extra 50px is added.
        val extraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )

        assertEquals(50, extraPx)
    }

    @Test
    fun floatingBottomExtraInsetDoesNotStackOnGestureBarOrThreeButtonNavigation() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        // 15dp small bar (53px) + 10dp = 88px > the 85px corner target, so the navigation bar height is reused directly without stacking.
        val gestureExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 53,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(53, gestureExtraPx)

        // three-button navigation (48dp = 168px) keeps the original navigation bar avoidance height unchanged.
        val threeButtonExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 168,
            bottomCornerRadiusPx = 203,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(168, threeButtonExtraPx)
    }

    @Test
    fun floatingBottomExtraInsetClampsExtremeCornerAndFallsBackOnFlatScreen() {
        val density = Density(density = 3.5f)
        val fixedPx = with(density) { 10.dp.roundToPx() }
        val maxCornerPx = with(density) { 26.dp.roundToPx() }
        val minTotalPx = with(density) { 12.dp.roundToPx() }

        val clampedExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 300,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(maxCornerPx - fixedPx, clampedExtraPx)

        val flatScreenExtraPx = computeFloatingBottomExtraInsetPx(
            baseBottomInsetPx = 0,
            bottomCornerRadiusPx = 0,
            fixedBottomPaddingPx = fixedPx,
            maxCornerTotalPaddingPx = maxCornerPx,
            minTotalPaddingPx = minTotalPx,
        )
        assertEquals(minTotalPx - fixedPx, flatScreenExtraPx)
    }
}
