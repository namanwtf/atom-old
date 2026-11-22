package io.github.namanwtf.atom.agent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Path
import io.github.namanwtf.atom.ui.components.coui.couiButtonFeedback
import io.github.namanwtf.atom.ui.components.coui.couiPressMotion
import io.github.namanwtf.atom.ui.components.coui.rememberCouiPressMotion
import io.github.suqi8.coui.kmp.squircle.SquircleShape as CouiShape
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.basic.IconButton
import androidx.compose.material.icons.rounded.ContentCopy
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.namanwtf.atom.ui.markdown.MarkdownTone
import io.github.namanwtf.atom.ui.markdown.StaticMarkdown
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.namanwtf.atom.data.model.AppearanceUiStyle
import io.github.namanwtf.atom.ui.app.LocalUiStyle
import io.github.namanwtf.atom.ui.components.coui.CouiButton
import io.github.namanwtf.atom.ui.components.coui.CouiButtonRole
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Miuix does not provide a semantic success color, so reuse the project's existing value; the failure color uses the theme error
private val SuccessColor = Color(0xFF34C759)


@Composable
internal fun overlayPhaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}

// The edge light flow takes the theme accent color and adjacent warm/cool colors, avoiding an over-saturated rainbow stealing the show on light pages.
private val EdgeLightColors = listOf(
    Color(0xFF7AB8FF),
    Color(0xFFB59CFF),
    Color(0xFFFF9EC7),
    Color(0xFFFFC98A),
    Color(0xFF7AB8FF),
)

/**
 * Screen edge glow window: full-screen touch pass-through (FLAG_NOT_TOUCHABLE), does not dim the page or block interaction.
 * Window type TYPE_ACCESSIBILITY_OVERLAY, filtered out by takeScreenshotOfWindow during screenshots, so it is transparent to the Agent.
 * - RUNNING: a thin light band flowing along the screen's rounded edges with a soft outer glow; tells the user the phone is being taken over.
 * - PAUSED: a static faint outline, hinting the task is still running and control has temporarily returned to the user.
 * - FINISHED / FAILED: not drawn.
 */
/**
 * Screen corner radii (pixels). Display reports only one radius for a circular arc, but the actual panel uses continuously-curved corners;
 * the light band draws a continuous curve with the same radius, fuller at the corners than a circular arc, hugging the screen edge instead of inset at the corners.
 */
@Immutable
internal data class ScreenCornerRadii(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float) {
    /** All four corners are equal on mainstream devices; take the maximum as the corner size of the continuous curve so no corner exposes a straight edge. */
    val radius: Float get() = maxOf(topLeft, topRight, bottomRight, bottomLeft)
}

