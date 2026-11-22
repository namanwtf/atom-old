package io.github.namanwtf.atom.agent.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Actions reachable from the expanded orb, in arc order. */
internal enum class AgentOrbAction { HIDE, OPEN_CHAT, TALK }

/**
 * Atom's floating orb: a dragged-anywhere bubble that expands into an arc of
 * Hide / Open chat / Talk, matching the three-satellite layout.
 *
 * Everything is drawn on a single Canvas so the overlay window never has to
 * re-measure mid-animation (the capsule window learned that lesson the hard way).
 * Callbacks stay semantic; the host owns window movement and provider wiring.
 */
@Composable
internal fun AgentOverlayBubble(
    state: AgentOverlayState,
    expanded: Boolean,
    labels: AgentOrbLabels,
    onToggleExpanded: () -> Unit,
    onAction: (AgentOrbAction) -> Unit,
    onDrag: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = overlayPhaseAccent(state.phase)
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200), RepeatMode.Restart),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Restart),
        label = "sweep",
    )
    val arc by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "arc",
    )

    val measurer = rememberTextMeasurer()
    val orbRadius = 34.dp
    val satelliteRadius = 21.dp
    val orbitRadius = 82.dp
    val padding = 8.dp

    Box(modifier = modifier.size(228.dp)) {
        Canvas(
            modifier = Modifier
                .size(228.dp)
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        val orb = orbCenter(size.width.toFloat(), size.height.toFloat(), orbRadius.toPx(), padding.toPx())
                        val orbHit = orbRadius.toPx() + 14.dp.toPx()
                        if ((point - orb).getDistance() <= orbHit) {
                            onToggleExpanded()
                            return@detectTapGestures
                        }
                        if (arc < 0.35f) return@detectTapGestures
                        AgentOrbAction.entries.forEachIndexed { index, action ->
                            val c = satelliteCenter(
                                orb = orb,
                                index = index,
                                orbitPx = orbitRadius.toPx() * arc,
                            )
                            if ((point - c).getDistance() <= satelliteRadius.toPx() + 12.dp.toPx()) {
                                onAction(action)
                                return@detectTapGestures
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    var insideOrb = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val orb = orbCenter(size.width.toFloat(), size.height.toFloat(), orbRadius.toPx(), padding.toPx())
                            insideOrb = (start - orb).getDistance() <= orbRadius.toPx() * 2.2f
                        },
                        onDragEnd = { insideOrb = false },
                        onDragCancel = { insideOrb = false },
                        onDrag = { change, dragAmount ->
                            if (!insideOrb) return@detectDragGestures
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                    )
                },
        ) {
            val orb = orbCenter(size.width.toFloat(), size.height.toFloat(), orbRadius.toPx(), padding.toPx())

            // Halo: soft radial bloom behind the orb.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.42f), Color.Transparent),
                    center = orb,
                    radius = orbRadius.toPx() * 2.6f * pulse,
                ),
                radius = orbRadius.toPx() * 2.6f * pulse,
                center = orb,
            )

            // Electric ring: broken arcs rotating at two speeds.
            drawElectricRing(orb, orbRadius.toPx() * 1.42f, accent, spin, sweep, 0.95f)
            drawElectricRing(orb, orbRadius.toPx() * 1.78f, accent, -spin * 0.62f, 1f - sweep, 0.45f)

            // Core body.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.95f),
                        accent.copy(alpha = 0.55f),
                        Color(0xFF0B0B0F),
                    ),
                    center = orb - Offset(orbRadius.toPx() * 0.25f, orbRadius.toPx() * 0.3f),
                    radius = orbRadius.toPx() * 1.5f,
                ),
                radius = orbRadius.toPx(),
                center = orb,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.16f),
                radius = orbRadius.toPx(),
                center = orb,
                style = Stroke(width = 1.2.dp.toPx()),
            )

            if (arc > 0.02f) {
                AgentOrbAction.entries.forEachIndexed { index, action ->
                    val c = satelliteCenter(orb, index, orbitRadius.toPx() * arc)
                    drawSatellite(
                        center = c,
                        radius = satelliteRadius.toPx(),
                        alpha = arc.coerceIn(0f, 1f),
                        accent = accent,
                        action = action,
                    )
                    drawLabel(
                        measurer = measurer,
                        text = labels.text(action),
                        center = c,
                        radius = satelliteRadius.toPx(),
                        alpha = arc.coerceIn(0f, 1f),
                    )
                }
            }
        }
    }
}

