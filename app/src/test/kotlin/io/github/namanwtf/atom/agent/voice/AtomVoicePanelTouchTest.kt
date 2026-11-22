package io.github.namanwtf.atom.agent.voice

import android.app.Application
import android.graphics.Insets
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.namanwtf.atom.data.model.AppearanceSettings
import io.github.namanwtf.atom.data.model.AppearanceThemeMode
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.ui.app.AgentAppTheme
import io.github.namanwtf.atom.ui.model.AgentMessageUi
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AtomVoicePanelTouchTest {
    @Test
    fun handleCanDragDownAfterEmptyEntryReceivesReply() = withPanel { panel ->
        panel.showReply()
        val before = panel.handleBounds()
        panel.dragBy(200f)
        val after = panel.handleBounds()
        assertTrue(
            "after a reply arrives the handle should drag downward: before=$before after=$after",
            after.top > before.top + panel.dp(30f),
        )
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun handleCanHandoffAfterReplyCompletesWithoutChangingBaseHeight() = withPanel { panel ->
        panel.showReply(processing = true)
        panel.state.value = panel.state.value.copy(phase = AtomVoicePhase.READY)
        panel.advance()
        panel.dragBy(-80f)
        assertEquals(1, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun cancelledHandleDragDoesNotHandoff() = withPanel { panel ->
        panel.showReply()
        panel.dragBy(-80f, cancel = true)
        assertEquals(0, panel.opened)
        assertEquals(0, panel.closed)
    }

    @Test
    fun streamingReplyGrowsMonotonicallyWithoutPostCompletionBounceOrDrop() = withPanel { panel ->
        for (lineCount in listOf(4, 10, 18)) {
            panel.state.value = AtomVoiceUiState(
                messages = listOf(
                    AgentMessageUi(
                        id = "reply",
                        content = (1..lineCount).joinToString("\n") { "Atom streaming reply line $it line" },
                        renderMarkdown = false,
                        isStreaming = true,
                    ),
                ),
                phase = AtomVoicePhase.PROCESSING,
            )
            panel.advance(12)
        }
        panel.advance(60)
        val topBeforeComplete = panel.handleBounds().top
        panel.state.value = AtomVoiceUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "reply",
                    content = (1..18).joinToString("\n") { "Atom streaming reply line $it line" },
                    renderMarkdown = false,
                    isStreaming = false,
                ),
            ),
            phase = AtomVoicePhase.READY,
        )
        var minTopAfterComplete = topBeforeComplete
        var maxTopAfterComplete = topBeforeComplete
        repeat(90) {
            panel.advance(1)
            val top = panel.handleBounds().top
            minTopAfterComplete = minOf(minTopAfterComplete, top)
            maxTopAfterComplete = maxOf(maxTopAfterComplete, top)
        }
        assertTrue(
            "After streaming ends there must be no large bounce up or fall down: before=$topBeforeComplete min=$minTopAfterComplete max=$maxTopAfterComplete",
            maxTopAfterComplete <= topBeforeComplete + 1f,
        )
    }

    @Test
    fun disabledRecognitionWaitsForBothWakeAndContentRevealBeforeKeyboard() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advance(10)
            assertEquals(0, panel.entrances)
            assertEquals(0, panel.keyboardShows)
            assertFalse(panel.hasTextInput())
            assertFalse(panel.hasMicrophone())
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            assertEquals(1, panel.entrances)
            assertTrue(panel.hasTextInput())
            assertEquals("Focus must not be handed over while the input bar is still revealing; actual reveal duration=${panel.revealDurationMs}", 0, panel.contentReveals)
            assertEquals(0, panel.keyboardShows)
            panel.advanceUntil { panel.contentReveals == 1 }
            assertTrue("The reveal must form a perceptible, distinct phase", panel.revealDurationMs >= 200)
            panel.advance(8)
            assertEquals(1, panel.contentReveals)
            assertTrue("After the entrance ends the input box must gain focus", panel.textInputFocused())
            assertTrue("After the input box gains focus it must request the keyboard", panel.keyboardShows > 0)
            assertFalse(panel.hasMicrophone())
            assertEquals(0, panel.microphoneStarts)
            panel.recognitionEnabled.value = true
            panel.advance()
            assertTrue(panel.hasMicrophone())
            assertEquals(1, panel.entrances)
            assertEquals(1, panel.contentReveals)
        }

    @Test
    fun revealedInputStillWaitsForWindowFocus() = withPanel(recognitionEnabled = false) { panel ->
        assertEquals(1, panel.contentReveals)
        assertEquals(0, panel.keyboardShows)
        panel.gainWindowFocus()
        panel.advance(8)
        assertTrue(panel.textInputFocused())
        assertTrue(panel.keyboardShows > 0)
    }

    @Test
    fun dismissingDuringTextRevealDoesNotRequestKeyboardLater() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            assertEquals(1, panel.entrances)
            assertEquals(0, panel.contentReveals)
            panel.close()
            panel.advance()
            assertEquals(0, panel.contentReveals)
            assertEquals(0, panel.keyboardShows)
        }

    @Test
    fun manualInputFocusDuringRevealCancelsAutomaticKeyboardRequest() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.gainWindowFocus()
            panel.advanceUntil { panel.entrances == 1 }
            panel.advance(1)
            panel.focusTextInput()
            panel.advance(3)
            assertTrue(panel.textInputFocused())
            val shows = panel.keyboardShows
            panel.advance()
            assertEquals(1, panel.contentReveals)
            assertEquals(shows, panel.keyboardShows)
        }

    @Test
    fun textInputTracksImeInsetsWithoutApplyingAnotherAnimation() =
        withPanel(recognitionEnabled = false) { panel ->
            panel.setImeBottom(0)
            panel.advance(2)
            val baseline = panel.textInputBottom()
            for (imeBottom in listOf(120, 240, 360, 240, 0)) {
                panel.setImeBottom(imeBottom)
                panel.advance(2)
                assertEquals("The input bar must stay in sync with the current keyboard bounds", baseline - imeBottom, panel.textInputBottom(), 1f)
            }
        }

    @Test
    fun replyBackgroundCoversNavigationAreaWhileInputAvoidsIt() {
        for (style in AppearanceUiStyle.entries) {
            for (theme in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
                withPanel(appearance = AppearanceSettings(uiStyle = style, themeMode = theme)) { panel ->
                    panel.showReply()
                    for (navigationBottom in listOf(48, 144)) {
                        panel.setBottomInsets(navigationBottom = navigationBottom)
                        panel.advance(3)
                        panel.assertNavigationBackgroundCovered(navigationBottom)
                    }
                }
            }
        }
    }

    @Test
    fun keyboardAndNavigationInsetsAreNotAddedTogether() = withPanel { panel ->
        panel.showReply()
        panel.setBottomInsets(navigationBottom = 48)
        panel.advance(3)
        val baseline = panel.textInputBottom()
        for (imeBottom in listOf(120, 240, 360, 240, 0)) {
            panel.setBottomInsets(navigationBottom = 48, imeBottom = imeBottom)
            panel.advance(3)
            assertEquals(baseline - (maxOf(48, imeBottom) - 48), panel.textInputBottom(), 1f)
        }
    }

    @Test
    fun inputAvoidsSideNavigationAndDisplayCutouts() = withPanel { panel ->
        panel.showReply()
        panel.setBottomInsets(navigationBottom = 0, navigationRight = 96, cutoutLeft = 72)
        panel.advance(3)
        panel.assertHorizontalInsetsRespected(left = 72, right = 96)
    }

    @Test
    fun dismissingDuringEntranceDoesNotRequestKeyboardLater() =
        withPanel(recognitionEnabled = false, initialFrames = 1) { panel ->
            panel.advance(20)
            panel.close()
            panel.advance()
            assertEquals(0, panel.entrances)
            assertEquals(0, panel.keyboardShows)
        }

    @Test
    fun voiceCapsuleSwitchesSmoothlyBetweenListeningAndKeyboard() = withPanel { panel ->
        panel.speech.value = AtomSpeechState(phase = AtomSpeechPhase.LISTENING)
        panel.advance(30)
        assertFalse(panel.hasTextInput())
        assertTrue(panel.hasActionDescription(R.string.voice_use_keyboard))
        assertTrue(panel.hasActionDescription(R.string.voice_finish_speech))
        panel.assertSuggestionCapsulesTopUnclipped()
        panel.clickActionDescription(R.string.voice_use_keyboard)
        assertEquals(1, panel.keyboardSwitches)
        panel.speech.value = AtomSpeechState()
        panel.advance(30)
        assertTrue(panel.hasTextInput())
        assertTrue(panel.hasMicrophone())
        panel.assertSuggestionCapsulesTopUnclipped()
    }

    @Test
    fun sheetTopEdgeCoversLeftAndRightHorizontalSpanWithoutDiagonalClip() = withPanel { panel ->
        panel.showReply()
        panel.assertSheetTopSpanUnclipped()
    }

    @Test
    fun handleDragTracksPointerWithoutJitterAcrossFrames() = withPanel { panel ->
        panel.showReply()
        panel.dragBy(240f)
        val startBounds = panel.handleBounds()
        val startX = startBounds.center.x
        val startY = startBounds.center.y
        val topsDuringMove = mutableListOf<Float>()
        val topsDuringHold = mutableListOf<Float>()
        var topBeforeRelease = startBounds.top
        var topRightAfterRelease = startBounds.top

        panel.runFrameSteppedTouch(
            startX = startX,
        ) { send, advanceOneFrame ->
            send(MotionEvent.ACTION_DOWN, startY)
            advanceOneFrame()
            var currentY = startY
            repeat(12) {
                currentY -= panel.dp(12f)
                send(MotionEvent.ACTION_MOVE, currentY)
                advanceOneFrame()
                topsDuringMove += panel.handleBounds().top
            }
            repeat(5) {
                send(MotionEvent.ACTION_MOVE, currentY)
                advanceOneFrame()
                topsDuringHold += panel.handleBounds().top
            }
            topBeforeRelease = panel.handleBounds().top
            send(MotionEvent.ACTION_UP, currentY)
            advanceOneFrame()
            topRightAfterRelease = panel.handleBounds().top
        }

        assertTrue("During the pull-up the top handle must move up significantly", topsDuringMove.last() < startBounds.top - panel.dp(80f))
        for (i in 1 until topsDuringMove.size) {
            assertTrue(
                "The pull-up must not jump backwards frame by frame: frame=${i - 1}->${i} prev=${topsDuringMove[i - 1]} cur=${topsDuringMove[i]}",
                topsDuringMove[i] <= topsDuringMove[i - 1] + 0.5f,
            )
        }
        val holdBaseline = topsDuringHold.first()
        for (holdTop in topsDuringHold) {
            assertEquals("The top handle must stay still and not oscillate while the finger is held", holdBaseline, holdTop, 0.5f)
        }
        assertTrue(
            "The first frame after release must not flash back to the pre-drag height: before=$topBeforeRelease after=$topRightAfterRelease",
            kotlin.math.abs(topRightAfterRelease - topBeforeRelease) <= panel.dp(14f),
        )
    }

    @Test
    fun listDragExpandsSheetSmoothlyWithoutJitter() = withPanel { panel ->
        panel.showReply()
        panel.dragBy(240f)
        val startBounds = panel.handleBounds()
        val listCenterX = startBounds.center.x
        val listStartY = startBounds.bottom + panel.dp(80f)
        val tops = mutableListOf<Float>()

        panel.runFrameSteppedTouch(
            startX = listCenterX,
        ) { send, advanceOneFrame ->
            send(MotionEvent.ACTION_DOWN, listStartY)
            advanceOneFrame()
            var currentY = listStartY
            repeat(10) {
                currentY -= panel.dp(12f)
                send(MotionEvent.ACTION_MOVE, currentY)
                advanceOneFrame()
                tops += panel.handleBounds().top
            }
            repeat(4) {
                send(MotionEvent.ACTION_MOVE, currentY)
                advanceOneFrame()
                tops += panel.handleBounds().top
            }
            send(MotionEvent.ACTION_UP, currentY)
            advanceOneFrame()
        }

        assertTrue("Pulling up from the list area should smoothly expand the panel", tops.last() < startBounds.top - panel.dp(60f))
        for (i in 1 until tops.size) {
            assertTrue(
                "While pulling up from the list area, the panel top must not jitter or jump back:prev=${tops[i - 1]} cur=${tops[i]}",
                tops[i] <= tops[i - 1] + 0.5f,
            )
        }
    }

    @Test
    fun sheetTopShadowDoesNotLeakOutsideRoundedCorners() =
        withPanel(appearance = AppearanceSettings(uiStyle = AppearanceUiStyle.COUI, themeMode = AppearanceThemeMode.LIGHT)) { panel ->
            panel.showReply()
            panel.assertTopCornerShadowNoRectangularCut()
        }

    private fun withPanel(
        recognitionEnabled: Boolean = true,
        initialFrames: Int = 180,
        appearance: AppearanceSettings = AppearanceSettings(),
        block: (Panel) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val panel = Panel(controller.get(), recognitionEnabled, appearance) { controller.windowFocusChanged(true) }
        try {
            panel.advance(initialFrames)
            block(panel)
        } finally {
            panel.close()
            controller.pause().stop().destroy()
        }
    }

    private class Panel(
        val activity: ComponentActivity,
        recognitionEnabled: Boolean,
        appearance: AppearanceSettings,
        private val onGainWindowFocus: () -> Unit,
    ) {
        val state = mutableStateOf(AtomVoiceUiState())
        val speech = mutableStateOf(AtomSpeechState())
        val recognitionEnabled = mutableStateOf(recognitionEnabled)
        private val entranceComplete = mutableStateOf(false)
        private val inputFocusRequestKey = mutableStateOf(-1)
        var entrances = 0
        var contentReveals = 0
        private var wakeFinishedAt = 0L
        var revealDurationMs = 0L
        private var inputActivated = false
        var keyboardShows = 0
        var keyboardSwitches = 0
        var microphoneStarts = 0
        private val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShows++ }
            override fun hide() = Unit
        }
        var opened = 0
        var closed = 0
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(appearance) {
                    CompositionLocalProvider(
                        LocalSquircleEnabled provides false,
                        LocalSoftwareKeyboardController provides keyboard,
                    ) {
                        AtomVoicePanel(
                            speechLevel = { 0f },
                            state = state.value,
                            input = remember { TextFieldState() },
                            speech = speech.value,
                            recognitionEnabled = this.recognitionEnabled.value,
                            entranceComplete = entranceComplete.value,
                            onEntranceFinished = {
                                entrances++
                                wakeFinishedAt = SystemClock.uptimeMillis()
                                entranceComplete.value = true
                            },
                            onContentReady = {
                                contentReveals++
                                revealDurationMs = SystemClock.uptimeMillis() - wakeFinishedAt
                                if (!this.recognitionEnabled.value && !inputActivated) inputFocusRequestKey.value++
                            },
                            onInputFocus = { inputActivated = true },
                            onMicrophone = { microphoneStarts++ },
                            onCancelSpeech = {},
                            onFinishSpeech = {},
                            onOpenSpeechSettings = {},
                            onKeyboard = { keyboardSwitches++ },
                            inputFocusRequestKey = inputFocusRequestKey.value,
                            canOpenConversation = state.value.phase == AtomVoicePhase.READY &&
                                state.value.messages.isNotEmpty(),
                            handoffRunning = false,
                            exitRequested = false,
                            onInputEdited = {},
                            onSuggestionClick = {},
                            onSubmit = {},
                            onStop = {},
                            onClose = { closed++ },
                            onOpenConversation = { opened++ },
                        )
                    }
                }
            }
            activity.setContentView(view)
        }

        fun close() {
            recomposer.cancel()
            scope.cancel()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }

        fun showReply(processing: Boolean = false) {
            state.value = AtomVoiceUiState(
                messages = listOf(
                    AgentMessageUi(
                        id = "reply",
                        content = (1..70).joinToString("\n") { "Atom test reply $it" },
                        renderMarkdown = false,
                    ),
                ),
                phase = if (processing) AtomVoicePhase.PROCESSING else AtomVoicePhase.READY,
            )
            advance()
        }

        fun advance(frames: Int = 180) {
            // Drive recomposition frames and layout manually so touch tests without a real display don't depend on background thread timing.
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }

        fun advanceUntil(condition: () -> Boolean) {
            repeat(180) {
                if (condition()) return
                advance(1)
            }
            assertTrue("the animation must finish within a bounded number of frames", condition())
        }

        fun dp(value: Float): Float = value * root().density.density

        fun gainWindowFocus() = onGainWindowFocus()

        fun hasTextInput(): Boolean = findTextInput(root().semanticsOwner.unmergedRootSemanticsNode) != null

        fun textInputFocused(): Boolean = findTextInput(root().semanticsOwner.unmergedRootSemanticsNode)
            ?.config?.getOrNull(SemanticsProperties.Focused) == true

        fun focusTextInput() {
            val node = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode))
            assertTrue(node.config[SemanticsActions.RequestFocus].action?.invoke() == true)
        }

        fun textInputBottom(): Float = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode))
            .boundsInRoot.bottom

        fun setImeBottom(bottom: Int) {
            root().view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsets.Type.ime(), bottom > 0)
                .build())
        }

        fun setBottomInsets(navigationBottom: Int, imeBottom: Int = 0, navigationRight: Int = 0, cutoutLeft: Int = 0) {
            root().view.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, navigationRight, navigationBottom))
                .setVisible(WindowInsets.Type.navigationBars(), true)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(cutoutLeft, 0, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .setVisible(WindowInsets.Type.ime(), imeBottom > 0)
                .build())
        }

        fun assertHorizontalInsetsRespected(left: Int, right: Int) {
            val bounds = requireNotNull(findTextInput(root().semanticsOwner.unmergedRootSemanticsNode)).boundsInRoot
            assertTrue("The input box must avoid the left punch hole", bounds.left >= left)
            assertTrue("The input box must avoid the side navigation bar", bounds.right <= root().view.width - right)
        }

        fun assertNavigationBackgroundCovered(navigationBottom: Int) {
            val view = root().view
            assertTrue("The input box must avoid the navigation area", textInputBottom() < view.height - navigationBottom)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val inset = dp(4f).toInt()
                val contentColor = bitmap.getPixel(view.width / 2, view.height - navigationBottom - inset)
                val navigationColor = bitmap.getPixel(view.width / 2, view.height - inset)
                assertEquals("The reply panel background must actually be painted opaque", 255, contentColor ushr 24)
                assertEquals("The floating window background must continuously cover the navigation area without exposing the window beneath", contentColor, navigationColor)
            } finally {
                bitmap.recycle()
            }
        }

        fun assertSuggestionCapsulesTopUnclipped() {
            val view = root().view
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                for (resId in listOf(
                    R.string.voice_i18n_suggestion_summarize_screen,
                    R.string.voice_i18n_suggestion_explain_page,
                    R.string.voice_i18n_suggestion_extract_key_info,
                )) {
                    val label = activity.getString(resId)
                    val node = requireNotNull(findByText(label)) { "suggestion pill not found:$label" }
                    val bounds = node.boundsInRoot
                    val sampleX = bounds.center.x.toInt()
                    val topPixel = bitmap.getPixel(sampleX, (bounds.top + dp(3f)).toInt())
                    val bottomPixel = bitmap.getPixel(sampleX, (bounds.bottom - dp(3f)).toInt())
                    assertTrue(
                        "The suggestion pill top must not be diagonally clipped: $label topAlpha=${topPixel ushr 24}",
                        (topPixel ushr 24) > 235,
                    )
                    assertTrue(
                        "Suggestion pill top/bottom edge brightness must be consistent: $label top=${Integer.toHexString(topPixel)} bottom=${Integer.toHexString(bottomPixel)}",
                        kotlin.math.abs(((topPixel shr 16) and 0xFF) - ((bottomPixel shr 16) and 0xFF)) <= 8,
                    )
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun assertSheetTopSpanUnclipped() {
            val view = root().view
            val handle = handleBounds()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val sampleY = (handle.top + dp(6f)).toInt()
                val leftPixel = bitmap.getPixel(dp(44f).toInt(), sampleY)
                val rightPixel = bitmap.getPixel((view.width - dp(44f)).toInt(), sampleY)
                assertEquals("Inside the reply panel's top-left corner the background must be fully covered", 255, leftPixel ushr 24)
                assertEquals("Inside the reply panel's top-right corner the background must be fully covered", 255, rightPixel ushr 24)
            } finally {
                bitmap.recycle()
            }
        }

        fun assertTopCornerShadowNoRectangularCut() {
            val view = root().view
            val handle = handleBounds()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val yAbove = (handle.top + dp(6f)).toInt()
                val yBelow = (handle.top + dp(10f)).toInt()
                val leftX = dp(2f).toInt()
                val rightX = (view.width - dp(2f)).toInt()
                val leftAboveAlpha = bitmap.getPixel(leftX, yAbove) ushr 24
                val leftBelowAlpha = bitmap.getPixel(leftX, yBelow) ushr 24
                val rightAboveAlpha = bitmap.getPixel(rightX, yAbove) ushr 24
                val rightBelowAlpha = bitmap.getPixel(rightX, yBelow) ushr 24
                assertTrue(
                    "No rectangular clipped shadow should appear outside the top-left corner: above=$leftAboveAlpha below=$leftBelowAlpha",
                    kotlin.math.abs(leftAboveAlpha - leftBelowAlpha) <= 4,
                )
                assertTrue(
                    "No rectangular clipped shadow should appear outside the top-right corner: above=$rightAboveAlpha below=$rightBelowAlpha",
                    kotlin.math.abs(rightAboveAlpha - rightBelowAlpha) <= 4,
                )
            } finally {
                bitmap.recycle()
            }
        }

        private fun findByText(text: String): SemanticsNode? {
            fun find(node: SemanticsNode): SemanticsNode? {
                val texts = node.config.getOrNull(SemanticsProperties.Text)
                if (texts?.any { it.text == text } == true &&
                    node.config.getOrNull(SemanticsProperties.Role) != null
                ) {
                    return node
                }
                return node.children.firstNotNullOfOrNull(::find)
            }
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
                ?: run {
                    fun findTextParent(node: SemanticsNode): SemanticsNode? {
                        if (node.children.any { child ->
                                child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
                            }
                        ) {
                            return node
                        }
                        return node.children.firstNotNullOfOrNull(::findTextParent)
                    }
                    findTextParent(root().semanticsOwner.unmergedRootSemanticsNode)
                }
        }

        fun hasMicrophone(): Boolean = hasActionDescription(R.string.voice_tap_to_speak)

        fun hasActionDescription(resId: Int): Boolean = findByDescription(resId) != null

        fun clickActionDescription(resId: Int) {
            val node = requireNotNull(findByDescription(resId))
            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
        }

        private fun findByDescription(resId: Int): SemanticsNode? {
            val description = activity.getString(resId)
            fun find(node: SemanticsNode): SemanticsNode? {
                if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) {
                    return node
                }
                return node.children.firstNotNullOfOrNull(::find)
            }
            return find(root().semanticsOwner.unmergedRootSemanticsNode)
        }

        private fun findTextInput(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
            return node.children.firstNotNullOfOrNull(::findTextInput)
        }

        fun handleBounds(): Rect = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)?.boundsInRoot
            ?: error("top handle not found")

        fun dragBy(distanceDp: Float, cancel: Boolean = false) {
            val view = root().view
            val bounds = handleBounds()
            val node = findHandle(root().semanticsOwner.unmergedRootSemanticsNode)!!
            assertTrue(
                "the handle should have finished layout: bounds=$bounds size=${node.size} " +
                    "position=${node.positionInRoot} view=${view.width}x${view.height}",
                bounds.width > 0 && bounds.height > 0,
            )
            val start = bounds.center
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, start.x, y, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            send(MotionEvent.ACTION_DOWN, start.y)
            repeat(10) { index ->
                send(MotionEvent.ACTION_MOVE, start.y + dp(distanceDp) * (index + 1) / 10f)
            }
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, start.y + dp(distanceDp))
            advance()
        }

        fun runFrameSteppedTouch(
            startX: Float,
            block: (send: (Int, Float) -> Unit, advanceOneFrame: () -> Unit) -> Unit,
        ) {
            val view = root().view
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, startX, y, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            block(::send) { advance(1) }
        }

        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("not found Compose Root")

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        private fun findHandle(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == "atom_assistant_drag_handle") return node
            for (child in node.children) findHandle(child)?.let { return it }
            return null
        }
    }
}
