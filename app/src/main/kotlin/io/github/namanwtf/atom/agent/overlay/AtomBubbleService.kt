package io.github.namanwtf.atom.agent.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.View
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
    private var menuView: ComposeView? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private val messages = mutableStateOf<List<AtomChatMessage>>(emptyList())
    private val streaming = mutableStateOf("")
    private val bubbleReset = mutableStateOf(0)

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
                    var expanded by remember(bubbleReset.value) { mutableStateOf(false) }
                    AgentOverlayBubble(
                        // Idle: the persistent bubble is not attached to a run, so it
                        // must not draw the working ring.
                        state = AgentOverlayState(phase = AgentOverlayPhase.FINISHED),
                        expanded = expanded,
                        labels = labels,
                        onToggleExpanded = { toggleMenu() },
                        onAction = { action ->
                            expanded = false
                            handleAction(action)
                        },
                        onDrag = { dx, dy -> moveBubble(dx, dy) },
                        onDragEnd = { snapBubble() },
                    )
                }
            }
        }
        // Anchored to the right edge: the hold menu and the status pill grow
        // towards the middle of the screen instead of off it.
        val metrics = wm.currentWindowMetrics
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
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(6)
            y = (metrics.bounds.height() * 0.55f).toInt()
            windowAnimations = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        bubbleView = view
        bubbleParams = lp
    }

    private fun moveBubble(dx: Float, dy: Float) {
        hideMenu()
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val width = view.width.takeIf { it > 0 } ?: (74 * density).toInt()
        val height = view.height.takeIf { it > 0 } ?: (74 * density).toInt()

        // x counts from the right edge because the window is END-anchored.
        lp.x -= dx.toInt()
        lp.y += dy.toInt()

        val margin = dpToPx(6)
        val bottomInset = navBarInset(wm)
        lp.x = lp.x.coerceIn(margin, maxOf(margin, metrics.bounds.width() - width - margin))
        lp.y = lp.y.coerceIn(margin, maxOf(margin, metrics.bounds.height() - bottomInset - height - margin))
        runCatching { wm.updateViewLayout(view, lp) }
    }

    // ── Radial hold menu (Buddy's RadialMenu) ──────────────────────────────
    private fun toggleMenu() {
        if (menuView != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val bubble = bubbleView ?: return
        val bubbleLp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val bubbleWidth = bubble.width.takeIf { it > 0 } ?: (74 * density).toInt()
        val bubbleHeight = bubble.height.takeIf { it > 0 } ?: (74 * density).toInt()
        // x counts from the right edge because the bubble window is END-anchored.
        val left = metrics.bounds.width() - bubbleLp.x - bubbleWidth
        val centerX = left + bubbleWidth / 2
        val centerY = bubbleLp.y + bubbleHeight / 2
        val dockedRight = bubbleLp.x < metrics.bounds.width() / 2

        // Keep the window on screen, then draw the arc from wherever the bubble
        // actually sits inside it: a clamped window would otherwise put the
        // menu somewhere else entirely.
        val size = (ATOM_MENU_SIZE_DP * density).toInt()
        val originX = (centerX - size / 2)
            .coerceIn(0, (metrics.bounds.width() - size).coerceAtLeast(0))
        val originY = (centerY - size / 2)
            .coerceIn(0, (metrics.bounds.height() - size).coerceAtLeast(0))
        val anchorOffset = androidx.compose.ui.geometry.Offset(
            (centerX - originX).toFloat(),
            (centerY - originY).toFloat(),
        )

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
                    AtomOrbMenu(
                        labels = labels,
                        dockedRight = dockedRight,
                        anchor = anchorOffset,
                        onAction = { action ->
                            hideMenu()
                            bubbleView?.visibility = View.VISIBLE
                            handleAction(action)
                        },
                        onDismiss = { hideMenu() },
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = originX
            y = originY
            windowAnimations = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        menuView = view
        menuParams = lp
        bubble.visibility = View.GONE
    }

    private fun hideMenu() {
        menuView?.let { view -> runCatching { windowManager?.removeView(view) } }
        menuView = null
        menuParams = null
        bubbleView?.visibility = View.VISIBLE
    }

    /**
     * Parks the bubble against the nearest edge on release. Without this a
     * scroll that starts on the bubble drags it into the middle of the screen,
     * where it covers the app and looks stuck.
     */
    private fun snapBubble() {
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val width = view.width.takeIf { it > 0 } ?: (74 * density).toInt()
        val margin = dpToPx(6)
        val leftmost = metrics.bounds.width() - width - margin
        lp.x = if (lp.x > leftmost / 2) leftmost else margin
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun navBarInset(wm: WindowManager): Int = runCatching {
        wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            .bottom
    }.getOrDefault(dpToPx(48)).let { if (it > 0) it else dpToPx(48) }

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
        // Buddy's panel geometry: between the status bar and the navigation bar,
        // full height, the screen behind dimmed and blurred.
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = runCatching {
            metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        }.getOrNull()
        val topInset = insets?.top ?: 0
        val bottomInset = insets?.bottom ?: dpToPx(48)
        val gap = dpToPx(8)
        val chatWidth = (bounds.width() - gap * 2).coerceAtLeast(dpToPx(240))
        // A card, not a takeover: tall enough to read a conversation, short
        // enough that the app behind stays visible and reachable.
        val chatHeight = minOf(
            (bounds.height() * 0.62f).toInt(),
            bounds.height() - topInset - bottomInset - gap * 2,
        ).coerceAtLeast(dpToPx(220))
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val lp = WindowManager.LayoutParams(
            chatWidth,
            chatHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = bottomInset + gap
            dimAmount = 0.45f
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching { blurBehindRadius = dpToPx(16) }
            }
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        chatView = view
        chatParams = lp
        // The panel is the focused surface: hide the bubble so the two never
        // fight for the same corner of the screen.
        bubbleView?.visibility = View.GONE
        bubbleReset.value++
    }

    private fun hideChat() {
        chatView?.let { view -> runCatching { windowManager?.removeView(view) } }
        chatView = null
        chatParams = null
        dockBubbleToBottom()
    }

    /**
     * After the panel closes the bubble comes back at the bottom of the screen on
     * the edge it was parked on, which is where the thumb expects it.
     */
    private fun dockBubbleToBottom() {
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val width = view.width.takeIf { it > 0 } ?: (74 * density).toInt()
        val height = view.height.takeIf { it > 0 } ?: (74 * density).toInt()
        val margin = dpToPx(6)
        val leftmost = metrics.bounds.width() - width - margin
        lp.x = if (lp.x > leftmost / 2) leftmost else margin
        lp.y = (metrics.bounds.height() - navBarInset(wm) - height - dpToPx(24))
            .coerceAtLeast(margin)
        view.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(view, lp) }
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
        hideMenu()
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        bubbleParams = null
        windowManager = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
