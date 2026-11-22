package io.github.namanwtf.atom.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.components.AtomTopLayerState
import top.yukonga.miuix.kmp.blur.Backdrop

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalUiStyle = staticCompositionLocalOf { AppearanceUiStyle.Default }
internal val LocalPureBlack = staticCompositionLocalOf { false }
internal val LocalGlassSceneBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalGlassPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalAtomTopLayer = staticCompositionLocalOf<AtomTopLayerState?> { null }
