package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Unified OS4 glass skeleton for Eta secondary list pages: collapsible large title on phones, fixed small title on wide screens, GlassTopAppBar,
 * landscape safe area, wide-screen content centering, scroll-boundary feel, and overscroll rebound are all provided here.
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable (Backdrop?) -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val pageBackdrop = rememberGlassBackdrop(surfaceColor)
    val demandPageBackdrop = remember(pageBackdrop, surfaceColor) {
        pageBackdrop?.let { DemandSceneBackdrop(it, fallbackColor = surfaceColor) }
    }
    if (demandPageBackdrop != null) {
        LaunchedEffect(demandPageBackdrop.isActive) {
            if (!demandPageBackdrop.isActive) return@LaunchedEffect
            while (demandPageBackdrop.isActive) {
                delay(250L)
                demandPageBackdrop.refreshIdleState()
            }
        }
    }
    val isContentScrolled by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val expandable = !compactTitle && !rememberIsWideScreen()
    val scrollState = scrollBehavior.state
    val isTopBarGlassActive by remember(listState, scrollState) {
        derivedStateOf {
            listState.canScrollBackward ||
                scrollState.contentOffset < -1f ||
                scrollState.overscrollOffset < -1f
        }
    }
    val captureActive = isTopBarGlassActive || demandPageBackdrop?.isActive == true
    if (demandPageBackdrop != null) {
        SideEffect {
            demandPageBackdrop.setDelegateAttached(captureActive)
        }
    }
    val onOverscrollChanged: (Float) -> Unit = remember(scrollState) {
        { scrollState.overscrollOffset = it }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                backdrop = if (isTopBarGlassActive) pageBackdrop else null,
                expandable = expandable,
                scrollBehavior = scrollBehavior,
                isContentScrolled = isContentScrolled,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                bottomContent = { topBarBottomContent(demandPageBackdrop) },
            )
        },
    ) { innerPadding ->
        CompositionLocalProvider(LocalGlassPageBackdrop provides demandPageBackdrop) {
            WidePageContent { sidePadding ->
                // Keep the default overscroll factory injected by MiuixTheme and sync the rebound offset to the top bar.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .captureGlassBackdrop(
                            if (captureActive) pageBackdrop else null,
                        )
                        .horizontalCutoutPadding()
                        .scrollEndHaptic()
                        .overScrollVertical(onOffsetChanged = onOverscrollChanged)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = sidePadding,
                        top = innerPadding.calculateTopPadding(),
                        end = sidePadding,
                    ),
                ) {
                    content()
                    item(key = "bottom_spacer") {
                        MiuixPageBottomSpacer()
                    }
                }
            }
            overlay()
        }
    }
}

/**
 * Lower-level OS4 glass skeleton for custom-content secondary pages. Callers wire top padding, horizontal safe area, and nested scroll
 * into their own content; [sidePadding] limits actual content width on wide screens, while the scroll container itself stays full-width.
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottomContent: @Composable (Backdrop?) -> Unit = {},
    overlay: @Composable () -> Unit = {},
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    isContentScrolled: Boolean? = null,
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val pageBackdrop = rememberGlassBackdrop(surfaceColor)
    val demandPageBackdrop = remember(pageBackdrop, surfaceColor) {
        pageBackdrop?.let { DemandSceneBackdrop(it, fallbackColor = surfaceColor) }
    }
    if (demandPageBackdrop != null) {
        LaunchedEffect(demandPageBackdrop.isActive) {
            if (!demandPageBackdrop.isActive) return@LaunchedEffect
            while (demandPageBackdrop.isActive) {
                delay(250L)
                demandPageBackdrop.refreshIdleState()
            }
        }
    }
    val scrollState = scrollBehavior.state
    val isTopBarGlassActive by remember(scrollState, isContentScrolled) {
        derivedStateOf {
            isContentScrolled == true ||
                scrollState.contentOffset < -1f ||
                scrollState.overscrollOffset < -1f
        }
    }
    val captureActive = isTopBarGlassActive || demandPageBackdrop?.isActive == true
    if (demandPageBackdrop != null) {
        SideEffect {
            demandPageBackdrop.setDelegateAttached(captureActive)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                backdrop = if (isTopBarGlassActive) pageBackdrop else null,
                scrollBehavior = scrollBehavior,
                isContentScrolled = isContentScrolled,
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                actions = actions,
                bottomContent = { topBarBottomContent(demandPageBackdrop) },
            )
        },
    ) { paddingValues ->
        CompositionLocalProvider(LocalGlassPageBackdrop provides demandPageBackdrop) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureGlassBackdrop(
                        if (captureActive) pageBackdrop else null,
                    ),
            ) {
                WidePageContent { sidePadding ->
                    content(paddingValues, scrollBehavior, sidePadding)
                }
            }
            overlay()
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
