package io.github.namanwtf.atom.ui

import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeService
import io.github.namanwtf.atom.agent.voice.AtomAssistantOverlayService
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.data.model.AppearanceThemeMode
import io.github.namanwtf.atom.data.repository.AppearanceSettingsRepository
import io.github.namanwtf.atom.agent.overlay.AtomBubbleService
import io.github.namanwtf.atom.ui.app.AgentAppRoot
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import io.github.namanwtf.atom.ui.app.PredictiveBackController
import io.github.namanwtf.atom.ui.app.installStartupSplash
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var launchRequests by mutableStateOf(MainActivityLaunchRequests())
    private var appliedPredictiveBackEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAgentLogger.info("Agent UI created: task=$taskId restored=${savedInstanceState != null}")
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        launchRequests = MainActivityLaunchRequests.restore(savedInstanceState, intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    provideGlassHost = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = launchRequests.assistantConversationKey,
                        requestedConversationId = launchRequests.conversationId,
                        onRequestedConversationOpened = {
                            launchRequests = launchRequests.copy(conversationId = null)
                            intent?.action = null
                        },
                        requestedArchivedConversation = launchRequests.archivedConversation,
                        onArchivedConversationOpened = {
                            launchRequests = launchRequests.copy(archivedConversation = null)
                            intent?.action = null
                        },
                        requestedScheduledTaskId = launchRequests.scheduledTaskId,
                        onScheduledTaskOpened = {
                            launchRequests = launchRequests.copy(scheduledTaskId = null)
                            intent?.action = null
                        },
                        sharedContent = launchRequests.sharedContent,
                        onSharedContentAccepted = {
                            launchRequests = launchRequests.copy(sharedContent = null)
                            intent?.action = null
                        },
                        openSpeechSettings = launchRequests.speechSettings,
                        openTerminal = launchRequests.terminal,
                        onTerminalOpened = {
                            launchRequests = launchRequests.copy(terminal = false)
                            intent?.action = null
                        },
                        onSpeechSettingsOpened = {
                            launchRequests = launchRequests.copy(speechSettings = false)
                            intent?.action = null
                        },
                        onAssistantConversationOpened = { opened ->
                            launchRequests = launchRequests.copy(assistantConversationKey = null)
                            intent?.action = null
                            if (opened) {
                                AtomAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                    )
                }
            }
            contentReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        startedInstances.add(this)
        isInForeground = true
        startService(Intent(this, AtomBubbleService::class.java))
        AgentRuntimeService.onAtomUiVisibilityChanged()
    }

    override fun onStop() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AgentRuntimeService.onAtomUiVisibilityChanged()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        AndroidAgentLogger.info("Agent UI new intent: task=$taskId launcher=$fromLauncher")
        setIntent(intent)
        launchRequests = launchRequests.accept(intent)
    }

    override fun onDestroy() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AndroidAgentLogger.info(
            "Agent UI destroyed: task=$taskId changing_configuration=$isChangingConfigurations finishing=$isFinishing",
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchRequests.saveTo(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // App-level AUTO clears the night-mode override, restoring follow-system.
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        private val startedInstances = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())

        @Volatile
        internal var isInForeground: Boolean = false
            private set

        const val ACTION_OPEN_TERMINAL = "io.github.namanwtf.atom.action.OPEN_TERMINAL"
        /** The running overlay's result card returns to the app body: App-initiated runs carry a conversation ID and open that conversation directly. */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.namanwtf.atom.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.namanwtf.atom.extra.CONVERSATION_ID"
        /** Runs from external entries like the system assistant are archived by source and conversation key; returning to the body opens the corresponding archived conversation. */
        const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.namanwtf.atom.action.OPEN_ARCHIVED_CONVERSATION"
        const val EXTRA_HANDOFF_SOURCE = "io.github.namanwtf.atom.extra.HANDOFF_SOURCE"
        const val EXTRA_CONVERSATION_KEY = "io.github.namanwtf.atom.extra.CONVERSATION_KEY"
        const val ACTION_OPEN_SCHEDULED_TASK_DETAIL = "io.github.namanwtf.atom.action.OPEN_SCHEDULED_TASK_DETAIL"
        const val EXTRA_SCHEDULED_TASK_ID = "io.github.namanwtf.atom.extra.SCHEDULED_TASK_ID"
    }
}
