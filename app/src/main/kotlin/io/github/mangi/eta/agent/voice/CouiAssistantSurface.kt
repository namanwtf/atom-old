package io.github.mangi.eta.agent.voice

import android.util.LruCache
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape as CouiCapsuleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiPressMotion
import io.github.mangi.eta.ui.components.coui.couiButtonFeedback
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
internal data class EtaVoicePanelColors(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val content: Color,
    val input: Color,
    val focusedInput: Color,
    val chipBackground: Color,
    val controlSurface: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val panelSpecular: Color,
    val chipStroke: Color,
    val idleScrim: Color,
    val sheetScrim: Color,
    val voiceBottomCushion: Color,
    val textBottomCushion: Color,
)

@Composable
internal fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val dark = if (isCoui) {
        CouiTheme.isDark
    } else {
        MiuixTheme.colorScheme.background.luminance() < 0.5f
    }
    val accent = if (isCoui) {
        CouiTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.primary
    }
    val destructive = if (isCoui) {
        CouiTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.error
    }
    return remember(dark, accent, destructive) {
        if (dark) {
            EtaVoicePanelColors(
                dark = true,
                accent = accent,
                onAccent = Color.White,
                error = destructive,
                // The reply panel background stays fully opaque so that, while continuously covering the navigation bar downward, no desktop ghosting shows through.
                content = Color(0xFF17191E),
                input = Color(0xF023262E),
                focusedInput = Color(0xFA2B2F39),
                chipBackground = Color(0xEB22252D),
                controlSurface = Color(0x1CFFFFFF),
                inputPrimary = Color(0xF2FFFFFF),
                inputSecondary = Color(0xB8FFFFFF),
                inputTertiary = Color(0x5CFFFFFF),
                tertiary = Color(0x36FFFFFF),
                panelSpecular = Color(0x29FFFFFF),
                chipStroke = Color(0x1EFFFFFF),
                idleScrim = Color(0x18000000),
                sheetScrim = Color(0x59000000),
                voiceBottomCushion = Color(0xBA060810),
                textBottomCushion = Color(0x880D1017),
            )
        } else {
            EtaVoicePanelColors(
                dark = false,
                accent = accent,
                onAccent = Color.White,
                error = destructive,
                content = Color(0xFFF7F8FA),
                // A soft porcelain white with a slight translucency, which with the inverted-clip shadow neither overexposes on dark wallpapers nor loses layering on light backgrounds.
                input = Color(0xF2FAFAFC),
                focusedInput = Color(0xFCFFFFFF),
                chipBackground = Color(0xEEFAFAFC),
                controlSurface = Color(0x0F11141A),
                inputPrimary = Color(0xEB11141A),
                inputSecondary = Color(0xA611141A),
                inputTertiary = Color(0x5411141A),
                tertiary = Color(0x2411141A),
                panelSpecular = Color(0xEBFFFFFF),
                chipStroke = Color(0x12000000),
                idleScrim = Color(0x0A000000),
                sheetScrim = Color(0x30000000),
                // In the voice state, the bottom uses a progressive dark tone to soften the desktop Dock's high-saturation icons, letting the bottom flowing light and floating pill emerge cleanly.
                voiceBottomCushion = Color(0x98080B14),
                textBottomCushion = Color(0x7CF3F5F9),
            )
        }
    }
}

/**
 * A half-screen panel outline using Oplus G2 continuous curvature at the top and right angles at the bottom, so the panel's bottom edge sits flush against the screen and navigation area.
 */
@Immutable
internal data class TopCouiSheetShape(
    val topRadius: Dp,
    val cornerWeight: Float = 2.6f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect.Zero)
        }
        val radiusPx = (topRadius.value * density.density)
            .coerceIn(0f, minOf(size.width * 0.5f, size.height))
        if (radiusPx <= 0.5f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        return Outline.Generic(buildTopCouiSheetPath(size, radiusPx, cornerWeight))
    }
}

private data class SheetCornerKey(val radiusPx: Int, val weightBits: Int)

private val topSheetCornerSamples = LruCache<SheetCornerKey, FloatArray>(8)

internal fun buildTopCouiSheetPath(
    size: Size,
    radiusPx: Float,
    cornerWeight: Float = 2.6f,
): Path {
    val r = radiusPx.coerceIn(0f, minOf(size.width * 0.5f, size.height))
    val path = Path()
    if (r <= 0.5f) {
        path.addRect(Rect(0f, 0f, size.width, size.height))
        return path
    }
    path.moveTo(0f, size.height)
    path.lineTo(0f, r)
    appendTopCouiRim(path, size.width, r, cornerWeight)
    path.lineTo(size.width, size.height)
    path.close()
    return path
}

internal fun buildTopCouiRimPath(
    size: Size,
    radiusPx: Float,
    cornerWeight: Float = 2.6f,
): Path {
    val r = radiusPx.coerceIn(0f, minOf(size.width * 0.5f, size.height))
    val path = Path()
    if (r <= 0.5f) {
        path.moveTo(0f, 0f)
        path.lineTo(size.width, 0f)
        return path
    }
    path.moveTo(0f, r)
    appendTopCouiRim(path, size.width, r, cornerWeight)
    return path
}

