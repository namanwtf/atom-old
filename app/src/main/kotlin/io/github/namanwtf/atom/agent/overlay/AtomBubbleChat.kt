package io.github.namanwtf.atom.agent.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.namanwtf.atom.R

internal data class AtomChatMessage(val fromUser: Boolean, val text: String)

/**
 * The panel that opens from the bubble: Buddy's layout (a header with the title
 * and a status chip, a transcript that runs the full height, and a rounded
 * composer with a single accent send button), rebuilt with foundation
 * primitives because this app does not depend on material3.
 */
@Composable
internal fun AtomBubbleChat(
    messages: List<AtomChatMessage>,
    streaming: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    title: String = "Atom",
    onInputFocusChanged: (Boolean) -> Unit = {},
    onNewChat: () -> Unit = {},
    onOpenInApp: () -> Unit = {},
    onMic: () -> Unit = {},
    modelLabel: String = "",
    onPickModel: () -> Unit = {},
) {
    var input by remember { mutableStateOf(TextFieldValue()) }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(24.dp)
    val replying = streaming.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtomOverlayColors.Background, shape)
            .border(1.dp, AtomOverlayColors.Outline, shape)
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = TextStyle(
                    color = AtomOverlayColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.weight(1f))
            ToolIcon(ToolGlyph.NEW) { onNewChat() }
            Spacer(Modifier.width(4.dp))
            ToolIcon(ToolGlyph.EXPAND) { onOpenInApp() }
            Spacer(Modifier.width(4.dp))
            ToolIcon(ToolGlyph.CLOSE) { onClose() }
        }

        Spacer(Modifier.height(8.dp))

        // Model chip, like Buddy's "Claude Code · Sonnet 5.5": the model name at
        // the same weight as the title, tappable to change it.
        Row(
            modifier = Modifier
                .background(AtomOverlayColors.Card2, RoundedCornerShape(12.dp))
                .clickable { onPickModel() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                modelLabel.ifBlank { "No model" },
                style = TextStyle(
                    color = AtomOverlayColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                messages.isEmpty() && !replying -> EmptyState()
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    messages.forEach { message -> MessageRow(message) }
                    if (replying) MessageRow(AtomChatMessage(fromUser = false, text = streaming))
                    Spacer(Modifier.height(2.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // One pill, like Buddy's: paperclip, field, mic, then the round send.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(AtomOverlayColors.Card, RoundedCornerShape(26.dp))
                    .border(1.dp, AtomOverlayColors.Outline, RoundedCornerShape(26.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = AtomOverlayColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AtomOverlayColors.Bolt),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .onFocusChanged { onInputFocusChanged(it.isFocused) },
                    decorationBox = { inner ->
                        if (input.text.isEmpty()) {
                            BasicText(
                                "Ask Atom…",
                                style = TextStyle(color = AtomOverlayColors.Muted, fontSize = 14.sp),
                            )
                        }
                        inner()
                    },
                )
                PillIcon(PillGlyph.MIC) { onMic() }
            }
            Spacer(Modifier.width(8.dp))
            SendButton(
                enabled = input.text.isNotBlank(),
                onClick = {
                    val text = input.text.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        input = TextFieldValue()
                    }
                },
            )
        }
    }

    LaunchedEffect(messages.size, streaming.length) {
        if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
}

private enum class PillGlyph { MIC }

@Composable
private fun PillIcon(glyph: PillGlyph, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.7.dp.toPx()
            val tint = AtomOverlayColors.Muted
            when (glyph) {
                PillGlyph.MIC -> {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(size.width * 0.36f, 0f),
                        size = Size(size.width * 0.28f, size.height * 0.52f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        color = tint,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.2f, size.height * 0.28f),
                        size = Size(size.width * 0.6f, size.height * 0.48f),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

private enum class ToolGlyph { NEW, EXPAND, CLOSE }

@Composable
private fun ToolIcon(glyph: ToolGlyph, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 1.8.dp.toPx()
            val tint = Color.White.copy(alpha = 0.85f)
            when (glyph) {
                ToolGlyph.NEW -> {
                    drawLine(tint, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), stroke, StrokeCap.Round)
                }
                ToolGlyph.EXPAND -> {
                    drawLine(tint, Offset(0f, 0f), Offset(size.width * 0.42f, 0f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(0f, 0f), Offset(0f, size.height * 0.42f), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(size.width, size.height), Offset(size.width * 0.58f, size.height), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(size.width, size.height), Offset(size.width, size.height * 0.58f), stroke, StrokeCap.Round)
                }
                ToolGlyph.CLOSE -> {
                    drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            // The Buddy-derived cartoon face reads off-brand here; the chat's
            // empty state is simple enough to use the real emblem directly
            // instead of waiting on the bubble face family's own redesign.
            painter = painterResource(R.drawable.atom_octopus_emblem),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Ask Atom anything",
            style = TextStyle(
                color = AtomOverlayColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Draft a reply, look something up, or hand me a task.",
            style = TextStyle(color = AtomOverlayColors.Muted, fontSize = 13.sp, lineHeight = 18.sp),
        )
    }
}

@Composable
private fun MessageRow(message: AtomChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (message.fromUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .background(AtomOverlayColors.User, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                BasicText(
                    message.text,
                    style = TextStyle(
                        color = AtomOverlayColors.Text.copy(alpha = 0.95f),
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                    ),
                )
            }
        } else {
            BasicText(
                message.text,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                style = TextStyle(
                    color = AtomOverlayColors.Text.copy(alpha = 0.95f),
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                ),
            )
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(
                if (enabled) AtomOverlayColors.Bolt else AtomOverlayColors.Card2,
                CircleShape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val tint = if (enabled) Color.White else AtomOverlayColors.Muted
            val stroke = 2.2.dp.toPx()
            drawLine(
                tint,
                Offset(size.width / 2f, size.height),
                Offset(size.width / 2f, 0f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                tint,
                Offset(size.width / 2f, 0f),
                Offset(0f, size.height / 2f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                tint,
                Offset(size.width / 2f, 0f),
                Offset(size.width, size.height / 2f),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}
