package io.github.namanwtf.atom.ui.components.coui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import io.github.namanwtf.atom.ui.layout.TopBarBackdropLayout
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.TopAppBarGlow
import io.github.suqi8.coui.kmp.blur.TopAppBarGlowDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
internal fun CouiFixedTopBar(
    backdrop: LayerBackdrop?,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TopBarBackdropLayout(
        modifier = modifier.fillMaxWidth(),
        backgroundHeight = { height ->
            if (backdrop == null) height else height + TopAppBarGlowDefaults.ExtraHeight.roundToPx()
        },
        backgroundModifier = if (backdrop == null) Modifier.background(backgroundColor) else Modifier,
        background = {
            if (backdrop != null) CouiFixedTopBarGlow(backdrop, backgroundColor)
        },
    ) {
        ProvideCouiMaterial(backdrop, content)
    }
}

@Composable
internal fun BoxScope.CouiFixedTopBarGlow(backdrop: LayerBackdrop, backgroundColor: Color) {
    // The library signals via a scroll threshold that the glow is fully visible; the fixed top bar uses that state directly.
    val fullyVisibleOffset = with(LocalDensity.current) { TopAppBarGlowDefaults.AlphaScrollThreshold.toPx() }
    TopAppBarGlow(
        backdrop = backdrop,
        scrollOffsetPx = fullyVisibleOffset,
        isDark = COUITheme.isDark,
        baseColor = backgroundColor,
        enabled = true,
    )
}
