package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Adaptive OS4 glass top bar: fixed small title on wide screens, collapsible large title on phones;
 * uses OS4 progressive blur directly when `RuntimeShader` is supported, otherwise falls back to a solid background; when [isContentScrolled] is passed, it stays strictly synced to list position.
 */
@Composable
fun AdaptiveTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    progressiveBlur: Boolean = isRuntimeShaderSupported() && LocalAppearanceSettings.current.progressiveBlurEnabled,
    expandable: Boolean = !rememberIsWideScreen(),
    subtitle: String = "",
    scrollBehavior: ScrollBehavior? = null,
    isContentScrolled: Boolean? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val bandBrush = remember(surfaceColor) { GlassTopAppBarDefaults.bandBrush(surfaceColor) }
    if (isContentScrolled != null) {
        GlassTopAppBar(
            title = title,
            isContentScrolled = isContentScrolled,
            modifier = modifier,
            expandable = expandable,
            progressiveBlur = progressiveBlur,
            backdrop = backdrop,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = bandBrush,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        GlassTopAppBar(
            title = title,
            modifier = modifier,
            expandable = expandable,
            progressiveBlur = progressiveBlur,
            backdrop = backdrop,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = bandBrush,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    }
}
