package io.github.namanwtf.atom.ui.components

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.model.AppearanceThemeMode
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import io.github.namanwtf.atom.ui.model.ConversationModeUi
import io.github.namanwtf.atom.ui.model.ConversationPaneUiState
import io.github.namanwtf.atom.ui.model.ConversationSummaryUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ConversationPanePanelTest {

    @Suppress("DEPRECATION")
    @Before
    fun resetLocaleForEnglishAssertions() {
        val resources = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.US)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    @Test
    fun sidePaneComposesAcrossStylesAndThemeModesAndHandlesNavigationActions() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(
                uiStyle = AppearanceUiStyle.COUI,
                themeMode = AppearanceThemeMode.LIGHT,
            ),
        )
        var paneState by mutableStateOf(
            ConversationPaneUiState(
                conversations = listOf(
                    ConversationSummaryUi(
                        id = "conv-pinned",
                        title = "pin system debugging",
                        preview = "check permissions",
                        timeLabel = "Today",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                        isPinned = true,
                    ),
                    ConversationSummaryUi(
                        id = "conv-today",
                        title = "hello, who are you",
                        preview = "I am Atom",
                        timeLabel = "Today",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                        isActiveRun = true,
                        runStatus = "thinking",
                    ),
                    ConversationSummaryUi(
                        id = "conv-role",
                        title = "morning cafe",
                        preview = "good morning",
                        timeLabel = "Yesterday",
                        updatedAtMillis = 1_000L,
                        mode = ConversationModeUi.Chat,
                        characterName = "Lin Lan",
                    ),
                ),
                selectedConversationId = "conv-today",
                searchQuery = "",
            ),
        )
        var hasPermissionAttention by mutableStateOf(true)
        var composedQuery by mutableStateOf("")
        var composedAttention by mutableStateOf(true)

        var newChatCount = 0
        var selectedConversation: String? = null
        var modelsClicks = 0
        var charactersClicks = 0
        var toolsClicks = 0
        var skillsClicks = 0
        var permissionsClicks = 0
        var settingsClicks = 0

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(
                    appearance = appearance,
                ) {
                    composedQuery = paneState.searchQuery
                    composedAttention = hasPermissionAttention
                    ConversationSidePaneScaffold(
                        state = paneState,
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = { query ->
                            paneState = paneState.copy(searchQuery = query)
                        },
                        onNewConversation = { newChatCount++ },
                        onConversationSelected = { selectedConversation = it },
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = { settingsClicks++ },
                        onOpenModelProviders = { modelsClicks++ },
                        onOpenTools = { toolsClicks++ },
                        onOpenSkills = { skillsClicks++ },
                        onOpenCharacters = { charactersClicks++ },
                        onOpenPermissions = { permissionsClicks++ },
                        activeModelLabel = "claude-sonnet-4",
                        hasPermissionAttention = hasPermissionAttention,
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }

            for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
                for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                    appearance = AppearanceSettings(uiStyle = style, themeMode = theme)
                    pumpFrames(activity, frameClock)

                    val nodes = collectSemanticsNodes(activity.window.decorView)
                    val allTexts = nodes.flatMap { node ->
                        (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    }
                    assertTrue(
                        "Expected workbench models entry for $style / $theme",
                        allTexts.any { it.contains("claude-sonnet-4") },
                    )
                    assertTrue(
                        "Expected conversation title for $style / $theme",
                        allTexts.any { it == "hello, who are you" },
                    )
                }
            }

            // switch back to COUI light theme to verify workspace, bottom bar and new-button interactions
            appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI, themeMode = AppearanceThemeMode.LIGHT)
            pumpFrames(activity, frameClock)

            clickByLabel(activity, activity.getString(R.string.action_new_conversation))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_models))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_characters))
            clickByLabel(activity, activity.getString(R.string.route_tools))
            clickByLabel(activity, activity.getString(R.string.route_skills))
            clickByLabel(activity, activity.getString(R.string.conversation_permission_attention))
            clickByLabel(activity, activity.getString(R.string.route_settings))
            clickByLabel(activity, "pin system debugging")

            assertEquals(1, newChatCount)
            assertEquals(1, modelsClicks)
            assertEquals(1, charactersClicks)
            assertEquals(1, toolsClicks)
            assertEquals(1, skillsClicks)
            assertEquals(1, permissionsClicks)
            assertEquals(1, settingsClicks)
            assertEquals("conv-pinned", selectedConversation)

            // once all permissions are ready the description switches to the normal "permission", and the workspace collapses to focus results while searching
            hasPermissionAttention = false
            paneState = paneState.copy(searchQuery = "coffee")
            pumpFrames(activity, frameClock)

            assertEquals(false, composedAttention)
            assertEquals("coffee", composedQuery)

            val searchNodes = collectSemanticsNodes(activity.window.decorView)
            val searchTexts = searchNodes.flatMap { node ->
                (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
            assertFalse(
                "Workbench should hide while searching: $searchTexts",
                searchTexts.any { it == "claude-sonnet-4" },
            )
            assertTrue(
                "Healthy permission button should use default label",
                searchTexts.any { it == activity.getString(R.string.route_permissions) },
            )

            // tap the native search box clear button to restore the workspace display (COUI's native InputField built-in clear button text is "Clear text")
            clickByLabel(activity, "Clear text")
            pumpFrames(activity, frameClock)
            assertEquals("", paneState.searchQuery)
            val restoredTexts = collectAllTexts(activity)
            assertTrue(
                "Workbench should reappear after clearing search",
                restoredTexts.any { it == "claude-sonnet-4" },
            )

            // also verify new conversation, workspace entry and bottom-bar circular button clicks in MIUIX mode
            appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX, themeMode = AppearanceThemeMode.DARK)
            pumpFrames(activity, frameClock)
            clickByLabel(activity, activity.getString(R.string.action_new_conversation))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_models))
            clickByLabel(activity, activity.getString(R.string.conversation_dock_characters))
            clickByLabel(activity, activity.getString(R.string.route_tools))
            clickByLabel(activity, activity.getString(R.string.route_skills))
            clickByLabel(activity, activity.getString(R.string.route_permissions))
            clickByLabel(activity, activity.getString(R.string.route_settings))
            assertEquals(2, newChatCount)
            assertEquals(2, modelsClicks)
            assertEquals(2, charactersClicks)
            assertEquals(2, toolsClicks)
            assertEquals(2, skillsClicks)
            assertEquals(2, permissionsClicks)
            assertEquals(2, settingsClicks)
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun sidePaneShowsEmptyStateWhenNoConversationsExist() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)
        var style by mutableStateOf(AppearanceUiStyle.COUI)
        var conversations by mutableStateOf(
            listOf(
                ConversationSummaryUi(
                    id = "conv-1",
                    title = "temporary conversation",
                    preview = "test",
                    timeLabel = "Today",
                    updatedAtMillis = System.currentTimeMillis(),
                    mode = ConversationModeUi.Chat,
                ),
            ),
        )

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = style,
                        themeMode = AppearanceThemeMode.LIGHT,
                    ),
                ) {
                    ConversationSidePaneScaffold(
                        state = ConversationPaneUiState(
                            conversations = conversations,
                            selectedConversationId = conversations.firstOrNull()?.id,
                            searchQuery = "",
                        ),
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = {},
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = {},
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = {},
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            pumpFrames(activity, frameClock)
            assertTrue(collectAllTexts(activity).any { it == "temporary conversation" })

            conversations = emptyList()
            pumpFrames(activity, frameClock)
            val couiEmptyTexts = collectAllTexts(activity)
            assertTrue(
                "COUI empty state title should be shown: $couiEmptyTexts",
                couiEmptyTexts.any { it == activity.getString(R.string.conversation_empty) },
            )

            style = AppearanceUiStyle.MIUIX
            pumpFrames(activity, frameClock)
            val miuixEmptyTexts = collectAllTexts(activity)
            assertTrue(
                "MIUIX empty state title should be shown: $miuixEmptyTexts",
                miuixEmptyTexts.any { it == activity.getString(R.string.conversation_empty) },
            )
            assertTrue(
                "Empty state hint should be shown: $miuixEmptyTexts",
                miuixEmptyTexts.any { it == activity.getString(R.string.conversation_empty_hint) },
            )
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun roleplayConversationRowRendersNonZeroAvatarInSelectedAndUnselectedStates() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)
        var style by mutableStateOf(AppearanceUiStyle.COUI)
        var selectedId by mutableStateOf<String?>("conv-other")
        val conversations = listOf(
            ConversationSummaryUi(
                id = "conv-other",
                title = "ordinary conversation",
                preview = "hello",
                timeLabel = "Today",
                updatedAtMillis = System.currentTimeMillis(),
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "conv-role",
                title = "morning cafe",
                preview = "good morning",
                timeLabel = "Today",
                updatedAtMillis = System.currentTimeMillis() - 1_000L,
                mode = ConversationModeUi.Chat,
                characterName = "Lin Lan",
            ),
        )

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(
                    appearance = AppearanceSettings(
                        uiStyle = style,
                        themeMode = AppearanceThemeMode.LIGHT,
                    ),
                ) {
                    ConversationSidePaneScaffold(
                        state = ConversationPaneUiState(
                            conversations = conversations,
                            selectedConversationId = selectedId,
                            searchQuery = "",
                        ),
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = {},
                        onSearchChange = {},
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = {},
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = {},
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }

            for (uiStyle in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
                style = uiStyle
                for (selection in listOf("conv-other", "conv-role")) {
                    selectedId = selection
                    pumpFrames(activity, frameClock)
                    val avatarNode = collectSemanticsNodes(activity.window.decorView, unmerged = true)
                        .firstOrNull { node ->
                            node.config.getOrNull(SemanticsProperties.Text)
                                .orEmpty()
                                .any { it.text == "Lin" }
                        }
                    assertTrue(
                        "Expected monogram avatar node for $uiStyle selection=$selection",
                        avatarNode != null,
                    )
                    val bounds = avatarNode!!.boundsInWindow
                    assertTrue(
                        "Expected non-zero avatar bounds for $uiStyle selection=$selection, got $bounds",
                        bounds.width > 0f && bounds.height > 0f,
                    )
                }
            }
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun sidePaneSearchCoordinatesButtonsFocusAndBackAcrossBothStyles() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val frameClock = BroadcastFrameClock()
        val recomposeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        val recomposer = Recomposer(recomposeScope.coroutineContext)

        var appearance by mutableStateOf(
            AppearanceSettings(
                uiStyle = AppearanceUiStyle.COUI,
                themeMode = AppearanceThemeMode.LIGHT,
                blurEnabled = false,
            ),
        )
        var paneState by mutableStateOf(
            ConversationPaneUiState(
                conversations = listOf(
                    ConversationSummaryUi(
                        id = "conv-1",
                        title = "weekend plan",
                        preview = "organize the checklist",
                        timeLabel = "Today",
                        updatedAtMillis = System.currentTimeMillis(),
                        mode = ConversationModeUi.Chat,
                    ),
                ),
                selectedConversationId = "conv-1",
                searchQuery = "",
            ),
        )
        var paneDismissCount = 0
        var permissionsClicks = 0
        var settingsClicks = 0

        try {
            mountWithDeterministicRecomposer(activity, recomposeScope, recomposer) {
                AgentAppTheme(appearance = appearance) {
                    ConversationSidePaneScaffold(
                        state = paneState,
                        visible = true,
                        backHandlerEnabled = true,
                        onOpen = {},
                        onDismiss = { paneDismissCount++ },
                        onSearchChange = { query -> paneState = paneState.copy(searchQuery = query) },
                        onNewConversation = {},
                        onConversationSelected = {},
                        onConversationTogglePin = {},
                        onConversationRename = {},
                        onConversationExport = {},
                        onConversationDelete = {},
                        onOpenSettings = { settingsClicks++ },
                        onOpenModelProviders = {},
                        onOpenTools = {},
                        onOpenSkills = {},
                        onOpenCharacters = {},
                        onOpenPermissions = { permissionsClicks++ },
                        activeModelLabel = "claude-sonnet-4",
                        hasPermissionAttention = false,
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            controller.windowFocusChanged(true)

            val permissionsLabel = activity.getString(R.string.route_permissions)
            val settingsLabel = activity.getString(R.string.route_settings)
            val cancelLabel = activity.getString(R.string.action_cancel)

            for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
                appearance = appearance.copy(uiStyle = style)
                paneState = paneState.copy(searchQuery = "")
                repeat(8) { pumpFrames(activity, frameClock) }

                // collapsed state: permission and settings buttons are visible and tappable, the cancel button is not shown
                val collapsedTexts = collectAllTexts(activity)
                assertTrue("Expected permissions button collapsed for $style", collapsedTexts.any { it == permissionsLabel })
                assertTrue("Expected settings button collapsed for $style", collapsedTexts.any { it == settingsLabel })
                assertFalse("Cancel button should stay hidden when collapsed for $style", collapsedTexts.any { it == cancelLabel })

                // tap the search input to expand search: the cancel button appears, and the permission/settings buttons leave the semantic and interaction scope
                val searchInput = collectSemanticsNodes(activity.window.decorView).first { node ->
                    node.config.contains(SemanticsActions.SetText)
                }
                searchInput.config[SemanticsActions.OnClick].action?.invoke()
                repeat(12) { pumpFrames(activity, frameClock) }

                val expandedTexts = collectAllTexts(activity)
                assertTrue("Expected cancel button when search expanded for $style: $expandedTexts", expandedTexts.any { it == cancelLabel })
                assertFalse("Permissions button should hide when search expanded for $style", expandedTexts.any { it == permissionsLabel })
                assertFalse("Settings button should hide when search expanded for $style", expandedTexts.any { it == settingsLabel })

                // tap cancel after typing a query: clear the query and restore the permission/settings buttons
                paneState = paneState.copy(searchQuery = "weekend")
                pumpFrames(activity, frameClock)
                clickByLabel(activity, cancelLabel)
                repeat(12) { pumpFrames(activity, frameClock) }

                assertEquals("Query should clear on cancel for $style", "", paneState.searchQuery)
                val restoredTexts = collectAllTexts(activity)
                assertTrue("Permissions button should return after cancel for $style", restoredTexts.any { it == permissionsLabel })
                assertTrue("Settings button should return after cancel for $style", restoredTexts.any { it == settingsLabel })

                // expand again and collapse with the system back button: back closes the search first, not the side panel
                val searchInputAgain = collectSemanticsNodes(activity.window.decorView).first { node ->
                    node.config.contains(SemanticsActions.SetText)
                }
                searchInputAgain.config[SemanticsActions.OnClick].action?.invoke()
                repeat(12) { pumpFrames(activity, frameClock) }
                val previousDismiss = paneDismissCount
                activity.onBackPressedDispatcher.onBackPressed()
                repeat(12) { pumpFrames(activity, frameClock) }

                assertEquals("Back while searching should not dismiss side pane for $style", previousDismiss, paneDismissCount)
                val afterBackTexts = collectAllTexts(activity)
                assertTrue("Permissions button should return after back for $style", afterBackTexts.any { it == permissionsLabel })
                assertTrue("Settings button should return after back for $style", afterBackTexts.any { it == settingsLabel })
            }
            assertEquals(0, permissionsClicks)
            assertEquals(0, settingsClicks)
        } finally {
            recomposer.cancel()
            recomposeScope.cancel()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            controller.pause().stop().destroy()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun mountWithDeterministicRecomposer(
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

    private fun collectAllTexts(activity: ComponentActivity): List<String> =
        collectSemanticsNodes(activity.window.decorView).flatMap { node ->
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun clickByLabel(activity: ComponentActivity, label: String) {
        val nodes = collectSemanticsNodes(activity.window.decorView)
        val target = nodes.firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && (
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label }
                )
        } ?: error("Expected clickable node with label '$label'")
        val clicked = target.config[SemanticsActions.OnClick].action?.invoke()
        assertEquals(true, clicked)
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

    private fun collectSemanticsNodes(root: View, unmerged: Boolean = false): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visitView(view: View) {
            if (view is AbstractComposeView) {
                val child = view.getChildAt(0)
                if (child != null) {
                    runCatching {
                        val ownerField = child.javaClass.getMethod("getSemanticsOwner")
                        val semanticsOwner = ownerField.invoke(child)
                        val rootMethod = if (unmerged) "getUnmergedRootSemanticsNode" else "getRootSemanticsNode"
                        val rootNode = semanticsOwner.javaClass
                            .getMethod(rootMethod)
                            .invoke(semanticsOwner) as SemanticsNode
                        fun collect(node: SemanticsNode) {
                            result.add(node)
                            node.children.forEach(::collect)
                        }
                        collect(rootNode)
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
