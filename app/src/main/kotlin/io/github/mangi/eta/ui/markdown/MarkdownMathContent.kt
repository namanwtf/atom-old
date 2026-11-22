package io.github.mangi.eta.ui.markdown

import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ratex.RaTeXRenderer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import top.yukonga.miuix.kmp.basic.Text

@Immutable
internal data class InlineMathBoxMetrics(
    val boxWidthPx: Int,
    val boxHeightPx: Int,
    val contentWidthPx: Int,
    val mathFontSizePx: Float,
    val drawLeftPx: Float,
    val drawTopPx: Float,
    val scrollable: Boolean,
)

/**
 * Computes the precise placeholder box and draw offset of inline formulas within the host text line.
 *
 * `PlaceholderVerticalAlign.TextCenter` fixes the placeholder box center at the host font's `(ascent + descent) / 2`,
 * and extends the line metrics equally above and below when exceeding the original glyph height. By wrapping the formula's height-above-baseline
 * and depth-below-baseline symmetrically around that text midline, then deriving `topFromBaseline` to compute `drawTopPx`, the formula's math baseline aligns seamlessly with the body baseline,
 * while ordinary super/subscripts don't stretch CJK paragraph line spacing, and tall fractions and radicals don't collide with or get clipped by adjacent lines.
 */
internal fun computeInlineMathBoxMetrics(
    displayListWidthEm: Double,
    displayListHeightEm: Double,
    displayListDepthEm: Double,
    hasItems: Boolean,
    textFontSizePx: Float,
    fontAscentPx: Int,
    fontDescentPx: Int,
    leadingSpaceEm: Float,
    trailingSpaceEm: Float,
    maxAvailableWidthPx: Int,
): InlineMathBoxMetrics {
    val safeTextFontPx = textFontSizePx.coerceAtLeast(1f)
    val baseMathFontSizePx = safeTextFontPx * INLINE_MATH_FONT_SCALE
    val leadingPadPx = (INLINE_OVERHANG_SAFETY_EM + leadingSpaceEm) * safeTextFontPx
    val trailingPadPx = (INLINE_OVERHANG_SAFETY_EM + trailingSpaceEm) * safeTextFontPx
    val horizontalPadPx = leadingPadPx + trailingPadPx
    val naturalFormulaWidthPx = (displayListWidthEm * baseMathFontSizePx).toFloat().coerceAtLeast(0f)
    val safeMaxWidthPx = maxAvailableWidthPx.coerceAtLeast(ceil(horizontalPadPx + 8f).toInt())

    val mathFontSizePx: Float
    val scrollable: Boolean
    val contentWidthPx: Int
    val boxWidthPx: Int

    if (naturalFormulaWidthPx + horizontalPadPx <= safeMaxWidthPx) {
        mathFontSizePx = baseMathFontSizePx
        scrollable = false
        contentWidthPx = ceil(naturalFormulaWidthPx + horizontalPadPx).toInt().coerceAtLeast(1)
        boxWidthPx = contentWidthPx
    } else {
        val fitScale = if (naturalFormulaWidthPx > 0f) {
            ((safeMaxWidthPx - horizontalPadPx).coerceAtLeast(1f) / naturalFormulaWidthPx)
        } else {
            1f
        }
        if (fitScale >= MIN_INLINE_FIT_SCALE) {
            mathFontSizePx = baseMathFontSizePx * fitScale
            scrollable = false
            val scaledTotalPx = (displayListWidthEm * mathFontSizePx).toFloat() + horizontalPadPx
            contentWidthPx = ceil(scaledTotalPx).toInt().coerceIn(1, safeMaxWidthPx)
            boxWidthPx = contentWidthPx
        } else {
            // On narrow screens, cap the placeholder width of very long inline formulas to avoid StaticLayout forced line-break truncation, and allow horizontal scrolling inside the box.
            mathFontSizePx = baseMathFontSizePx * OVERFLOW_INLINE_FONT_SCALE
            scrollable = true
            val fullTotalPx = (displayListWidthEm * mathFontSizePx).toFloat() + horizontalPadPx
            contentWidthPx = ceil(fullTotalPx).toInt().coerceAtLeast(safeMaxWidthPx)
            boxWidthPx = safeMaxWidthPx
        }
    }

    val bleedPx = if (hasItems) 1f else 0f
    val heightAboveBaselinePx = (displayListHeightEm * mathFontSizePx).toFloat() + bleedPx
    val depthBelowBaselinePx = (displayListDepthEm * mathFontSizePx).toFloat() + bleedPx
    val textCenterPx = (fontAscentPx + fontDescentPx) / 2f
    val halfSpanPx = max(
        heightAboveBaselinePx + textCenterPx,
        depthBelowBaselinePx - textCenterPx,
    ).coerceAtLeast(1f)
    val boxHeightPx = ceil(2f * halfSpanPx).toInt() + 2
    val topFromBaselinePx = (fontAscentPx + fontDescentPx - boxHeightPx) / 2
    val drawTopPx = -topFromBaselinePx.toFloat() - heightAboveBaselinePx

    return InlineMathBoxMetrics(
        boxWidthPx = boxWidthPx,
        boxHeightPx = boxHeightPx,
        contentWidthPx = contentWidthPx,
        mathFontSizePx = mathFontSizePx,
        drawLeftPx = leadingPadPx,
        drawTopPx = drawTopPx,
        scrollable = scrollable,
    )
}

