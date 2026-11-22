package io.github.namanwtf.atom.agent.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AssistantBlockKind
import io.github.namanwtf.atom.agent.model.ProviderClientFactory
import io.github.namanwtf.atom.agent.model.ProviderEvent
import io.github.namanwtf.atom.agent.model.ProviderRequest
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.voice.AtomAssistantOverlayService
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.repository.AppearanceSettingsRepository
import io.github.namanwtf.atom.data.repository.RuntimeConfigRepository
import io.github.namanwtf.atom.ui.MainActivity
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent floating bubble (the "Atom") — an always-on nucleus with an
 * electron orbit and electric arcs. Tap opens a floating mini-chat window
 * (Buddy style) that streams replies from the configured model.
 */
class AtomBubbleService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var chatView: ComposeView? = null
    private var chatParams: WindowManager.LayoutParams? = null

    private val messages = mutableStateOf<List<AtomChatMessage>>(emptyList())
    private val streaming = mutableStateOf("")

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        observeBubbleSetting()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideChat()
        hideBubble()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun observeBubbleSetting() {
        lifecycleScope.launch {
            AppearanceSettingsRepository.settingsFlow().collect { settings ->
                if (settings.floatingBubbleEnabled) showBubble() else hideBubble()
            }
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val labels = AgentOrbLabels(
            hide = getString(R.string.bubble_hide),
            openChat = getString(R.string.bubble_open_chat),
            talk = getString(R.string.bubble_talk),
        )
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AtomBubbleService)
            setViewTreeSavedStateRegistryOwner(this@AtomBubbleService)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = AppearanceSettings())
                AgentAppTheme(appearance = appearance, systemOverlay = true) {
                    var expanded by remember { mutableStateOf(false) }
                    AgentOverlayBubble(
                        state = AgentOverlayState.Initial,
                        expanded = expanded,
                        labels = labels,
                        onToggleExpanded = { expanded = !expanded },
                        onAction = { action -> handleAction(action) },
                        onDrag = { dx, dy -> moveBubble(dx, dy) },
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = wm.currentWindowMetrics
            x = (metrics.bounds.width() - dpToPx(232)).coerceAtLeast(0)
            y = (metrics.bounds.height() * 0.55f).toInt()
            windowAnimations = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        bubbleView = view
        bubbleParams = lp
    }

    private fun moveBubble(dx: Float, dy: Float) {
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun handleAction(action: AgentOrbAction) {
        when (action) {
            AgentOrbAction.HIDE -> {
                hideBubble()
                lifecycleScope.launch {
                    AppearanceSettingsRepository.update {
                        it.copy(floatingBubbleEnabled = false)
                    }
                }
                stopSelf()
            }
            AgentOrbAction.OPEN_CHAT -> showChat()
            AgentOrbAction.TALK -> {
                val intent = Intent(this, AtomAssistantOverlayService::class.java)
                    .setAction(AtomAssistantOverlayService.ACTION_SHOW)
                runCatching { startForegroundService(intent) }
                    .recoverCatching { startService(intent) }
            }
        }
    }

    private fun showChat() {
        if (chatView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AtomBubbleService)
            setViewTreeSavedStateRegistryOwner(this@AtomBubbleService)
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = AppearanceSettings())
                AgentAppTheme(appearance = appearance, systemOverlay = true) {
                    val msgs by messages
                    val stream by streaming
                    AtomBubbleChat(
                        messages = msgs,
                        streaming = stream,
                        onSend = { sendChat(it) },
                        onClose = { hideChat() },
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            dpToPx(340),
            dpToPx(540),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            val metrics = wm.currentWindowMetrics
            x = dpToPx(12)
            y = dpToPx(12)
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        chatView = view
        chatParams = lp
    }

    private fun hideChat() {
        chatView?.let { view -> runCatching { windowManager?.removeView(view) } }
        chatView = null
        chatParams = null
    }

    private fun sendChat(text: String) {
        messages.value = messages.value + AtomChatMessage(fromUser = true, text = text)
        streaming.value = ""
        lifecycleScope.launch {
            try {
                val config = RuntimeConfigRepository.currentRuntimeConfig()
                if (config == null) {
                    streaming.value = ""
                    messages.value = messages.value + AtomChatMessage(
                        fromUser = false,
                        text = "No model configured. Open Atom and set an API key.",
                    )
                    return@launch
                }
                val controller = AgentRunController()
                val requestMessages = JSONArray()
                    .put(JSONObject().put("role", "system").put(
                        "content",
                        "You are Atom, a helpful AI assistant on Android. Reply concisely in the user's language.",
                    ))
                    .put(AgentConversationCodec.userTextMessage(text))
                val client = ProviderClientFactory.getClient(config)
                val handler = Handler(Looper.getMainLooper())
                val buffer = StringBuilder()
                val response = withContext(Dispatchers.IO) {
                    client.complete(
                        ProviderRequest(
                            config = config,
                            messages = requestMessages,
                            tools = JSONArray(),
                        ),
                        controller,
                    ) { event ->
                        if (event is ProviderEvent.BlockDelta &&
                            event.kind == AssistantBlockKind.TEXT
                        ) {
                            buffer.append(event.delta)
                            val snapshot = buffer.toString()
                            handler.post { streaming.value = snapshot }
                        }
                    }
                }
                val final = response.assistantMessage.optString("content")
                    .ifBlank { buffer.toString() }
                handler.post {
                    messages.value = messages.value +
                        AtomChatMessage(fromUser = false, text = final)
                    streaming.value = ""
                }
            } catch (e: Exception) {
                streaming.value = ""
                messages.value = messages.value + AtomChatMessage(
                    fromUser = false,
                    text = "Error: ${e.message ?: "unknown"}",
                )
            }
        }
    }

    private fun hideBubble() {
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        bubbleParams = null
        windowManager = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
