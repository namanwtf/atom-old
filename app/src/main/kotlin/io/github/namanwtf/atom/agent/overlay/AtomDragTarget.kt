package io.github.namanwtf.atom.agent.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The dismiss target and the Tesla-coil effect between it and the dragged
 * bubble, after Buddy's ArcView.java: both ends crackle, lightning reaches from
 * one to the other as they get closer, and it fuses into a continuous arc when
 * the bubble is over the target.
 *
 * Drawn in a full-screen, non-touchable overlay window; [bubble] and [target]
 * are screen coordinates.
 */
@Composable
internal fun AtomDragTargetOverlay(
    bubble: Offset,
    target: Offset,
    fused: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "arcs")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Restart),
        label = "flicker",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val targetRadius = 32.dp.toPx()
            val closeness = if (fused) {
                1f
            } else {
                (1f - hypot(target.x - bubble.x, target.y - bubble.y) / (460.dp.toPx()))
                    .coerceIn(0f, 1f)
            }

            // Halo around the target.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AtomOverlayColors.Accent.copy(alpha = 0.28f * (0.4f + closeness)),
                        Color.Transparent,
                    ),
                    center = target,
                    radius = 120.dp.toPx(),
                ),
                radius = 120.dp.toPx(),
                center = target,
            )

            // Sparks crackling on both ends.
            drawSparks(bubble, 44.dp.toPx(), phase, 0.55f + 0.35f * closeness)
            drawSparks(target, targetRadius, phase + 0.4f, 0.6f)

            // Lightning between the two, thicker and steadier as they close in.
            if (closeness > 0.02f) {
                val bolts = if (fused) 5 else 3
                repeat(bolts) { index ->
                    drawBolt(
                        from = bubble,
                        to = target,
                        seed = phase + index * 0.21f,
                        width = (if (fused) 2.6f else 1.8f).dp.toPx(),
                        alpha = (if (fused) 0.95f else 0.25f + 0.55f * closeness).coerceIn(0f, 1f),
                        offset = if (index % 2 == 0) 1f else -1f,
                    )
                }
            }
        }

        // The target itself: a round X, brighter once the bubble is on it.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (target.x - 32.dp.toPx()).toInt(),
                        (target.y - 32.dp.toPx()).toInt(),
                    )
                }
                .size(64.dp)
                .background(
                    if (fused) Color(0xFF2A1A16) else Color(0xF0121614),
                    CircleShape,
                )
                .border(
                    2.dp,
                    if (fused) Color.White else AtomOverlayColors.Accent.copy(alpha = 0.9f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 2.4.dp.toPx()
                val tint = if (fused) Color.White else AtomOverlayColors.Text
                drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
            }
        }
    }
}

/** Short bolts radiating from a point, regenerated every frame. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparks(
    center: Offset,
    radius: Float,
    phase: Float,
    alpha: Float,
) {
    val random = Random((phase * 1000).toInt())
    repeat(6) {
        val angle = random.nextFloat() * 6.2831f
        val length = radius * (0.55f + random.nextFloat() * 0.9f)
        val start = Offset(
            center.x + cos(angle) * radius * 0.9f,
            center.y + sin(angle) * radius * 0.9f,
        )
        val end = Offset(
            center.x + cos(angle) * (radius * 0.9f + length),
            center.y + sin(angle) * (radius * 0.9f + length),
        )
        drawLine(
            color = Color(0xFFBBD7FF).copy(alpha = alpha * 0.8f),
            start = start,
            end = end,
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** A jagged bolt between two points, with a perpendicular zig-zag. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBolt(
    from: Offset,
    to: Offset,
    seed: Float,
    width: Float,
    alpha: Float,
    offset: Float,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy)
    if (length < 1f) return
    val nx = -dy / length
    val ny = dx / length
    val random = Random((seed * 9973).toInt())
    val steps = 9
    var previous = from
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val wobble = if (i == steps) 0f else (random.nextFloat() - 0.5f) * length * 0.16f * offset
        val point = Offset(
            from.x + dx * t + nx * wobble,
            from.y + dy * t + ny * wobble,
        )
        drawLine(
            color = Color(0xFF7DF9FF).copy(alpha = alpha),
            start = previous,
            end = point,
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = alpha * 0.65f),
            start = previous,
            end = point,
            strokeWidth = width * 0.45f,
            cap = StrokeCap.Round,
        )
        previous = point
    }
}