@Composable
internal fun AgentOverlayGlow(
    state: AgentOverlayState,
    corners: ScreenCornerRadii,
    virtualDisplay: VirtualDisplaySnapshot = VirtualDisplaySnapshot.Inactive,
) {
    if (virtualDisplay.materializingToMain) {
        VirtualDisplayMaterializeOverlay(snapshot = virtualDisplay, corners = corners)
        return
    }
    // When the background virtual display is active, the physical screen belongs to the user, so no edge glow is drawn.
    if (virtualDisplay.active) return
    val phase = state.phase
    if (phase != AgentOverlayPhase.RUNNING && phase != AgentOverlayPhase.PAUSED) return
    val running = phase == AgentOverlayPhase.RUNNING
    val pausedAccent = overlayPhaseAccent(AgentOverlayPhase.PAUSED)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(420), label = "edge_reveal")

    val rotation = if (running) {
        val transition = rememberInfiniteTransition(label = "edge_light")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "edge_rotation",
        ).value
    } else 0f

    Box(
        modifier = Modifier.fillMaxSize().drawBehind {
            val density = this.density
            val inset = 1.5f * density
            // The light band follows a continuous curve along the screen outline: the path is inset inward and corners shrink accordingly, keeping equal distance from the screen edge.
            val edge = Path().apply {
                addSquircleRect(
                    width = size.width - inset * 2,
                    height = size.height - inset * 2,
                    cornerRadius = (corners.radius - inset).coerceAtLeast(0f),
                )
                translate(Offset(inset, inset))
            }.asAndroidPath()
            drawIntoCanvas { canvas ->
                if (!running) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 3f * density
                        color = pausedAccent.copy(alpha = 0.55f * reveal).toArgb()
                    }
                    canvas.nativeCanvas.drawPath(edge, paint)
                    return@drawIntoCanvas
                }
                val shader = android.graphics.SweepGradient(
                    size.width / 2f, size.height / 2f, EdgeLightColors.map { it.toArgb() }.toIntArray(), null,
                ).apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setRotate(rotation, size.width / 2f, size.height / 2f) })
                }
                // The outer soft glow and inner bright line share the same rotating gradient, so the band looks like it flows as one piece.
                val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 14f * density
                    maskFilter = android.graphics.BlurMaskFilter(12f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    this.shader = shader
                    alpha = (0.55f * reveal * 255).toInt()
                }
                val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f * density
                    this.shader = shader
                    alpha = (0.95f * reveal * 255).toInt()
                }
                canvas.nativeCanvas.drawPath(edge, halo)
                canvas.nativeCanvas.drawPath(edge, line)
            }
        }
    )
}

@Composable
internal fun OverlaySupplementInput(
    state: TextFieldState,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = MiuixTheme.colorScheme.onSurface
    val fieldBg = MiuixTheme.colorScheme.surfaceContainer

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fieldBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (state.text.isBlank()) {
                Text(
                    text = stringResource(R.string.overlay_supplement_hint),
                    color = textColor.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    minHeight = 32.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                CouiButton(
                    text = stringResource(R.string.overlay_send),
                    onClick = onSend,
                    enabled = state.text.isNotBlank(),
                    role = CouiButtonRole.Primary,
                    minHeight = 32.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onCancel,
                    minWidth = 44.dp,
                    minHeight = 32.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.overlay_send),
                    onClick = onSend,
                    enabled = state.text.isNotBlank(),
                    minWidth = 44.dp,
                    minHeight = 32.dp,
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * Result card shown after a task ends: a rounded panel floating up from the bottom, with a grab handle hint at the top that it can be swiped down to close.
 * Ways to close: auto-dismiss after no interaction, swipe down, system back (registered by the Service), and the bottom "done" button;
 * the secondary "copy" action sits on the same row, the body is scrollable, and when too long the bottom fades to hint there is more content.
 */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    onClose: () -> Unit,
    onOpenAtom: () -> Unit,
    hasVirtualDisplayApp: Boolean = false,
    onOpenVirtualApp: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scope = rememberCoroutineScope()
    fun close() {
        if (!visible) return
        visible = false
        // Wait for the exit animation to finish before removing the window, so the card is not yanked away instantly.
        scope.launch {
            delay(ResultCardExitMs.toLong())
            onClose()
        }
    }

    val isFailed = state.phase == AgentOverlayPhase.FAILED
    val accent = overlayPhaseAccent(state.phase)
    val statusLabel = stringResource(
        if (isFailed) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished,
    )
    val content = state.detailText.ifBlank { state.status.localizedText() }
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var touching by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val interacting = touching || scroll.isScrollInProgress || dragOffset > 0f
    LaunchedEffect(visible, interacting, interactionTick) {
        if (!visible || interacting) return@LaunchedEffect
        delay(ResultCardAutoDismissMs)
        close()
    }
    val settledOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "result_drag",
    )
    val dismissThreshold = with(density) { 96.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it } +
                fadeIn(tween(200)),
            exit = slideOutVertically(tween(ResultCardExitMs)) { it } + fadeOut(tween(ResultCardExitMs)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
            val couiDark = CouiTheme.isDark
            val cardShape = if (isCoui) CouiShape(32.dp, 2.6f) else RoundedCornerShape(ResultCardCorner)
            val cardBg = if (isCoui) {
                if (couiDark) Color(0xFF1D1F25) else Color(0xFFFCFCFE)
            } else {
                MiuixTheme.colorScheme.surface
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .graphicsLayer { translationY = settledOffset }
                    .shadow(
                        elevation = 16.dp,
                        shape = cardShape,
                        ambientColor = Color.Black.copy(alpha = if (isCoui && couiDark) 0.42f else 0.18f),
                    )
                    .clip(cardShape)
                    .background(cardBg, cardShape)
                    .then(
                        if (isCoui) {
                            Modifier.border(
                                width = 0.6.dp,
                                color = if (couiDark) Color(0x22FFFFFF) else Color(0x12000000),
                                shape = cardShape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touching = true
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                            touching = false
                            interactionTick++
                        }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragOffset > dismissThreshold) close() else dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f },
                        ) { change, amount ->
                            change.consume()
                            // Only downward drag is allowed; upward drag is damped, so the card is not pulled off the bottom edge.
                            dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isFailed) Icons.Rounded.Close else Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Box(modifier = Modifier.weight(1f, fill = false)) {
                    StaticMarkdown(
                        content = content,
                        tone = MarkdownTone.Answer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
                    )
                    if (scroll.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, cardBg),
                                    ),
                                ),
                        )
                    }
                }

                ResultCardActions(
                    content = content,
                    onOpenAtom = onOpenAtom,
                    onDone = ::close,
                    hasVirtualDisplayApp = hasVirtualDisplayApp,
                    onOpenVirtualApp = onOpenVirtualApp,
                )
            }
        }
    }
}

