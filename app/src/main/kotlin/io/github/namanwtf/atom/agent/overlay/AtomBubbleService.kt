package io.github.namanwtf.atom.agent.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
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
import io.github.namanwtf.atom.agent.voice.AtomAssistantOverlayService
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.repository.AppearanceSettingsRepository
import io.github.namanwtf.atom.ui.MainActivity
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import kotlinx.coroutines.launch

/**
 * Persistent floating bubble (the "Atom") — an always-on nucleus with an
 * electron orbit and electric arcs, independent of any agent run.
 *
 * Tap opens the full chat app; Talk starts the assistant voice overlay; Hide
 * detaches the window and turns the setting off.
 */
class AtomBubbleService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: android.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

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
        hideBubble()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    /** Mount/unmount the bubble whenever the floating-bubble setting changes. */
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
            AgentOrbAction.OPEN_CHAT -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            AgentOrbAction.TALK -> {
                val intent = Intent(this, AtomAssistantOverlayService::class.java)
                    .setAction(AtomAssistantOverlayService.ACTION_SHOW)
                runCatching { startForegroundService(intent) }
                    .recoverCatching { startService(intent) }
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