@Composable
internal fun rememberInlineMathContentMap(
    text: AnnotatedString,
    specs: List<InlineMathSpec>,
    textStyle: TextStyle,
    fallbackColor: Color,
    maxInlineWidthDp: Dp? = null,
): Map<String, InlineTextContent> {
    if (specs.isEmpty()) return emptyMap()
    val fontsReady = RaTeXFontRegistry.rememberFontsReady()
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxWidthPx = with(density) {
        when {
            maxInlineWidthDp != null -> maxInlineWidthDp.roundToPx()
            windowWidthPx > 0 -> (windowWidthPx - 56.dp.roundToPx()).coerceAtLeast(120.dp.roundToPx())
            else -> (screenWidthDp.dp.roundToPx() - 56.dp.roundToPx()).coerceAtLeast(120.dp.roundToPx())
        }
    }
    val baseColor = if (textStyle.color != Color.Unspecified) textStyle.color else fallbackColor
    val typeface = remember(
        fontFamilyResolver,
        textStyle.fontFamily,
        textStyle.fontWeight,
        textStyle.fontStyle,
        textStyle.fontSynthesis,
    ) {
        fontFamilyResolver.resolve(
            fontFamily = textStyle.fontFamily,
            fontWeight = textStyle.fontWeight ?: FontWeight.Normal,
            fontStyle = textStyle.fontStyle ?: FontStyle.Normal,
            fontSynthesis = textStyle.fontSynthesis ?: FontSynthesis.All,
        ).value as? Typeface
    }

    return remember(text, specs, textStyle.fontSize, baseColor, typeface, density, maxWidthPx, fontsReady) {
        val textFontSizePx = with(density) {
            if (textStyle.fontSize != TextUnit.Unspecified) textStyle.fontSize.toPx() else 16.sp.toPx()
        }
        val paint = TextPaint().apply {
            isAntiAlias = true
            this.typeface = typeface
            textSize = textFontSizePx
        }
        val fm = paint.fontMetricsInt

        buildMap(specs.size) {
            for (spec in specs) {
                val spanColor = text.resolveSpanColor(spec.rangeStart, spec.rangeEnd) ?: baseColor
                val metrics = computeInlineMathBoxMetrics(
                    displayListWidthEm = spec.displayList.width,
                    displayListHeightEm = spec.displayList.height,
                    displayListDepthEm = spec.displayList.depth,
                    hasItems = spec.displayList.items.isNotEmpty(),
                    textFontSizePx = textFontSizePx,
                    fontAscentPx = fm.ascent,
                    fontDescentPx = fm.descent,
                    leadingSpaceEm = spec.leadingSpaceEm,
                    trailingSpaceEm = spec.trailingSpaceEm,
                    maxAvailableWidthPx = maxWidthPx,
                )
                val colored = spec.displayList.bindDefaultColor(spanColor)
                val renderer = RaTeXRenderer(
                    colored,
                    metrics.mathFontSizePx,
                    RaTeXFontRegistry::resolveTypeface,
                )
                val placeholder = Placeholder(
                    width = density.exactPlaceholderSp(metrics.boxWidthPx),
                    height = density.exactPlaceholderSp(metrics.boxHeightPx),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
                put(
                    spec.id,
                    InlineTextContent(placeholder) {
                        if (metrics.scrollable) {
                            val contentWidthDp = with(density) { metrics.contentWidthPx.toDp() }
                            val inlineScroll = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .mathHorizontalOverflowFade(inlineScroll)
                                    .horizontalScroll(inlineScroll),
                            ) {
                                Spacer(
                                    modifier = Modifier
                                        .width(contentWidthDp)
                                        .fillMaxHeight()
                                        .drawBehind {
                                            if (!fontsReady) return@drawBehind
                                            drawIntoCanvas { canvas ->
                                                val native = canvas.nativeCanvas
                                                native.save()
                                                native.translate(metrics.drawLeftPx, metrics.drawTopPx)
                                                renderer.draw(native)
                                                native.restore()
                                            }
                                        },
                                )
                            }
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawBehind {
                                        if (!fontsReady) return@drawBehind
                                        drawIntoCanvas { canvas ->
                                            val native = canvas.nativeCanvas
                                            native.save()
                                            native.translate(metrics.drawLeftPx, metrics.drawTopPx)
                                            renderer.draw(native)
                                            native.restore()
                                        }
                                    },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun MarkdownDisplayMathBlock(
    block: MarkdownMath,
    style: MarkdownStyle,
    inQuote: Boolean,
    reveal: SmoothTextRevealCoordinator?,
    modifier: Modifier = Modifier,
) {
    val displayList = block.displayList
    if (displayList == null) {
        // On parse failure keep the full LaTeX source, drop no characters, and support horizontal scrolling and select-to-copy.
        val fallbackParagraph = remember(block.offset, block.rawSource) {
            MarkdownParagraph(offset = block.offset, text = AnnotatedString(block.rawSource))
        }
        val scrollState = rememberScrollState()
        Box(
            modifier = modifier
                .fillMaxWidth()
                .mathHorizontalOverflowFade(scrollState)
                .horizontalScroll(scrollState)
                .padding(vertical = 2.dp),
        ) {
            MarkdownMathFallbackText(
                block = fallbackParagraph,
                textStyle = style.code.copy(color = style.secondaryColor),
                reveal = reveal,
            )
        }
        return
    }

    val fontsReady = RaTeXFontRegistry.rememberFontsReady()
    val density = LocalDensity.current
    val baseFontSizeSp = when {
        style.tone == MarkdownTone.Thinking -> 14.25.sp
        inQuote -> 16.25.sp
        else -> 17.sp
    }
    val fontSizePx = with(density) { baseFontSizeSp.toPx() }
    val inkColor = style.body.color
    val renderer = remember(displayList, inkColor, fontSizePx) {
        RaTeXRenderer(
            displayList.bindDefaultColor(inkColor),
            fontSizePx,
            RaTeXFontRegistry::resolveTypeface,
        )
    }
    val formulaWidthPx = ceil(renderer.widthPx).toInt().coerceAtLeast(1)
    val formulaHeightPx = ceil(renderer.totalHeightPx).toInt().coerceAtLeast(1)

    val singleLineSource = remember(block.rawSource, block.latex) {
        block.rawSource.replace("\r\n", " ").replace('\n', ' ').ifEmpty { block.latex }
    }
    val annotated = remember(singleLineSource) {
        buildAnnotatedString {
            appendInlineContent(BLOCK_MATH_INLINE_ID, singleLineSource)
        }
    }
    val atomicSpans = remember(annotated) {
        listOf(RevealAtomicSpan(0, annotated.length))
    }
    val inlineContent = remember(renderer, formulaWidthPx, formulaHeightPx, density, fontsReady) {
        mapOf(
            BLOCK_MATH_INLINE_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = density.exactPlaceholderSp(formulaWidthPx),
                    height = density.exactPlaceholderSp(formulaHeightPx),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                ),
            ) {
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (!fontsReady) return@drawBehind
                            drawIntoCanvas { canvas ->
                                renderer.draw(canvas.nativeCanvas)
                            }
                        },
                )
            }
        )
    }
    val hostTextStyle = remember(style.body) {
        style.body.copy(
            fontSize = 1.sp,
            lineHeight = 1.sp,
            textMotion = TextMotion.Animated,
        )
    }

    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .mathHorizontalOverflowFade(scrollState)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (reveal == null) {
            Text(
                text = annotated,
                style = hostTextStyle,
                inlineContent = inlineContent,
                softWrap = false,
            )
        } else {
            val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
            Text(
                text = annotated,
                style = hostTextStyle,
                inlineContent = inlineContent,
                softWrap = false,
                modifier = Modifier.smoothTextReveal(revealState),
                onTextLayout = { layout ->
                    revealState.onTextLayout(annotated.text, layout, atomicSpans)
                },
            )
        }
    }
}

@Composable
private fun MarkdownMathFallbackText(
    block: MarkdownParagraph,
    textStyle: TextStyle,
    reveal: SmoothTextRevealCoordinator?,
) {
    if (reveal == null || block.text.isEmpty()) {
        Text(text = block.text, style = textStyle, softWrap = false)
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    val animatedStyle = remember(textStyle) { textStyle.copy(textMotion = TextMotion.Animated) }
    Text(
        text = block.text,
        style = animatedStyle,
        softWrap = false,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) },
    )
}

/**
 * Enable the offscreen layer and edge-fade hint only when the formula actually overflows the viewport and can scroll horizontally; ordinary short formulas keep zero offscreen cost.
 * Scroll offset is read only in the draw phase; dragging does not trigger recomposition.
 */
internal fun Modifier.mathHorizontalOverflowFade(scrollState: ScrollState): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (scrollState.maxValue > 0) {
            CompositingStrategy.Offscreen
        } else {
            CompositingStrategy.Auto
        }
    }
    .drawWithContent {
        drawContent()
        val maxScroll = scrollState.maxValue
        if (maxScroll <= 0 || size.width <= 0f) return@drawWithContent
        val maxFadePx = min(16.dp.toPx(), size.width * 0.12f)
        val leftFadePx = min(scrollState.value.toFloat(), maxFadePx)
        if (leftFadePx > 0.5f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = leftFadePx,
                ),
                size = Size(leftFadePx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        val rightFadePx = min((maxScroll - scrollState.value).toFloat(), maxFadePx)
        if (rightFadePx > 0.5f) {
            val startX = size.width - rightFadePx
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = startX,
                    endX = size.width,
                ),
                topLeft = Offset(startX, 0f),
                size = Size(rightFadePx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

private fun AnnotatedString.resolveSpanColor(start: Int, end: Int): Color? =
    spanStyles.lastOrNull { range ->
        range.start <= start && range.end >= end && range.item.color != Color.Unspecified
    }?.item?.color

private fun Density.exactPlaceholderSp(targetPx: Int): TextUnit =
    (targetPx - 0.02f).coerceAtLeast(0.5f).toSp()

private const val BLOCK_MATH_INLINE_ID = "eta_block_math"
private const val INLINE_MATH_FONT_SCALE = 1.06f
private const val INLINE_OVERHANG_SAFETY_EM = 0.05f
private const val MIN_INLINE_FIT_SCALE = 0.78f
private const val OVERFLOW_INLINE_FONT_SCALE = 0.92f