@Composable
private fun ResultCardActions(
    content: String,
    onOpenAtom: () -> Unit,
    onDone: () -> Unit,
    hasVirtualDisplayApp: Boolean = false,
    onOpenVirtualApp: (() -> Unit)? = null,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
        // Copy is an icon button; the two text buttons are the primary/secondary "done" (stay in the current app) and "back to Atom" or "open the display".
        if (isCoui) {
            val dark = CouiTheme.isDark
            val source = remember { MutableInteractionSource() }
            val press = rememberCouiPressMotion()
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = press.scale.value
                        scaleY = press.scale.value
                    }
                    .couiPressMotion(press)
                    .clip(CircleShape)
                    .background(if (dark) Color(0xFF353535) else Color(0xFFEBEBEB), CircleShape)
                    .couiButtonFeedback(dark, source, enabled = true)
                    .clickable(source, indication = null, role = Role.Button) {
                        clipboard.setText(AnnotatedString(content))
                        copied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d),
                    modifier = Modifier.size(18.dp),
                    tint = if (copied) CouiTheme.colorScheme.primary else CouiTheme.colorScheme.labelPrimary,
                )
            }
        } else {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(content))
                    copied = true
                },
                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                minWidth = 44.dp,
                minHeight = 44.dp,
                cornerRadius = 22.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
        val doneText = stringResource(R.string.overlay_result_done)
        val primaryText = if (hasVirtualDisplayApp && onOpenVirtualApp != null) {
            stringResource(R.string.overlay_result_open_virtual_app)
        } else {
            stringResource(R.string.overlay_result_open_eta)
        }
        val onPrimary = if (hasVirtualDisplayApp && onOpenVirtualApp != null) onOpenVirtualApp else onOpenAtom
        if (isCoui) {
            CouiButton(
                text = doneText,
                onClick = onDone,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            CouiButton(
                text = primaryText,
                onClick = onPrimary,
                role = CouiButtonRole.Primary,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
        } else {
            TextButton(
                text = doneText,
                onClick = onDone,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
            )
            TextButton(
                text = primaryText,
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
                minHeight = 44.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private val ResultCardCorner = 28.dp
private const val ResultCardExitMs = 200
private const val ResultCardAutoDismissMs = 8_000L