private fun appendTopCouiRim(
    path: Path,
    width: Float,
    r: Float,
    cornerWeight: Float,
) {
    val key = SheetCornerKey((r * 10f).toInt(), cornerWeight.toRawBits())
    val points = topSheetCornerSamples[key] ?: sampleQuarterLpCorner(r, cornerWeight).also {
        topSheetCornerSamples.put(key, it)
    }
    val count = points.size / 2
    // Top-left continuous corner: as i increases, (r - y, r - x) transitions clockwise from (0, r) to (r, 0).
    for (i in 0 until count) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        path.lineTo(r - y, r - x)
    }
    path.lineTo(width - r, 0f)
    // Top-right continuous corner: as i increases, (width - r + x, r - y) transitions clockwise from (width - r, 0) to (width, r).
    for (i in 0 until count) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        path.lineTo(width - r + x, r - y)
    }
}

private fun sampleQuarterLpCorner(radiusPx: Float, cornerWeight: Float): FloatArray {
    val weight = cornerWeight.coerceAtLeast(2f).toDouble()
    val diagonal = 0.5.pow(1.0 / weight)
    val segments = ceil(sqrt(radiusPx * (weight - 1.0) * diagonal / (4.0 * 0.05)))
        .toInt()
        .coerceIn(4, 48)
    val halfCorner = DoubleArray(segments + 1) { index ->
        (1.0 - (diagonal * index / segments).pow(weight)).pow(1.0 / weight)
    }
    val total = segments * 2 + 1
    val coords = FloatArray(total * 2)
    for (index in 0..segments * 2) {
        val firstHalf = index <= segments
        val halfIndex = if (firstHalf) index else segments * 2 - index
        val a = diagonal * halfIndex / segments
        val b = halfCorner[halfIndex]
        coords[index * 2] = (radiusPx * if (firstHalf) a else b).toFloat()
        coords[index * 2 + 1] = (radiusPx * if (firstHalf) b else a).toFloat()
    }
    return coords
}

internal enum class AssistantCapsuleElevation {
    Composer,
    Chip,
    InlineControl,
}

/**
 * The voice-assistant floating pill surface: shadows are enabled on the hardware-accelerated canvas, and it falls back to a solid fill and thin stroke of the same outline on a software canvas or during window transitions.
 */
@Composable
internal fun Modifier.couiAssistantCapsule(
    surfaceColor: Color,
    strokeColor: Color,
    dark: Boolean,
    elevation: AssistantCapsuleElevation = AssistantCapsuleElevation.Composer,
    highlightProgress: Float = 0f,
    highlightColor: Color = Color.Unspecified,
    interactive: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    pressMotion: CouiPressMotion? = null,
): Modifier {
    val shadow = when (elevation) {
        AssistantCapsuleElevation.Composer -> Shadow(
            radius = if (dark) 12.dp else 20.dp,
            color = Color.Black,
            alpha = if (dark) 0.36f else 0.13f,
        )
        AssistantCapsuleElevation.Chip -> Shadow(
            radius = if (dark) 8.dp else 14.dp,
            color = Color.Black,
            alpha = if (dark) 0.28f else 0.10f,
        )
        AssistantCapsuleElevation.InlineControl -> null
    }
    val feedback = if (interactive && interactionSource != null) {
        Modifier.couiButtonFeedback(dark, interactionSource, enabled = true)
    } else {
        Modifier
    }
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val shadowPainter = remember(shadowContext, shadow) {
        shadow?.let { shadowContext.createDropShadowPainter(CouiCapsuleShape, it) }
    }
    return this
        .then(if (pressMotion == null) Modifier else Modifier.graphicsLayer {
            scaleX = pressMotion.scale.value
            scaleY = pressMotion.scale.value
        })
        .drawBehind {
            if (shadowPainter != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                with(shadowPainter) { draw(size) }
            }
            val outline = CouiCapsuleShape.createOutline(size, layoutDirection, this)
            drawOutline(outline, surfaceColor)
            if (strokeColor.alpha > 0f) {
                drawOutline(outline, strokeColor, style = Stroke(width = 0.75.dp.toPx()))
            }
            if (highlightProgress > 0.01f && highlightColor != Color.Unspecified) {
                drawOutline(
                    outline = outline,
                    color = highlightColor.copy(alpha = highlightColor.alpha * highlightProgress),
                    style = Stroke(width = 1.15.dp.toPx()),
                )
            }
        }
        .clip(CouiCapsuleShape)
        .then(feedback)
}

internal enum class AssistantGlyphKind {
    Voice,
    Send,
    Stop,
    Keyboard,
}

/**
 * The action icon inside the assistant pill, avoiding the double-ring effect when an old vector with its own solid outer ring is placed in a circular button.
 */
