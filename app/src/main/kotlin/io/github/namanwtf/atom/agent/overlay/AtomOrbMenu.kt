package io.github.namanwtf.atom.agent.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

/** Window side for the radial menu: Buddy's 344dp box. */
internal const val ATOM_MENU_SIZE_DP = 344f
private const val RADIUS_DP = 112f
private const val ITEM_W_DP = 72f
private const val ITEM_H_DP = 92f
private const val ICON_DP = 52f

/**
 * Buddy's hold menu, ported: the bubble's actions fan out in an arc towards the
 * middle of the screen, joined to the bubble by energy lines with a travelling
 * spark, a pulsing halo and two expanding rings behind them
 * (RadialMenu.java / RadialMenu.Glow, Apache-2.0).
 */
@Composable
internal fun AtomOrbMenu(
    labels: AgentOrbLabels,
    dockedRight: Boolean,
    anchor: Offset,
    onAction: (AgentOrbAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { RADIUS_DP.dp.toPx() }
    // The arc radiates from the bubble itself, wherever the window had to be
    // placed to stay on screen.
    val center = anchor

    val actions = AgentOrbAction.entries
    val span = min(170f, 44f * (actions.size - 1))
    val mid = if (dockedRight) 180f else 0f

    val targets = remember(dockedRight, anchor) {
        actions.indices.map { index ->
            val angle = if (actions.size == 1) mid else mid - span / 2f + span * index / (actions.size - 1)
            val rad = Math.toRadians(angle.toDouble())
            Offset(
                x = center.x + (cos(rad) * radiusPx).toFloat(),
                y = center.y + (sin(rad) * radiusPx).toFloat(),
            )
        }
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f)) }

    val pulse by rememberInfiniteTransition(label = "menu").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "pulse",
    )

    Box(Modifier.fillMaxSize()) {
        // Dismiss surface: every touch inside the menu window that is not an
        // action closes it, like Buddy's outside-touch handling.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                    onDismiss()
                },
        )

        Canvas(Modifier.fillMaxSize()) {
            drawMenuGlow(center, targets, pulse, progress.value)
        }

        actions.forEachIndexed { index, action ->
            val t = targets[index]
            val itemAlpha = (progress.value * 1.4f - index * 0.15f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (t.x - ITEM_W_DP.dp.toPx() / 2f).roundToInt(),
                            (t.y - ITEM_H_DP.dp.toPx() / 2f).roundToInt(),
                        )
                    }
                    .width(ITEM_W_DP.dp)
                    .graphicsLayer {
                        alpha = itemAlpha
                        scaleX = 0.6f + 0.4f * itemAlpha
                        scaleY = 0.6f + 0.4f * itemAlpha
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                MenuItem(
                    action = action,
                    label = labels.text(action),
                    onClick = { onAction(action) },
                )
            }
        }
    }
}

@Composable
private fun MenuItem(action: AgentOrbAction, label: String, onClick: () -> Unit) {
    val tint = if (action == AgentOrbAction.HIDE) Color(0xFFE58A7A) else AtomOverlayColors.Accent
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(ITEM_W_DP.dp)) {
        Box(
            modifier = Modifier
                .size(ICON_DP.dp)
                .background(Color(0xF0181614), CircleShape)
                .border(1.5.dp, tint.copy(alpha = 0.8f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                drawActionGlyph(action, tint)
            }
        }
        BasicText(
            label,
            modifier = Modifier.width(ITEM_W_DP.dp),
            style = TextStyle(
                color = AtomOverlayColors.Text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
            ),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMenuGlow(
    center: Offset,
    targets: List<Offset>,
    pulse: Float,
    progress: Float,
) {
    // Soft halo behind the bubble.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                AtomOverlayColors.Accent.copy(alpha = 0.20f),
                Color.Transparent,
            ),
            center = center,
            radius = 84.dp.toPx(),
        ),
        radius = 84.dp.toPx(),
        center = center,
    )

    // Two expanding rings.
    repeat(2) { k ->
        val p = (pulse + k * 0.5f) % 1f
        drawCircle(
            color = AtomOverlayColors.Accent.copy(alpha = 0.5f * (1f - p)),
            radius = 38.dp.toPx() + p * 70.dp.toPx(),
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }

    // Energy lines with a spark running along each one.
    targets.forEachIndexed { index, target ->
        val dx = target.x - center.x
        val dy = target.y - center.y
        val length = hypot(dx, dy)
        if (length < 40.dp.toPx()) return@forEachIndexed
        val sx = center.x + dx / length * 36.dp.toPx()
        val sy = center.y + dy / length * 36.dp.toPx()
        val ex = target.x - dx / length * 28.dp.toPx()
        val ey = target.y - dy / length * 28.dp.toPx()
        val alpha = (0.4f * progress).coerceIn(0f, 1f)
        drawLine(
            color = AtomOverlayColors.Accent.copy(alpha = alpha),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val spark = (pulse * 1.6f + index * 0.17f) % 1f
        drawCircle(
            color = Color.White.copy(alpha = (0.8f * progress).coerceIn(0f, 1f)),
            radius = 1.8.dp.toPx(),
            center = Offset(sx + (ex - sx) * spark, sy + (ey - sy) * spark),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActionGlyph(
    action: AgentOrbAction,
    tint: Color,
) {
    val stroke = 1.8.dp.toPx()
    when (action) {
        AgentOrbAction.OPEN_CHAT -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(0f, size.height * 0.08f),
                size = Size(size.width, size.height * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                style = Stroke(width = stroke),
            )
            drawLine(
                tint,
                Offset(size.width * 0.3f, size.height * 0.8f),
                Offset(size.width * 0.3f, size.height),
                stroke,
                StrokeCap.Round,
            )
        }
        AgentOrbAction.TALK -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.37f, 0f),
                size = Size(size.width * 0.26f, size.height * 0.55f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = tint,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.22f, size.height * 0.3f),
                size = Size(size.width * 0.56f, size.height * 0.5f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        AgentOrbAction.HIDE -> {
            drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
