package io.github.namanwtf.atom.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.namanwtf.atom.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Unified OS4 glass back button for secondary pages, keeping icon, semantics, RTL direction, and hit area consistent.
 */
@Composable
fun MiuixBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
) {
    GlassIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = stringResource(R.string.action_back),
            modifier = Modifier.size(28.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}