@Composable
internal fun AssistantActionGlyph(
    kind: AssistantGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val u = minOf(w, h) / 24f
        when (kind) {
            AssistantGlyphKind.Voice -> {
                val stroke = 2.15f * u
                val centerY = h * 0.5f
                val bars = floatArrayOf(
                    6.2f, 3.0f,
                    10.1f, 6.6f,
                    13.9f, 4.8f,
                    17.8f, 2.6f,
                )
                for (i in bars.indices step 2) {
                    val x = bars[i] * u
                    val halfSpan = bars[i + 1] * u
                    drawLine(
                        color = tint,
                        start = Offset(x, centerY - halfSpan),
                        end = Offset(x, centerY + halfSpan),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            AssistantGlyphKind.Send -> {
                val stroke = 2.25f * u
                val centerX = w * 0.5f
                val topY = 5.6f * u
                val bottomY = 18.4f * u
                drawLine(
                    color = tint,
                    start = Offset(centerX, bottomY),
                    end = Offset(centerX, topY + 0.6f * u),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val wing = Path().apply {
                    moveTo(6.8f * u, 11.0f * u)
                    lineTo(centerX, topY)
                    lineTo(17.2f * u, 11.0f * u)
                }
                drawPath(
                    path = wing,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            AssistantGlyphKind.Stop -> {
                val side = 9.4f * u
                val topLeft = Offset((w - side) * 0.5f, (h - side) * 0.5f)
                drawRoundRect(
                    color = tint,
                    topLeft = topLeft,
                    size = Size(side, side),
                    cornerRadius = CornerRadius(2.5f * u, 2.5f * u),
                )
            }
            AssistantGlyphKind.Keyboard -> {
                val stroke = 1.75f * u
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(3.0f * u, 5.2f * u),
                    size = Size(18.0f * u, 13.6f * u),
                    cornerRadius = CornerRadius(3.4f * u, 3.4f * u),
                    style = Stroke(width = stroke),
                )
                val keyRadius = 1.05f * u
                for (rowY in floatArrayOf(9.2f, 12.2f)) {
                    for (colX in floatArrayOf(7.2f, 10.4f, 13.6f, 16.8f)) {
                        drawCircle(color = tint, radius = keyRadius, center = Offset(colX * u, rowY * u))
                    }
                }
                drawLine(
                    color = tint,
                    start = Offset(8.2f * u, 15.5f * u),
                    end = Offset(15.8f * u, 15.5f * u),
                    strokeWidth = 1.7f * u,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private val AiOrbColors = listOf(
    Color(0xFF3B82F6),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFFF59E0B),
    Color(0xFF3B82F6),
)

/**
 * The fluid-rhythm leading indicator of the voice pill: reads the mic level directly during the draw phase, drives four colorful waveform bars while listening,
 * and smoothly transitions into a rotating halo during the recognition phase, without overlaying a muddy base ring.
 */
@Composable
internal fun AssistantVoiceOrb(
    phase: EtaSpeechPhase,
    level: () -> Float,
    modifier: Modifier = Modifier.size(22.dp),
) {
    val transition = rememberInfiniteTransition(label = "assistant_voice_orb")
    val orbitPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "assistant_voice_orb_phase",
    )
    val spinDegrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "assistant_voice_orb_spin",
    )
    Canvas(modifier = modifier) {
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val radius = size.minDimension * 0.5f
        val gradient = Brush.linearGradient(
            colors = listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899)),
            start = Offset(0f, size.height),
            end = Offset(size.width, 0f),
        )
        if (phase == EtaSpeechPhase.RECOGNIZING) {
            val arcRadius = radius * 0.72f
            drawCircle(
                color = Color(0xFF8B5CF6).copy(alpha = 0.18f),
                radius = arcRadius,
                center = center,
                style = Stroke(width = 2.0.dp.toPx()),
            )
            drawArc(
                brush = Brush.sweepGradient(AiOrbColors, center),
                startAngle = spinDegrees,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round),
            )
        } else {
            val energy = level().coerceIn(0f, 1f)
            val stroke = 2.2.dp.toPx()
            val spacing = 4.0.dp.toPx()
            val startX = center.x - spacing * 1.5f
            val baseSpans = floatArrayOf(0.24f, 0.44f, 0.34f, 0.22f)
            val phaseOffsets = floatArrayOf(0f, 1.15f, 2.35f, 3.6f)
            for (index in 0 until 4) {
                val idleWave = (sin(orbitPhase + phaseOffsets[index]) * 0.5f + 0.5f) * 0.16f
                val voiceBoost = energy * (if (index == 1 || index == 2) 0.46f else 0.30f)
                val halfSpan = (radius * (baseSpans[index] + idleWave + voiceBoost))
                    .coerceIn(stroke * 0.65f, radius * 0.86f)
                val x = startX + index * spacing
                drawLine(
                    brush = gradient,
                    start = Offset(x, center.y - halfSpan),
                    end = Offset(x, center.y + halfSpan),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
