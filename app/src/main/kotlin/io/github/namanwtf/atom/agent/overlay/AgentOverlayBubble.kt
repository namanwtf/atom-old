package io.github.namanwtf.atom.agent.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.namanwtf.atom.R

/** Actions reachable from the bubble's hold menu. */
internal enum class AgentOrbAction { HIDE, OPEN_CHAT, TALK }

/** Buddy-derived palette (Apache-2.0) so the bubble and the panel share one look. */
internal object AtomOverlayColors {
    val Background = Color(0xFF121110)
    val Card = Color(0xFF1D1B19)
    val Card2 = Color(0xFF2A2724)
    val Text = Color(0xFFF3EFE8)
    val Muted = Color(0xFFA39D93)
    val Accent = Color(0xFFD97757)
    /** Overlay accent: Buddy's bubble, menu and arcs are blue, not clay. */
    val Bolt = Color(0xFF4F8BFF)
    val Spark = Color(0xFF7DF9FF)
    val OnAccent = Color(0xFF1A1714)
    val Outline = Color(0xFF2E2A26)
    val User = Color(0xFF4A3A2E)
}

/**
 * Atom's floating bubble: a 60dp face inside a 74dp orbit ring, exactly the
 * geometry Buddy uses (github.com/ghorbelhamdi/buddy-android, Apache-2.0), with
 * the same breathing and working-ring behaviour.
 *
 * Tap opens the panel, hold opens a small action menu, drag moves the window.
 * The bubble itself is tiny, so it never covers the app behind it.
 */
@Composable
internal fun AgentOverlayBubble(
    state: AgentOverlayState,
    expanded: Boolean,
    labels: AgentOrbLabels,
    onToggleExpanded: () -> Unit,
    onAction: (AgentOrbAction) -> Unit,
    onDrag: (x: Float, y: Float) -> Unit,
    onDragEnd: () -> Unit = {},
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val working = state.phase == AgentOverlayPhase.RUNNING
    val transition = rememberInfiniteTransition(label = "bubble")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )

    Row(
        modifier = modifier.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onAction(AgentOrbAction.OPEN_CHAT) },
                        onLongPress = { onToggleExpanded() },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (working) {
                Canvas(Modifier.size(74.dp)) {
                    drawWorkingRing(spin)
                }
            }

            Image(
                painter = painterResource(R.drawable.atom_bubble_face),
                contentDescription = "Atom",
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer {
                        // Buddy swells the face while it is being dragged.
                        val drag = if (dragging) 1.14f else 1f
                        scaleX = breath * drag
                        scaleY = breath * drag
                    },
            )
        }
    }
}

/** Localized labels for the hold menu, resolved by the host. */
internal class AgentOrbLabels(
    val hide: String,
    val openChat: String,
    val talk: String,
) {
    fun text(action: AgentOrbAction): String = when (action) {
        AgentOrbAction.HIDE -> hide
        AgentOrbAction.OPEN_CHAT -> openChat
        AgentOrbAction.TALK -> talk
    }
}

/** Three accent arcs swirling around the face while Atom is working. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWorkingRing(spin: Float) {
    val radius = size.minDimension / 2f - 1.5.dp.toPx()
    val center = Offset(size.width / 2f, size.height / 2f)
    val specs = listOf(
        Triple(260f, 95f, 0.92f),
        Triple(-190f, 70f, 0.66f),
        Triple(330f, 55f, 0.46f),
    )
    specs.forEach { (speed, length, alpha) ->
        drawArc(
            color = AtomOverlayColors.Accent.copy(alpha = alpha),
            startAngle = spin * (speed / 260f),
            sweepAngle = length,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMenuGlyph(action: AgentOrbAction) {
    val tint = AtomOverlayColors.Muted
    val stroke = 1.6.dp.toPx()
    when (action) {
        AgentOrbAction.HIDE -> {
            drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
        AgentOrbAction.OPEN_CHAT -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(0f, size.height * 0.12f),
                size = Size(size.width, size.height * 0.7f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
        AgentOrbAction.TALK -> {
            drawCircle(color = tint, radius = size.minDimension * 0.22f, center = center)
            drawArc(
                color = tint,
                startAngle = -40f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                size = Size(size.width * 0.76f, size.height * 0.76f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
