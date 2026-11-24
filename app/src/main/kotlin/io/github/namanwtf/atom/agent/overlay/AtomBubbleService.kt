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
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.accessibility.AgentAccessibilityService
import io.github.namanwtf.atom.agent.model.AgentConversationCodec
import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.agent.model.AssistantBlockKind
import io.github.namanwtf.atom.agent.model.ProviderClientFactory
import io.github.namanwtf.atom.agent.model.ProviderEvent
import io.github.namanwtf.atom.agent.model.ProviderRequest
import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.runtime.AgentExternalArchivePayload
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeClient
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeWire
import io.github.namanwtf.atom.core.AndroidAgentLogger
import io.github.namanwtf.atom.agent.voice.AtomAssistantOverlayService
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.repository.AppearanceSettingsRepository
import io.github.namanwtf.atom.data.repository.RuntimeConfigRepository
import io.github.namanwtf.atom.ui.MainActivity
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    private var panelBaseY: Int = 0
    private var panelLifted = false

    /** Real IME inset height in px, delivered only to accessibility overlays. */
    private var measuredImePx = 0

    /**
     * True once this chat window has reported a real IME inset at least once
     * (even a zero one). Distinguishes "this window type never gets IME
     * insets" from "it does, and the keyboard happens to be down right now" —
     * see [currentKeyboardHeightPx].
     */
    private var imeInsetsObserved = false

    /** The composer has focus — used only as a last-resort signal when no
     *  window actually measures the keyboard (see [currentKeyboardHeightPx]). */
    private var composerFocused = false

    /**
     * Corrects [composerFocused] back to false when the keyboard closes without
     * Compose ever reporting a focus-loss event.
     *
     * On this device neither real signal above ever fires reliably (no IME
     * insets land on this window, and the accessibility keyboardTop reads 0),
     * and dismissing the keyboard via the system back key or by tapping outside
     * the field does not always clear Compose's own focus state. Without this,
     * the panel stayed shrunk forever once the keyboard was dismissed that way.
     * Only runs while it is actually needed (no real signal available yet, and
     * the composer still thinks it has focus), and stops itself the moment
     * either condition changes — it never drives the panel height directly,
     * only corrects this one input to [currentKeyboardHeightPx].
     */
    private var imeWatchJob: Job? = null

    private fun overlayService(): AgentAccessibilityService? =
        runCatching { AgentAccessibilityService.current() }.getOrNull()

    private fun overlayType(): Int =
        if (overlayService() != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    private fun overlayWindowManager(): WindowManager? =
        (overlayService()?.let { it.getSystemService(WINDOW_SERVICE) } ?: getSystemService(WINDOW_SERVICE))
            as? WindowManager
    private var panelFullHeight = 0
    private val dragging = mutableStateOf(false)
    private val dragBubbleCenter = mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    private val dragFused = mutableStateOf(false)
    private var dismissView: ComposeView? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    private val prefs by lazy { getSharedPreferences("atom_overlay", MODE_PRIVATE) }
    private val modelLabel = mutableStateOf("")
    private var conversationKey = ""
    private var conversationHistory: List<AgentModelClient.ConversationMessage> = emptyList()
    private val historyFile by lazy { java.io.File(filesDir, "bubble_history.json") }
    private val messages = mutableStateOf<List<AtomChatMessage>>(emptyList())
    private val streaming = mutableStateOf("")
    private val bubbleReset = mutableStateOf(0)

    /** Measured size of the bubble itself (the status pill may sit beside it). */
    private var bubbleWidthPx = 0
    private var bubbleHeightPx = 0

    /** Where the user parked the bubble; the keyboard lift never overwrites it. */
    private var restingY = 0

    /** Top of the keyboard in screen pixels, 0 when it is hidden. */
    private var keyboardTopPx = 0

    /** The one-time "hold the bubble" hint goes away once the menu was opened. */
    private val menuHintPending = mutableStateOf(true)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        menuHintPending.value = !prefs.getBoolean(KEY_MENU_HINT_SEEN, false)
        conversationHistory = loadHistory()
        lifecycleScope.launch {
            AgentAccessibilityService.keyboardTop.collect { top ->
                if (top == keyboardTopPx) return@collect
                keyboardTopPx = top
                applyBubbleClearance()
                onKeyboardSignalChanged()
            }
        }
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
        val wm = overlayWindowManager() ?: return
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
                    val status by AtomBubbleStatusFeed.status.collectAsState()
                    // Buddy puts its status beside the face, so a glance at the
                    // bubble says what the agent is doing without opening the chat.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AtomBubbleStatusPill(
                            status = pillStatusWithHint(status),
                            onClick = { handleAction(AgentOrbAction.OPEN_CHAT) },
                        )
                        if (status.visible) Spacer(Modifier.width(6.dp))
                        AgentOverlayBubble(
                            // The persistent bubble is the only orb, so it follows
                            // the real run: the ring and the face are the state
                            // indicator, as on Buddy's bubble.
                            state = AgentOverlayState(phase = status.phase),
                            expanded = expanded,
                            labels = labels,
                            onToggleExpanded = { toggleMenu() },
                            onAction = { action ->
                                expanded = false
                                handleAction(action)
                            },
                            onDrag = { dx, dy -> moveBubble(dx, dy) },
                            onDragEnd = { endBubbleDrag() },
                            dragging = dragging.value,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                // The menu, the drag clamp and the snap all need the
                                // bubble's own box, not the window that also holds
                                // the status pill.
                                bubbleWidthPx = coordinates.size.width
                                bubbleHeightPx = coordinates.size.height
                            },
                        )
                    }
                }
            }
        }
        // Anchored to the right edge: the hold menu and the status pill grow
        // towards the middle of the screen instead of off it.
        val metrics = wm.currentWindowMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Come back where the user left it, not where we first put it.
            x = prefs.getInt(KEY_BUBBLE_X, -1).takeIf { it >= 0 } ?: dpToPx(6)
            restingY = prefs.getInt(KEY_BUBBLE_Y, -1).takeIf { it >= 0 }
                ?: (metrics.bounds.height() * 0.78f).toInt()
            y = restingY
            windowAnimations = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        bubbleView = view
        bubbleParams = lp
        // The bubble can be switched on while a keyboard is already up, and the
        // bubble's own box is only known after the first layout.
        view.post { applyBubbleClearance() }
    }

    private fun moveBubble(dx: Float, dy: Float) {
        hideMenu()
        if (!dragging.value) {
            dragging.value = true
            showDismissTarget()
        }
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val width = bubbleWidth()
        val height = bubbleHeight()

        // x counts from the right edge because the window is END-anchored.
        lp.x -= dx.toInt()
        lp.y += dy.toInt()

        val margin = dpToPx(6)
        lp.x = lp.x.coerceIn(margin, maxOf(margin, metrics.bounds.width() - width - margin))
        // The finger cannot park the bubble over the keys either: it stops at the
        // keyboard's top edge while one is up.
        lp.y = lp.y.coerceIn(margin, bubbleBottomLimit(wm, height))
        restingY = lp.y
        runCatching { wm.updateViewLayout(view, lp) }
        bubbleCenterOnScreen()?.let { center ->
            dragBubbleCenter.value = center
            val target = dismissTargetCenter(metrics)
            dragFused.value = kotlin.math.hypot(center.x - target.x, center.y - target.y) <= 96 * resources.displayMetrics.density
        }
    }

    /** The bubble's own width: the window also holds the status pill. */
    private fun bubbleWidth(): Int =
        bubbleWidthPx.takeIf { it > 0 } ?: dpToPx(82)

    private fun bubbleHeight(): Int =
        bubbleHeightPx.takeIf { it > 0 } ?: dpToPx(82)

    /**
     * The lowest y the bubble's top may take: above the navigation bar, and above
     * the keyboard when it is up.
     */
    private fun bubbleBottomLimit(wm: WindowManager, height: Int): Int {
        val metrics = wm.currentWindowMetrics
        val margin = dpToPx(6)
        val aboveNav = metrics.bounds.height() - navBarInset(wm) - height - margin
        val aboveKeyboard = if (keyboardTopPx > 0) keyboardTopPx - height - margin else aboveNav
        return minOf(aboveNav, aboveKeyboard).coerceAtLeast(margin)
    }

    /**
     * Keeps the bubble out of the keyboard's way.
     *
     * The place the user chose lives in [restingY] and in SharedPreferences; the
     * lift is only visual, so the bubble drops back to its spot as soon as the
     * keyboard closes instead of creeping upwards every time one opens.
     */
    private fun applyBubbleClearance() {
        if (dragging.value) return
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        val margin = dpToPx(6)
        val target = restingY.coerceIn(margin, bubbleBottomLimit(wm, bubbleHeight()))
        if (lp.y == target) return
        lp.y = target
        runCatching { wm.updateViewLayout(view, lp) }
        // The menu is anchored to where the bubble was when it opened.
        hideMenu()
    }

    // ── Radial hold menu (Buddy's RadialMenu) ──────────────────────────────
    private fun toggleMenu() {
        if (menuView != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val bubbleLp = bubbleParams ?: return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val bubbleWidth = bubbleWidth()
        val bubbleHeight = bubbleHeight()
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
            overlayType(),
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
        // The bubble stays visible: it is the pivot the arc radiates from.
        // Seeing the menu is what the one-time pill hint was asking for.
        if (menuHintPending.value) {
            menuHintPending.value = false
            prefs.edit().putBoolean(KEY_MENU_HINT_SEEN, true).apply()
        }
    }

    /** The pill copy, carrying the one-time "hold the bubble" hint while it applies. */
    private fun pillStatusWithHint(status: AtomBubbleStatus): AtomBubbleStatus {
        if (!status.visible || !menuHintPending.value) return status
        return status.copy(text = getString(R.string.bubble_pill_status_hold_hint, status.text))
    }

    private fun hideMenu() {
        menuView?.let { view -> runCatching { windowManager?.removeView(view) } }
        menuView = null
        menuParams = null
    }

    /**
     * Ends a drag: dropped on the X the bubble goes away, otherwise it snaps to
     * the nearest edge.
     */
    private fun endBubbleDrag() {
        val overTarget = isOverDismissTarget()
        dragging.value = false
        hideDismissTarget()
        if (overTarget) {
            hideBubble()
            lifecycleScope.launch {
                AppearanceSettingsRepository.update { it.copy(floatingBubbleEnabled = false) }
            }
            stopSelf()
        } else {
            snapBubble()
        }
    }

    /** The X target plus the Tesla-coil arcs to the bubble, in one not-touchable canvas. */
    private fun showDismissTarget() {
        if (dismissView != null) return
        val wm = windowManager ?: return
        val metrics = wm.currentWindowMetrics
        dragBubbleCenter.value = bubbleCenterOnScreen() ?: return
        dragFused.value = false
        val target = dismissTargetCenter(metrics)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AtomBubbleService)
            setViewTreeSavedStateRegistryOwner(this@AtomBubbleService)
            setContent {
                AtomDragTargetOverlay(
                    bubble = dragBubbleCenter.value,
                    target = target,
                    fused = dragFused.value,
                )
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            windowAnimations = 0
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        dismissView = view
        dismissParams = lp
    }

    private fun bubbleCenterOnScreen(): androidx.compose.ui.geometry.Offset? {
        val wm = windowManager ?: return null
        val lp = bubbleParams ?: return null
        val metrics = wm.currentWindowMetrics
        val width = bubbleWidth()
        val height = bubbleHeight()
        val left = metrics.bounds.width() - lp.x - width
        return androidx.compose.ui.geometry.Offset(
            left + width / 2f,
            lp.y + height / 2f,
        )
    }

    private fun dismissTargetCenter(metrics: android.view.WindowMetrics): androidx.compose.ui.geometry.Offset {
        val density = resources.displayMetrics.density
        val bottomInset = windowManager?.let { navBarInset(it) } ?: dpToPx(48)
        return androidx.compose.ui.geometry.Offset(
            metrics.bounds.width() / 2f,
            metrics.bounds.height() - bottomInset - 120 * density,
        )
    }

    private fun hideDismissTarget() {
        dismissView?.let { view -> runCatching { windowManager?.removeView(view) } }
        dismissView = null
        dismissParams = null
    }

    private fun isOverDismissTarget(): Boolean {
        val wm = windowManager ?: return false
        val lp = bubbleParams ?: return false
        val metrics = wm.currentWindowMetrics
        val density = resources.displayMetrics.density
        val bubbleWidth = bubbleWidth()
        val bubbleHeight = bubbleHeight()
        val centerX = metrics.bounds.width() - lp.x - bubbleWidth + bubbleWidth / 2f
        val centerY = lp.y + bubbleHeight / 2f
        val targetX = metrics.bounds.width() / 2f
        val targetY = metrics.bounds.height() - navBarInset(wm) - 120 * density
        val reach = 96 * density
        return kotlin.math.hypot(centerX - targetX, centerY - targetY) <= reach
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
        val width = bubbleWidth()
        val margin = dpToPx(6)
        val leftmost = metrics.bounds.width() - width - margin
        lp.x = if (lp.x > leftmost / 2) leftmost else margin
        lp.y = lp.y.coerceIn(margin, bubbleBottomLimit(wm, bubbleHeight()))
        restingY = lp.y
        saveBubblePosition(lp)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun saveBubblePosition(lp: WindowManager.LayoutParams) {
        prefs.edit().putInt(KEY_BUBBLE_X, lp.x).putInt(KEY_BUBBLE_Y, lp.y).apply()
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
        val wm = overlayWindowManager() ?: return
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
                        onInputFocusChanged = { focused ->
                            composerFocused = focused
                            onKeyboardSignalChanged()
                            if (focused) startImeWatchIfNeeded()
                        },
                        onNewChat = {
                            messages.value = emptyList()
                            streaming.value = ""
                            conversationHistory = emptyList()
                            conversationKey = ""
                            runCatching { historyFile.delete() }
                        },
                        onMic = { handleAction(AgentOrbAction.TALK) },
                        modelLabel = modelLabel.value,
                        onPickModel = { openModelPicker() },
                        onOpenInApp = {
                            val intent = Intent(this@AtomBubbleService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { startActivity(intent) }
                        },
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
        val gap = dpToPx(6)
        val chatWidth = (bounds.width() - gap * 2).coerceAtLeast(dpToPx(240))
        // Buddy's panel is a full-height sheet between the bars.
        val chatHeight = (bounds.height() - topInset - bottomInset - gap * 2)
            .coerceAtLeast(dpToPx(280))
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val lp = WindowManager.LayoutParams(
            chatWidth,
            chatHeight,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = topInset + gap
            dimAmount = 0.45f
            windowAnimations = 0
            // The panel lifts itself when the composer takes focus, so the
            // system must not try to resize an overlay window.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching { blurBehindRadius = dpToPx(16) }
            }
        }
        runCatching { wm.addView(view, lp) }.onFailure { return }
        windowManager = wm
        chatView = view
        chatParams = lp
        panelBaseY = lp.y
        panelLifted = false
        panelFullHeight = chatHeight
        refreshModelLabel()
        // With an accessibility overlay the IME inset actually arrives; follow it
        // frame by frame (the animation callback) and at rest (the listener) and
        // the panel lands exactly on the keyboard. Both write into the same
        // single authoritative path (see [currentKeyboardHeightPx]), so they
        // never disagree with each other the way the old four separate signals did.
        view.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            imeInsetsObserved = true
            if (ime != measuredImePx) {
                measuredImePx = ime
                onKeyboardSignalChanged()
            }
            insets
        }
        view.setWindowInsetsAnimationCallback(
            object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: android.view.WindowInsets,
                    running: MutableList<android.view.WindowInsetsAnimation>,
                ): android.view.WindowInsets {
                    val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    imeInsetsObserved = true
                    if (ime != measuredImePx) {
                        measuredImePx = ime
                        onKeyboardSignalChanged()
                    }
                    return insets
                }
            },
        )
        // The panel is the focused surface: hide the bubble so the two never
        // fight for the same corner of the screen.
        bubbleView?.visibility = View.GONE
        bubbleReset.value++
    }

    /**
     * The single authoritative keyboard height, in px (0 = hidden).
     *
     * Three independent signals used to each decide [panelLifted] and the panel
     * geometry on their own (accessibility `keyboardTop`, the insets listener,
     * the insets-animation callback, plus a 400ms `imm.isActive()` poll) and
     * raced each other — whichever last wrote `panelLifted` won, regardless of
     * whether its own measurement was still current. That cross-talk was the
     * jitter and the stuck-half-height bug.
     *
     * This is the only place that decides the keyboard height now, picked by
     * priority, never raced:
     * 1. The real IME window inset, once this window has reported one (even a
     *    zero one) — authoritative whenever it is delivered (accessibility
     *    overlay windows get it; see [imeInsetsObserved]).
     * 2. The accessibility service's measured keyboard top — used only while
     *    no IME inset has ever been observed for this window.
     * 3. A fixed share of the display — used only as a last resort, and only
     *    while the composer actually has focus, so it cannot fire on its own.
     *    [startImeWatchIfNeeded] corrects that focus flag back to false when
     *    this is the only available path and the keyboard closes without a
     *    Compose focus-loss event.
     */
    private fun currentKeyboardHeightPx(): Int {
        val wm = windowManager
        return when {
            imeInsetsObserved -> measuredImePx
            keyboardTopPx > 0 && wm != null ->
                (wm.currentWindowMetrics.bounds.height() - keyboardTopPx).coerceAtLeast(0)
            composerFocused -> (resources.displayMetrics.heightPixels * KEYBOARD_FALLBACK_SHARE).toInt()
            else -> 0
        }
    }

    /** Call after any of the inputs to [currentKeyboardHeightPx] changes. */
    private fun onKeyboardSignalChanged() {
        if (chatView == null) return
        val keyboard = currentKeyboardHeightPx()
        panelLifted = keyboard > 0
        refitPanelForKeyboard(keyboard)
    }

    /**
     * Starts the watch described on [imeWatchJob].
     *
     * Measured on device: this window's IME insets fire reliably for the
     * keyboard *showing* but never redeliver a closing event for a back-key
     * dismissal, so [imeInsetsObserved] (and [measuredImePx]) latch onto a
     * stale "open" reading forever once the real signal has fired even once.
     * The same can happen to [keyboardTopPx] from the accessibility service.
     * This does not try to guess which signal is unreliable ahead of time —
     * it runs whenever any of them currently claims the keyboard is up, and
     * corrects all three back to "closed" the moment the system's own
     * `imm.isActive()` disagrees. A real update to any signal after that still
     * overrides this correction immediately, same as before.
     */
    private fun startImeWatchIfNeeded() {
        if (imeWatchJob?.isActive == true) return
        imeWatchJob = lifecycleScope.launch {
            val imm = getSystemService(InputMethodManager::class.java)
            while (chatView != null) {
                val trackedHeight = currentKeyboardHeightPx()
                if (trackedHeight <= 0 && !composerFocused) break
                if (trackedHeight > 0 && imm != null && !imm.isActive()) {
                    measuredImePx = 0
                    keyboardTopPx = 0
                    composerFocused = false
                    onKeyboardSignalChanged()
                }
                delay(400)
            }
            imeWatchJob = null
        }
    }

    /** Fits the panel between its top edge and the keyboard's top edge. */
    private fun refitPanelForKeyboard(keyboard: Int = currentKeyboardHeightPx()) {
        val view = chatView ?: return
        val lp = chatParams ?: return
        val gap = dpToPx(6)
        val target = if (keyboard <= 0) {
            panelFullHeight
        } else {
            (panelFullHeight - keyboard - gap).coerceIn(dpToPx(280), panelFullHeight)
        }
        if (lp.height == target) return
        lp.height = target
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    /** Reads the configured model so the panel can name it. */
    private fun refreshModelLabel() {
        lifecycleScope.launch {
            val config = runCatching { RuntimeConfigRepository.currentRuntimeConfig() }.getOrNull()
            modelLabel.value = config?.let { runCatching { it.modelDisplayName.ifBlank { it.model } }.getOrNull() }.orEmpty()
        }
    }

    private fun openModelPicker() {
        hideChat()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun hideChat() {
        chatView?.let { view -> runCatching { windowManager?.removeView(view) } }
        chatView = null
        chatParams = null
        panelLifted = false
        // Reset every keyboard-signal input so a stale measurement from this
        // session (e.g. a leftover IME inset) cannot leak into the next time
        // the panel opens and start it already shrunk.
        measuredImePx = 0
        imeInsetsObserved = false
        composerFocused = false
        imeWatchJob?.cancel()
        imeWatchJob = null
        dockBubbleToBottom()
    }

    /**
     * Brings the bubble back after the panel closes — exactly where the user
     * left it. It used to jump to the bottom edge, which undid every drag.
     */
    private fun dockBubbleToBottom() {
        val view = bubbleView ?: return
        val lp = bubbleParams ?: return
        val wm = windowManager ?: return
        view.visibility = View.VISIBLE
        val margin = dpToPx(6)
        restingY = restingY.coerceIn(margin, bubbleBottomLimit(wm, bubbleHeight()))
        lp.y = restingY
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
                // Route through the real agent runtime, not a bare model call:
                // the bubble now has the full tool surface and streams the same
                // events the app's chat does.
                // One stable conversation key so every bubble message lands in
                // the same chat, and an external archive payload so the app
                // materialises the finished run into its conversation list.
                val key = ensureConversationKey()
                val runId = "bubble-${System.currentTimeMillis()}"
                val payload = AgentExternalArchivePayload(
                    userText = text,
                    conversationKey = key,
                    title = text.take(40),
                )
                val request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = text,
                    config = config,
                    images = emptyList(),
                    history = conversationHistory,
                    modelSessionId = key,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$key:$runId",
                        source = AgentRuntimeWire.ATOM_BUBBLE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                    ),
                )
                val handler = Handler(Looper.getMainLooper())
                val buffer = StringBuilder()
                val result = withContext(Dispatchers.IO) {
                    AgentRuntimeClient(this@AtomBubbleService, AndroidAgentLogger)
                        .run(request) { event ->
                            if (event is AgentEvent.AssistantBlockDelta &&
                                event.kind == AgentEvent.AssistantBlockKind.TEXT
                            ) {
                                buffer.append(event.delta)
                                val snapshot = buffer.toString()
                                handler.post { streaming.value = snapshot }
                            }
                        }
                }
                // Keep the conversation for the next message, the same way the
                // voice assistant carries its history across runs.
                if (result.contextSnapshot != null) {
                    conversationHistory = result.contextSnapshot.messages
                } else if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(text, emptyList()) +
                        result.transcript
                }
                saveHistory()
                val final = result.content.ifBlank { buffer.toString() }
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
        hideDismissTarget()
        dragging.value = false
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        bubbleParams = null
        windowManager = null
    }

    /**
     * The bubble is one long conversation: it persists to a file so a service
     * restart, a reboot or an update does not lose the thread. Only the explicit
     * "new chat" action clears it.
     */
    private fun loadHistory(): List<AgentModelClient.ConversationMessage> = runCatching {
        if (!historyFile.exists()) return emptyList()
        val array = org.json.JSONArray(historyFile.readText())
        buildList {
            for (i in 0 until array.length()) {
                add(AgentConversationCodec.fromJsonObject(array.getJSONObject(i)))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveHistory() {
        runCatching {
            val array = org.json.JSONArray()
            conversationHistory.forEach { array.put(AgentConversationCodec.toJsonObject(it)) }
            historyFile.writeText(array.toString())
        }
    }

    /** One stable conversation for the whole bubble, so its runs land together. */
    private fun ensureConversationKey(): String {
        if (conversationKey.isBlank()) {
            conversationKey = (prefs.getString(KEY_BUBBLE_CONVERSATION, "") ?: "")
                .takeIf { it.isNotBlank() }
                ?: "atom_bubble_${UUID.randomUUID()}".also { key ->
                    prefs.edit().putString(KEY_BUBBLE_CONVERSATION, key).apply()
                }
        }
        return conversationKey
    }

    private companion object {
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_MENU_HINT_SEEN = "menu_hint_seen"
        const val KEY_BUBBLE_CONVERSATION = "bubble_conversation"

        /**
         * Last-resort keyboard-height guess, as a share of the display, used only
         * when no window on this device has ever measured the real keyboard (see
         * [currentKeyboardHeightPx]). Not a bare fallback: it is gated on the
         * composer actually having focus, never applied on its own.
         */
        const val KEYBOARD_FALLBACK_SHARE = 0.32f
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