/** Localized labels for the three satellites, resolved by the host. */
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

private fun orbCenter(width: Float, height: Float, radius: Float, padding: Float): Offset =
    Offset(width - radius - padding, height - radius - padding)

/**
 * Satellites sit above the orb on a left-leaning arc: 200°, 250°, 300°
 * (screen angles, y down), so the orb keeps the bottom-right corner clear.
 */
private fun satelliteCenter(orb: Offset, index: Int, orbitPx: Float): Offset {
    val degrees = 200f + index * 50f
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(
        x = orb.x + (cos(rad) * orbitPx).toFloat(),
        y = orb.y + (sin(rad) * orbitPx).toFloat(),
    )
}

private fun DrawScope.drawElectricRing(
    center: Offset,
    radius: Float,
    accent: Color,
    rotationDeg: Float,
    phase: Float,
    alpha: Float,
) {
    // Three unequal segments read as "current" rather than a plain progress ring.
    val segments = listOf(0f to 92f, 128f to 64f, 212f to 120f)
    segments.forEach { (start, sweep) ->
        val jitter = 6f * sin(((phase * 2f + start / 360f) * 6.2831f).toDouble()).toFloat()
        drawArc(
            color = accent.copy(alpha = alpha * 0.85f),
            startAngle = start + rotationDeg + jitter,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    // One short, brighter bolt for the spark.
    val boltStart = (rotationDeg * 1.7f) % 360f
    drawArc(
        color = Color.White.copy(alpha = alpha * 0.55f),
        startAngle = boltStart,
        sweepAngle = 18f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawSatellite(
    center: Offset,
    radius: Float,
    alpha: Float,
    accent: Color,
    action: AgentOrbAction,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF1A1A22).copy(alpha = alpha),
                Color(0xFF101014).copy(alpha = alpha),
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = accent.copy(alpha = 0.55f * alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 1.4.dp.toPx()),
    )
    val glyph = Color.White.copy(alpha = 0.92f * alpha)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    when (action) {
        AgentOrbAction.HIDE -> {
            val d = radius * 0.38f
            drawLine(glyph, center - Offset(d, d), center + Offset(d, d), stroke.width, StrokeCap.Round)
            drawLine(glyph, center + Offset(d, -d), center - Offset(d, -d), stroke.width, StrokeCap.Round)
        }
        AgentOrbAction.OPEN_CHAT -> {
            val w = radius * 0.86f
            val h = radius * 0.62f
            val topLeft = Offset(center.x - w / 2f, center.y - h / 2f - radius * 0.06f)
            drawRoundRect(
                color = glyph,
                topLeft = topLeft,
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = stroke,
            )
            drawLine(
                glyph,
                Offset(center.x, topLeft.y + h),
                Offset(center.x, topLeft.y + h + radius * 0.22f),
                stroke.width,
                StrokeCap.Round,
            )
        }
        AgentOrbAction.TALK -> {
            val path = Path().apply {
                moveTo(center.x - radius * 0.22f, center.y - radius * 0.30f)
                lineTo(center.x + radius * 0.10f, center.y - radius * 0.30f)
                lineTo(center.x + radius * 0.38f, center.y - radius * 0.58f)
                lineTo(center.x + radius * 0.38f, center.y + radius * 0.58f)
                lineTo(center.x + radius * 0.10f, center.y + radius * 0.30f)
                lineTo(center.x - radius * 0.22f, center.y + radius * 0.30f)
                close()
                moveTo(center.x + radius * 0.52f, center.y - radius * 0.34f)
                quadraticTo(
                    center.x + radius * 0.82f,
                    center.y,
                    center.x + radius * 0.52f,
                    center.y + radius * 0.34f,
                )
            }
            drawPath(path, glyph, style = stroke)
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    if (alpha < 0.5f) return
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    drawText(
        textLayoutResult = layout,
        color = Color.White.copy(alpha = (alpha - 0.5f) * 2f),
        topLeft = Offset(
            x = center.x - layout.size.width / 2f,
            y = center.y + radius + 6.dp.toPx(),
        ),
    )
}
