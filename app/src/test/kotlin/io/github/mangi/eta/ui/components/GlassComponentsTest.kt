package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiText
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.layout.TopBarBackdropLayout
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassComponentsTest {

    @Test
    fun couiSecondaryPagesReboundOrScrollBelowTheTitle() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(400, 800)
            override val containerDpSize = DpSize(400.dp, 800.dp)
        }
        val dispatcher = NestedScrollDispatcher()
        val connection = object : NestedScrollConnection {}
        val scrollState = ScrollState(0)
        val listState = LazyListState()
        var lazyPage by mutableStateOf(false)
        var longPage by mutableStateOf(false)

        try {
            mount(activity, scope, recomposer) {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    AgentAppTheme(appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI, blurEnabled = false)) {
                        key(lazyPage, longPage) {
                            val pageModifier = Modifier.width(400.dp).height(700.dp)
                            val row: @Composable () -> Unit = {
                                Box(
                                    Modifier.fillMaxWidth().height(56.dp)
                                        .nestedScroll(connection, dispatcher)
                                        .testTag("firstGroup"),
                                ) {
                                    CouiText("install")
                                }
                                if (longPage) Spacer(Modifier.height(1000.dp))
                            }
                            if (lazyPage) {
                                CouiLazyScaffoldPage(title = "Skills", onBack = {}, modifier = pageModifier, listState = listState) {
                                    item { row() }
                                }
                            } else {
                                CouiScaffoldPage(title = "Skills", onBack = {}, modifier = pageModifier, scrollState = scrollState) { row() }
                            }
                        }
                    }
                }
            }
            fun dispatchDrag(startY: Float, endY: Float) {
                val root = activity.window.decorView
                val downTime = SystemClock.uptimeMillis()
                val x = 200f
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0)
                root.dispatchTouchEvent(down)
                down.recycle()
                pumpFrames(activity, frameClock)
                val steps = 8
                for (step in 1..steps) {
                    val t = downTime + step * 16L
                    val y = startY + (endY - startY) * step / steps
                    val move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, x, y, 0)
                    root.dispatchTouchEvent(move)
                    move.recycle()
                    pumpFrames(activity, frameClock)
                }
                val up = MotionEvent.obtain(downTime, downTime + (steps + 1) * 16L, MotionEvent.ACTION_UP, x, endY, 0)
                root.dispatchTouchEvent(up)
                up.recycle()
                repeat(16) { pumpFrames(activity, frameClock) }
            }

            for (lazy in listOf(false, true)) {
                lazyPage = lazy
                pumpFrames(activity, frameClock)
                fun rowBounds() = collectSemanticsNodes(activity.window.decorView).single {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "firstGroup"
                }.boundsInRoot
                val expandedTitleBottom = collectSemanticsNodes(activity.window.decorView).filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Skills" }
                }.maxOf { it.boundsInRoot.bottom }
                val restingBounds = rowBounds()
                assertTrue("the first group at rest should sit below the large title, lazy=$lazy", restingBounds.top >= expandedTitleBottom)

                // pulling down on a short page should trigger overscroll rebound and return to the resting position after release
                dispatcher.dispatchPostScroll(Offset.Zero, Offset(0f, 72f), NestedScrollSource.UserInput)
                pumpFrames(activity, frameClock)
                val pulledDown = rowBounds()
                assertTrue("pulling down on a short page should shift downward, lazy=$lazy", pulledDown.top > restingBounds.top)
                scope.launch { dispatcher.dispatchPreFling(Velocity.Zero) }
                repeat(16) { pumpFrames(activity, frameClock) }
                assertEquals("after release the short page should rebound to the resting position, lazy=$lazy", restingBounds, rowBounds())

                // a real touch swipe up on a short page should collapse the large title and move the body up smoothly
                dispatchDrag(startY = 520f, endY = 220f)
                val collapsedBounds = rowBounds()
                assertTrue(
                    "swiping up on a short page should collapse the large title so the first group moves up, lazy=$lazy rest=${restingBounds.top} collapsed=${collapsedBounds.top}",
                    collapsedBounds.top < restingBounds.top - 20f,
                )

                // a real touch swipe down on a short page should re-expand the large title back to the resting position
                dispatchDrag(startY = 220f, endY = 520f)
                val reExpandedBounds = rowBounds()
                assertEquals("swiping down on a short page should re-expand the large title back to the resting position, lazy=$lazy", restingBounds.top, reExpandedBounds.top, 2f)
            }
            longPage = true
            for (lazy in listOf(false, true)) {
                lazyPage = lazy
                pumpFrames(activity, frameClock)
                val consumed = dispatcher.dispatchPreScroll(Offset(0f, -240f), NestedScrollSource.UserInput)
                assertTrue("swiping up on a long page should collapse the top bar, lazy=$lazy", consumed.y < 0f)
                scope.launch {
                    if (lazy) listState.scrollToItem(0, 96) else scrollState.scrollTo(96)
                }
                pumpFrames(activity, frameClock)
                assertTrue("the long-page body should be able to keep scrolling, lazy=$lazy", if (lazy) listState.canScrollBackward else scrollState.canScrollBackward)
            }
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun topBarBackdropExtendsBelowTheBarWithoutChangingItsHeight() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var barHeight = 0
        var backdropHeight = 0

        try {
            mount(activity, scope, recomposer) {
                Box {
                    TopBarBackdropLayout(
                        backgroundHeight = { height -> height + 24.dp.roundToPx() },
                        modifier = Modifier.onSizeChanged { barHeight = it.height },
                        backgroundModifier = Modifier.onSizeChanged { backdropHeight = it.height },
                    ) {
                        Box(Modifier.fillMaxWidth().height(52.dp))
                    }
                }
            }
            pumpFrames(activity, frameClock)
            with(Density(activity.resources.displayMetrics.density)) {
                assertEquals(52.dp.roundToPx(), barHeight)
                assertEquals(barHeight + 24.dp.roundToPx(), backdropHeight)
            }
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun etaGlassSpinnerPreference_displaysSummaryRespectsDisabledOptionAndUpdatesSelection() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                    provideGlassHost = true,
                ) {
                    Column {
                        EtaGlassSpinnerPreference(
                            title = "run mode",
                            items = listOf(
                                DropdownItem(text = "PRoot", summary = "no Root required", enabled = true),
                                DropdownItem(text = "Chroot", summary = "Root required", enabled = false),
                                DropdownItem(text = "Direct", summary = "native terminal", enabled = true),
                            ),
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { selectedIndex = it },
                        )
                    }
                }
            }
            pumpFrames(activity, frameClock)

            // tap the Preference row to open GlassDropdownPopup
            clickByText(activity, "run mode")
            pumpFrames(activity, frameClock)

            val texts = collectAllTexts(activity)
            assertTrue("Expected summary in dropdown: $texts", texts.any { it == "no Root required" })
            assertTrue("Expected disabled option summary: $texts", texts.any { it == "Root required" })

            // disabled items cannot be selected (selectedIndex is not modified)
            val disabledNode = findClickableNodeByText(activity, "Chroot")
            assertTrue(disabledNode == null || disabledNode.config.contains(SemanticsProperties.Disabled))
            assertEquals(0, selectedIndex)

            // tap an available item to update the selected index
            clickByText(activity, "Direct")
            pumpFrames(activity, frameClock)
            assertEquals(2, selectedIndex)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun etaGlassSpinnerPreference_worksInStandaloneScaffoldPopupLayoutFallback() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var selectedIndex by mutableIntStateOf(0)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                ) {
                    Scaffold {
                        EtaGlassSpinnerPreference(
                            title = "voice",
                            items = listOf(
                                DropdownItem(text = "Cherry", summary = "clear female voice"),
                                DropdownItem(text = "Ethan", summary = "steady male voice"),
                            ),
                            selectedIndex = selectedIndex,
                            onSelectedIndexChange = { selectedIndex = it },
                        )
                    }
                }
            }
            pumpFrames(activity, frameClock)

            clickByText(activity, "voice")
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == "steady male voice" })

            clickByText(activity, "Ethan")
            pumpFrames(activity, frameClock)
            assertEquals(1, selectedIndex)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun miuixTopBarActionButton_blocksDisabledClicksAndTriggersEnabledClicks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var enabled by mutableStateOf(false)
        var clickCount = 0

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceUiStyle.MIUIX.let { AppearanceSettings(uiStyle = it) },
                ) {
                    MiuixTopBarActionButton(
                        icon = MiuixIcons.Glass.Refresh,
                        contentDescription = "refresh",
                        enabled = enabled,
                        onClick = { clickCount++ },
                    )
                }
            }
            pumpFrames(activity, frameClock)

            val disabledNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "refresh" } &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(disabledNode.config.contains(SemanticsProperties.Disabled))
            disabledNode.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(0, clickCount)

            enabled = true
            pumpFrames(activity, frameClock)

            val enabledNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "refresh" } &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            assertFalse(enabledNode.config.contains(SemanticsProperties.Disabled))
            enabledNode.config[SemanticsActions.OnClick].action?.invoke()
            assertEquals(1, clickCount)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun agentToolsScreen_miuixGlassTabRowSwitchesTabs() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        val toolsState = AgentToolsUiState(
            groups = listOf(
                ToolGroupUi(
                    id = "root",
                    title = "advanced",
                    tools = listOf(
                        ToolItemUi(id = "get_logcat", title = "log inspection", summary = "Root required"),
                    ),
                ),
            ),
        )

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX),
                ) {
                    AgentToolsScreen(state = toolsState, onAction = {})
                }
            }
            pumpFrames(activity, frameClock)

            val allTabLabel = activity.getString(R.string.capability_all)
            clickByText(activity, allTabLabel)
            pumpFrames(activity, frameClock)

            assertTrue(
                "Switching to All tab should expose root-gated tools",
                collectAllTexts(activity).any { it == "log inspection" },
            )
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @Config(application = EtaApp::class, sdk = [36])
    fun appearanceSettingsScreen_miuixExposesCmfPalette() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = AppearanceUiStyle.MIUIX,
                        monetEnabled = true,
                    ),
                ) {
                    AppearanceSettingsScreen(onBack = {})
                }
            }
            pumpFrames(activity, frameClock)

            // open the color style menu to verify the CMF options are available
            clickByText(activity, activity.getString(R.string.appearance_palette_style))
            pumpFrames(activity, frameClock)
            assertTrue(
                "Expected CMF palette label in dropdown",
                collectAllTexts(activity).any { it == activity.getString(R.string.appearance_palette_cmf) },
            )
            clickByText(activity, activity.getString(R.string.appearance_palette_cmf))
            pumpFrames(activity, frameClock)
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    @Config(application = EtaApp::class, sdk = [36])
    fun switchingUiStyleInsideNavDisplayAndSceneHostWorksCleanly() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(
                uiStyle = AppearanceUiStyle.MIUIX,
                themeMode = AppearanceThemeMode.LIGHT,
            ),
        )

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = appearance,
                    provideGlassHost = true,
                ) {
                    val backStack = top.yukonga.miuix.kmp.nav.core.rememberNavBackStack<io.github.mangi.eta.ui.navigation.AppRoute>(
                        io.github.mangi.eta.ui.navigation.AppRoute.Home,
                        io.github.mangi.eta.ui.navigation.AppRoute.AppearanceSettings,
                    )
                    top.yukonga.miuix.kmp.nav.core.NavDisplay(
                        backStack = backStack,
                        onBack = {},
                    ) {
                        entry<io.github.mangi.eta.ui.navigation.AppRoute.Home> {
                            top.yukonga.miuix.kmp.basic.Text("Home")
                        }
                        entry<io.github.mangi.eta.ui.navigation.AppRoute.AppearanceSettings> {
                            AppearanceSettingsScreen(onBack = {})
                        }
                    }
                }
            }
            pumpFrames(activity, frameClock)

            // open the UI style dropdown and tap COUI
            clickByText(activity, activity.getString(R.string.appearance_ui_style))
            pumpFrames(activity, frameClock)
            clickByText(activity, activity.getString(R.string.appearance_ui_style_coui))
            appearance = appearance.copy(uiStyle = AppearanceUiStyle.COUI)
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })

            // switch back to MIUIX and wait for animation and layout to stabilize
            appearance = appearance.copy(uiStyle = AppearanceUiStyle.MIUIX)
            repeat(4) { pumpFrames(activity, frameClock) }
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })

            clickByText(activity, activity.getString(R.string.appearance_theme_mode))
            repeat(4) { pumpFrames(activity, frameClock) }
            val afterClickTexts = collectAllTexts(activity)
            assertTrue(
                "Expected Dark after clicking Theme mode. Available: $afterClickTexts",
                afterClickTexts.any { it == activity.getString(R.string.appearance_theme_dark) },
            )
            appearance = appearance.copy(themeMode = AppearanceThemeMode.DARK)
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == activity.getString(R.string.appearance_title) })
        } finally {
            recomposer.cancel()
            scope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun draftEchoPreservesImeCompositionAndExternalReplacementDoesNotEmitAnEdit() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        var draft by mutableStateOf("14")
        val edits = mutableListOf<String>()
        lateinit var connection: InputConnection
        lateinit var editor: TextFieldState

        try {
            mount(activity, scope, recomposer) {
                editor = rememberDraftTextFieldState(draft) {
                    edits += it
                    draft = it
                }
                InterceptPlatformTextInput(interceptor = { request, _ ->
                    connection = request.createInputConnection(EditorInfo())
                    awaitCancellation()
                }) {
                    BasicTextField(state = editor, inputTransformation = DecimalDigits.maxLengthTrim(3))
                }
            }
            pumpFrames(activity, frameClock)
            fun field() = collectSemanticsNodes(activity.window.decorView).single {
                it.config.contains(SemanticsActions.SetText)
            }
            controller.windowFocusChanged(true)
            assertEquals(true, field().config[SemanticsActions.RequestFocus].action!!.invoke())
            pumpFrames(activity, frameClock)
            connection.setSelection(1, 1)
            connection.setComposingText("2", 1)
            pumpFrames(activity, frameClock)
            assertEquals("124", draft)
            assertEquals(TextRange(2), editor.selection)
            assertEquals(TextRange(1, 2), editor.composition)
            connection.finishComposingText()
            field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("a1b2345"))
            pumpFrames(activity, frameClock)
            assertEquals("123", draft)
            draft = "85"
            pumpFrames(activity, frameClock)
            assertEquals("85", editor.text.toString())
            assertEquals(TextRange(2), editor.selection)
            assertEquals(listOf("124", "123"), edits)
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun bothSecureFieldsPreserveSelectionAndExposePasswordVisibility() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)
        val state = TextFieldState("example", TextRange(1, 4))
        var visible by mutableStateOf(false)
        var style by mutableStateOf(AppearanceUiStyle.COUI)

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(appearance = AppearanceSettings(uiStyle = style)) {
                    if (style == AppearanceUiStyle.COUI) {
                        CouiTextField(state = state, label = "secret key", secretVisible = visible)
                    } else {
                        MiuixSecureTextField(state = state, label = "secret key", visible = visible)
                    }
                }
            }
            for (uiStyle in AppearanceUiStyle.entries) {
                style = uiStyle
                for (reveal in listOf(false, true, false)) {
                    visible = reveal
                    pumpFrames(activity, frameClock)
                    val field = collectSemanticsNodes(activity.window.decorView).single {
                        it.config.contains(SemanticsProperties.Password)
                    }
                    assertEquals(!reveal, field.config[SemanticsProperties.IsPasswordObfuscated])
                    assertFalse(field.config.contains(SemanticsActions.CopyText))
                    assertEquals("example", state.text.toString())
                    assertEquals(TextRange(1, 4), state.selection)
                }
            }
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun couiDarkThemeProvidesLightContentColorAndSearchBarHidesCancelWhenCollapsed() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(scope.coroutineContext)

        var resolvedContentColor = androidx.compose.ui.graphics.Color.Unspecified
        var rejectedSwitchClicks = 0
        var query by mutableStateOf("")

        try {
            mount(activity, scope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = AppearanceUiStyle.COUI,
                        themeMode = AppearanceThemeMode.DARK,
                        blurEnabled = false,
                    ),
                ) {
                    resolvedContentColor = io.github.suqi8.coui.kmp.theme.LocalContentColor.current
                    Column {
                        io.github.mangi.eta.ui.components.coui.CouiSwitchPreference(
                            title = "protected switch",
                            checked = false,
                            onCheckedChange = { rejectedSwitchClicks++ },
                        )
                        io.github.mangi.eta.ui.components.coui.CouiSearchBarBox(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "search models",
                        )
                    }
                }
            }
            pumpFrames(activity, frameClock)

            // 1. in dark mode COUI LocalContentColor must not stay at the default black
            assertTrue(
                "COUI dark mode LocalContentColor should be light, got $resolvedContentColor",
                resolvedContentColor != androidx.compose.ui.graphics.Color.Black &&
                    resolvedContentColor.red > 0.7f,
            )

            // 2. CouiSwitchPreference must not stay stuck in the on state after refusing to toggle
            val switchNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.contains(SemanticsProperties.ToggleableState) &&
                    node.config.contains(SemanticsActions.OnClick)
            }
            switchNode.config[SemanticsActions.OnClick].action?.invoke()
            pumpFrames(activity, frameClock)
            assertEquals(1, rejectedSwitchClicks)
            val afterSwitchNode = collectSemanticsNodes(activity.window.decorView).first { node ->
                node.config.contains(SemanticsProperties.ToggleableState)
            }
            assertEquals(
                androidx.compose.ui.state.ToggleableState.Off,
                afterSwitchNode.config[SemanticsProperties.ToggleableState],
            )

            // 3. CouiSearchBarBox must not show the cancel button when collapsed
            val cancelLabel = activity.getString(R.string.action_cancel)
            assertFalse(
                "Cancel button must not show when CouiSearchBarBox is collapsed",
                collectAllTexts(activity).any { it == cancelLabel },
            )
        } finally {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }

    private fun mount(
        activity: ComponentActivity,
        scope: CoroutineScope,
        recomposer: Recomposer,
        content: @Composable () -> Unit,
    ) {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val composeView = ComposeView(activity).apply {
            setParentCompositionContext(recomposer)
            setContent(content)
        }
        activity.setContentView(composeView)
    }

    private fun findClickableNodeByText(activity: ComponentActivity, text: String): SemanticsNode? =
        collectSemanticsNodes(activity.window.decorView).firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

    private fun clickByText(activity: ComponentActivity, text: String) {
        val node = findClickableNodeByText(activity, text)
            ?: error("Expected clickable node with text '$text'. Available: ${collectAllTexts(activity)}")
        val invoked = node.config[SemanticsActions.OnClick].action?.invoke()
        assertEquals(true, invoked)
    }

    private fun collectAllTexts(activity: ComponentActivity): List<String> =
        collectSemanticsNodes(activity.window.decorView).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun pumpFrames(activity: ComponentActivity, frameClock: BroadcastFrameClock) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(4) {
            Snapshot.sendApplyNotifications()
            looper.idle()
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            measureAndLayoutActivity(activity)
            looper.idle()
        }
    }

    private fun measureAndLayoutActivity(activity: ComponentActivity) {
        val decor = activity.window.decorView
        forceLayoutTree(decor)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        decor.measure(widthSpec, heightSpec)
        decor.layout(0, 0, 1080, 2400)
    }

    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                forceLayoutTree(view.getChildAt(i))
            }
        }
    }

    private fun collectSemanticsNodes(root: View): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitView(view: View) {
            if (view is AbstractComposeView) {
                val child = view.getChildAt(0)
                if (child != null) {
                    runCatching {
                        val ownerField = child.javaClass.getMethod("getSemanticsOwner")
                        val semanticsOwner = ownerField.invoke(child)
                        val mergedRoot = semanticsOwner.javaClass
                            .getMethod("getRootSemanticsNode")
                            .invoke(semanticsOwner) as SemanticsNode
                        fun collect(node: SemanticsNode) {
                            result.add(node)
                            node.children.forEach(::collect)
                        }
                        collect(mergedRoot)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visitView(view.getChildAt(i))
                }
            }
        }
        visitView(root)
        return result
    }
}
