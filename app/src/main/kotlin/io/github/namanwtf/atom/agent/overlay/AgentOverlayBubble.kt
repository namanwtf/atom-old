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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 * Atom's floating orb: a compact nucleus with an electron orbit and electric
 * arcs, anchored bottom right inside its window. Tapping expands a small arc of
 * Hide / Open chat / Talk satellites.
 *
 * Every radius and offset comes from [AtomOrbSpec], and the host sizes the
 * window from the same constants, so nothing can be clipped by the window edge.
 * The composable fills whatever window it is given: the host grows the window
 * when the arc opens and shrinks it again on collapse, which keeps the
 * transparent area that swallows touches as small as possible.
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
        animationSpec = infiniteRepeatable(tween(5200), RepeatMode.Restart),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "pulse",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000), RepeatMode.Restart),
        label = "sweep",
    )
    val arc by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "arc",
    )
    val currentArc by rememberUpdatedState(arc)
    val measurer = rememberTextMeasurer()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        val density = this.density
                        val orb = AtomOrbSpec.orbCenter(size.width.toFloat(), size.height.toFloat(), density)
                        val orbHit = AtomOrbSpec.orbRadius(density) + AtomOrbSpec.orbRadius(density) * 0.45f
                        if ((point - orb).getDistance() <= orbHit) {
                            onToggleExpanded()
                            return@detectTapGestures
                        }
                        val action = hitSatellite(point, orb, currentArc, density) ?: return@detectTapGestures
                        onAction(action)
                    }
                }
                .pointerInput(Unit) {
                    var insideOrb = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val density = this.density
                            val orb = AtomOrbSpec.orbCenter(size.width.toFloat(), size.height.toFloat(), density)
                            insideOrb = (start - orb).getDistance() <=
                                AtomOrbSpec.orbRadius(density) * 1.6f
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
            drawOrb(accent, spin, pulse, sweep, arc, measurer, labels)
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

private fun hitSatellite(point: Offset, orb: Offset, arc: Float, density: Float): AgentOrbAction? {
    if (arc < 0.6f) return null
    val orbit = AtomOrbSpec.orbit(density) * arc
    val radius = AtomOrbSpec.satelliteRadius(density)
    AgentOrbAction.entries.forEachIndexed { index, action ->
        val center = orb + AtomOrbSpec.satelliteOffset(index, orbit)
        if ((point - center).getDistance() <= radius * 1.35f) return action
    }
    return null
}

private fun DrawScope.drawOrb(
    accent: Color,
    spin: Float,
    pulse: Float,
    sweep: Float,
    arc: Float,
    measurer: TextMeasurer,
    labels: AgentOrbLabels,
) {
    val density = this.density
    val orbRadius = AtomOrbSpec.orbRadius(density)
    val satelliteRadius = AtomOrbSpec.satelliteRadius(density)
    val orbit = AtomOrbSpec.orbit(density)
    val orb = AtomOrbSpec.orbCenter(size.width, size.height, density)

    // Halo: a soft bloom that fades out well before the window edge.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.06f), Color.Transparent),
            center = orb,
            radius = orbRadius * 1.75f * pulse,
        ),
        radius = orbRadius * 1.75f * pulse,
        center = orb,
    )

    // Electric rings: two broken, counter-rotating arcs.
    drawElectricRing(orb, orbRadius * 1.22f, accent, spin, sweep, 0.85f)
    drawElectricRing(orb, orbRadius * 1.42f, accent, -spin * 0.62f, 1f - sweep, 0.38f)

    // Electron orbit: a tilted ellipse with two orbiting electrons.
    val orbitA = orbRadius * 1.52f
    val orbitB = orbRadius * 0.78f
    val electronRadius = 2.6.dp.toPx()
    drawOval(
        color = accent.copy(alpha = 0.26f),
        topLeft = Offset(orb.x - orbitA, orb.y - orbitB),
        size = Size(orbitA * 2f, orbitB * 2f),
        style = Stroke(width = 1.dp.toPx()),
    )
    for (phase in listOf(0f, 180f)) {
        val angle = Math.toRadians((spin + phase).toDouble())
        val electron = Offset(
            x = orb.x + (cos(angle) * orbitA).toFloat(),
            y = orb.y + (sin(angle) * orbitB).toFloat(),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.95f), accent.copy(alpha = 0.8f)),
                center = electron,
                radius = electronRadius * 2.4f,
            ),
            radius = electronRadius,
            center = electron,
        )
    }

    // Core body.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.95f),
                accent.copy(alpha = 0.5f),
                Color(0xFF0B0B10),
            ),
            center = orb - Offset(orbRadius * 0.22f, orbRadius * 0.28f),
            radius = orbRadius * 1.35f,
        ),
        radius = orbRadius,
        center = orb,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.14f),
        radius = orbRadius,
        center = orb,
        style = Stroke(width = 1.dp.toPx()),
    )

    if (arc <= 0.02f) return

    val alpha = arc.coerceIn(0f, 1f)
    val orbitNow = orbit * arc

    // A faint arc ties the satellites to the orb so the group reads as one control.
    drawArc(
        color = accent.copy(alpha = 0.18f * alpha),
        startAngle = AtomOrbSpec.ARC_START_DEGREES,
        sweepAngle = AtomOrbSpec.ARC_STEP_DEGREES * (AgentOrbAction.entries.size - 1),
        useCenter = false,
        topLeft = Offset(orb.x - orbitNow, orb.y - orbitNow),
        size = Size(orbitNow * 2f, orbitNow * 2f),
        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
    )

    AgentOrbAction.entries.forEachIndexed { index, action ->
        val center = orb + AtomOrbSpec.satelliteOffset(index, orbitNow)
        drawSatellite(center, satelliteRadius, alpha, accent, action)
        drawLabel(measurer, labels.text(action), center, satelliteRadius, alpha)
    }
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
        val jitter = 4f * sin(((phase * 2f + start / 360f) * 6.2831f).toDouble()).toFloat()
        drawArc(
            color = accent.copy(alpha = alpha * 0.8f),
            startAngle = start + rotationDeg + jitter,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    val boltStart = (rotationDeg * 1.7f) % 360f
    drawArc(
        color = Color.White.copy(alpha = alpha * 0.45f),
        startAngle = boltStart,
        sweepAngle = 14f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
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
        color = accent.copy(alpha = 0.5f * alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 1.2.dp.toPx()),
    )
    val glyph = Color.White.copy(alpha = 0.92f * alpha)
    val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round)
    when (action) {
        AgentOrbAction.HIDE -> {
            val d = radius * 0.34f
            drawLine(glyph, center - Offset(d, d), center + Offset(d, d), stroke.width, StrokeCap.Round)
            drawLine(glyph, center + Offset(d, -d), center - Offset(d, d), stroke.width, StrokeCap.Round)
        }
        AgentOrbAction.OPEN_CHAT -> {
            val w = radius * 0.86f
            val h = radius * 0.62f
            val topLeft = Offset(center.x - w / 2f, center.y - h / 2f - radius * 0.06f)
            drawRoundRect(
                color = glyph,
                topLeft = topLeft,
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()),
                style = stroke,
            )
            drawLine(
                glyph,
                Offset(center.x, topLeft.y + h),
                Offset(center.x, topLeft.y + h + radius * 0.2f),
                stroke.width,
                StrokeCap.Round,
            )
        }
        AgentOrbAction.TALK -> {
            val path = Path().apply {
                moveTo(center.x - radius * 0.2f, center.y - radius * 0.26f)
                lineTo(center.x + radius * 0.09f, center.y - radius * 0.26f)
                lineTo(center.x + radius * 0.33f, center.y - radius * 0.5f)
                lineTo(center.x + radius * 0.33f, center.y + radius * 0.5f)
                lineTo(center.x + radius * 0.09f, center.y + radius * 0.26f)
                lineTo(center.x - radius * 0.2f, center.y + radius * 0.26f)
                close()
                moveTo(center.x + radius * 0.46f, center.y - radius * 0.3f)
                quadraticTo(
                    center.x + radius * 0.72f,
                    center.y,
                    center.x + radius * 0.46f,
                    center.y + radius * 0.3f,
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
    if (alpha < 0.55f) return
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
    )
    drawText(
        textLayoutResult = layout,
        color = Color.White.copy(alpha = (alpha - 0.55f) * 2.2f * 0.9f),
        topLeft = Offset(
            x = center.x - layout.size.width / 2f,
            y = center.y + radius + AtomOrbSpec.LABEL_GAP_DP * density,
        ),
    )
}
