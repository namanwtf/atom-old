package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import io.github.mangi.eta.ui.app.LocalEtaTopLayer

/**
 * Full-window top-layer host state: promotes in-page overlays to the root host so they aren't clipped or covered by the top bar, nav container, or sidebar.
 */
@Stable
internal class EtaTopLayerState {
    val entries = mutableStateListOf<EtaTopLayerEntry>()
}

@Stable
internal class EtaTopLayerEntry {
    var content by mutableStateOf<@Composable BoxScope.() -> Unit>({})
}

/**
 * Promotes [content] to render in the nearest [EtaTopLayerHost]; in environments without a top-layer host (e.g. standalone tests) it falls back to an in-place full-size container.
 */
@Composable
internal fun EtaTopLayer(content: @Composable BoxScope.() -> Unit) {
    val layer = LocalEtaTopLayer.current
    if (layer == null) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
        return
    }
    val entry = remember { EtaTopLayerEntry() }
    SideEffect { entry.content = content }
    DisposableEffect(layer, entry) {
        layer.entries += entry
        onDispose { layer.entries -= entry }
    }
}

@Composable
internal fun EtaTopLayerHost(
    state: EtaTopLayerState,
    modifier: Modifier = Modifier,
    onActiveOverlayChanged: (Boolean) -> Unit = {},
) {
    val currentOnActiveOverlayChanged by rememberUpdatedState(onActiveOverlayChanged)
    LaunchedEffect(state.entries.size) {
        if (state.entries.isEmpty()) {
            currentOnActiveOverlayChanged(false)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.onSizeChanged { size ->
                currentOnActiveOverlayChanged(size.width > 0 && size.height > 0)
            },
        ) {
            state.entries.forEach { entry ->
                key(entry) { entry.content(this) }
            }
        }
    }
}
