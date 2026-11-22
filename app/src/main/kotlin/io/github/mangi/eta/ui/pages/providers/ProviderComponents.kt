package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiPreferenceGroup(
                modifier = modifier,
                title = title,
            ) {
                item { androidx.compose.foundation.layout.Column(content = content) }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Column(modifier = modifier) {
                if (title != null) {
                    SmallTitle(title)
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/** Vendor brand primary-color icon; the asset already includes a background and safe area suitable for circular cropping. */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Box(
                modifier = modifier.size(CouiPageDefaults.iconSize),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .padding(end = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** Known vendors use their brand icon; unknown sources keep using the generic icon by protocol type. */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    val icon = when (provider) {
        is CustomProviderSetting -> Icons.Rounded.Dns
        else -> Icons.Rounded.Language
    }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Box(
                modifier = modifier.size(CouiPageDefaults.iconSize),
                contentAlignment = Alignment.Center,
            ) {
                CouiIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = colors.summary,
                )
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Box(
                modifier = modifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

internal enum class TagChipTone { Normal, Emphasized }

/** Small capsule tag for capability labels and status markers. */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val uiStyle = LocalUiStyle.current
    val background: Color
    val foreground: Color
    val weight: FontWeight
    if (uiStyle == AppearanceUiStyle.COUI) {
        val colors = CouiPageDefaults.themeColors()
        val dark = COUITheme.isDark
        when (tone) {
            TagChipTone.Normal -> {
                background = COUITheme.colorScheme.container8
                foreground = colors.summary
                weight = FontWeight.Normal
            }
            TagChipTone.Emphasized -> {
                background = COUITheme.colorScheme.secondary
                foreground = colors.accent
                weight = FontWeight.Medium
            }
        }
    } else {
        when (tone) {
            TagChipTone.Normal -> {
                background = MiuixTheme.colorScheme.secondaryContainer
                foreground = MiuixTheme.colorScheme.onSurfaceVariantSummary
                weight = FontWeight.Normal
            }
            TagChipTone.Emphasized -> {
                background = MiuixTheme.colorScheme.tertiaryContainer
                foreground = MiuixTheme.colorScheme.onTertiaryContainer
                weight = FontWeight.Medium
            }
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = weight,
        color = foreground,
        modifier = Modifier
            .squircleSurface(color = background, cornerRadius = 6.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
