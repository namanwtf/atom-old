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
import kotlin.math.hypot

/**
 * The dismiss target and a calm connecting beam between it and the dragged
 * bubble: a soft gradient line that brightens smoothly as they get closer and
 * fuses solid when the bubble is over the target. An earlier version ported
 * Buddy's Tesla-coil effect (ArcView.java) — random jagged lightning and
 * crackling sparks regenerated every frame — which read as busy and arcade-like
 * rather than matching the bubble's own minimal mark.
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
    val transition = rememberInfiniteTransition(label = "beam")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
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
                        AtomOverlayColors.Bolt.copy(alpha = 0.30f * (0.4f + closeness)),
                        Color.Transparent,
                    ),
                    center = target,
                    radius = 120.dp.toPx(),
                ),
                radius = 120.dp.toPx(),
                center = target,
            )

            // A single smooth beam, brightening and thickening as the bubble
            // nears the target, with a gentle pulse instead of flicker.
            if (closeness > 0.02f) {
                val width = ((if (fused) 3.2f else 1.8f) + pulse * 0.6f).dp.toPx()
                val alpha = (if (fused) 0.95f else 0.2f + 0.55f * closeness).coerceIn(0f, 1f)
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AtomOverlayColors.Bolt.copy(alpha = alpha),
                            AtomOverlayColors.Spark.copy(alpha = alpha),
                        ),
                        start = bubble,
                        end = target,
                    ),
                    start = bubble,
                    end = target,
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
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
                    if (fused) Color(0xFF14203C) else Color(0xF0121614),
                    CircleShape,
                )
                .border(
                    2.dp,
                    if (fused) Color.White else AtomOverlayColors.Bolt.copy(alpha = 0.95f),
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
