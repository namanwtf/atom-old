package io.github.namanwtf.atom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.LocalUiStyle
import io.github.namanwtf.atom.ui.components.coui.CouiIcon
import io.github.namanwtf.atom.ui.components.coui.CouiPageDefaults
import io.github.namanwtf.atom.ui.components.coui.CouiText
import io.github.namanwtf.atom.ui.components.coui.CouiTextKind
import io.github.suqi8.coui.kmp.squircle.SquircleShape as CouiShape
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * List empty state: with a description, the title uses the body color as primary info and the description steps down to secondary; with a single line, the whole thing is softened,
 * so a lone "nothing here" doesn't look more prominent than real content on the page.
 */
@Composable
internal fun ListEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.let {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CouiShape(16.dp, 2f))
                            .background(colors.card),
                        contentAlignment = Alignment.Center,
                    ) {
                        CouiIcon(
                            imageVector = it,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.summary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                CouiText(
                    text = title,
                    kind = if (summary != null) CouiTextKind.ItemTitle else CouiTextKind.Search,
                    color = if (summary != null) colors.title else colors.summary,
                    textAlign = TextAlign.Center,
                )
                summary?.let {
                    CouiText(
                        text = it,
                        kind = CouiTextKind.GroupFooter,
                        color = colors.summary,
                        textAlign = TextAlign.Center,
                    )
                }
                action?.let {
                    Spacer(Modifier.height(10.dp))
                    it()
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            val colors = MiuixTheme.colorScheme
            Column(
                modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.let {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .squircleBackground(colors.surfaceContainerHigh, 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = title,
                    style = if (summary != null) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2,
                    fontWeight = if (summary != null) FontWeight.Medium else null,
                    color = if (summary != null) colors.onSurface else colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                summary?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
                action?.let {
                    Spacer(Modifier.height(10.dp))
                    it()
                }
            }
        }
    }
}
