package io.github.namanwtf.atom.agent.overlay

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AtomChatMessage(val fromUser: Boolean, val text: String)

/**
 * The floating chat panel shown from the orb: a compact card with a header, a
 * scrollable transcript, an empty state that reads as intentional rather than a
 * blank box, and a rounded composer pinned to the bottom.
 *
 * Deliberately built from foundation primitives only — the app does not depend
 * on material3, and overlay windows must stay renderable on ROMs where the
 * squircle shader is unavailable.
 */
@Composable
internal fun AtomBubbleChat(
    messages: List<AtomChatMessage>,
    streaming: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue()) }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(26.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .background(OverlayPanelBackground, shape)
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Header(streaming = streaming.isNotEmpty(), onClose = onClose)

        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && streaming.isEmpty()) {
                EmptyState()
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                ) {
                    messages.forEach { message -> MessageRow(message) }
                    if (streaming.isNotEmpty()) {
                        MessageBubble(
                            text = "$streaming▍",
                            fromUser = false,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Composer(
            input = input,
            onInputChange = { input = it },
            onSend = {
                val text = input.text.trim()
                if (text.isNotEmpty()) {
                    onSend(text)
                    input = TextFieldValue()
                }
            },
        )
    }

    // Keep the newest message in view as replies stream in.
    LaunchedEffect(messages.size, streaming.length) {
        if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
}

private val OverlayPanelBackground = Color(0xFF0C0C12)
private val Accent = Color(0xFF2F6BFF)
private val Surface = Color(0xFF17171F)
private val Muted = Color(0xFF9AA0AE)

@Composable
private fun Header(streaming: Boolean, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0xFF14203C), CircleShape)
                .border(1.dp, Accent.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val r = size.minDimension / 2f
                drawCircle(color = Accent, radius = r * 0.5f, center = center)
                drawCircle(
                    color = Accent.copy(alpha = 0.35f),
                    radius = r,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            BasicText(
                "Atom",
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                if (streaming) "Replying…" else "Ready",
                style = TextStyle(color = Muted, fontSize = 11.sp),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color.White.copy(alpha = 0.06f), CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(12.dp)) {
                val stroke = 1.6.dp.toPx()
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    Offset(0f, 0f),
                    Offset(size.width, size.height),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    Offset(size.width, 0f),
                    Offset(0f, size.height),
                    stroke,
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(46.dp)) {
            val r = size.minDimension / 2f
            drawCircle(color = Accent.copy(alpha = 0.14f), radius = r, center = center)
            drawCircle(color = Accent, radius = r * 0.34f, center = center)
            drawCircle(
                color = Accent.copy(alpha = 0.45f),
                radius = r * 0.7f,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()),
            )
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Ask me anything",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Draft a reply, look something up, or hand me a task.",
            style = TextStyle(color = Muted, fontSize = 12.sp, lineHeight = 17.sp),
        )
    }
}

@Composable
private fun MessageRow(message: AtomChatMessage) {
    MessageBubble(text = message.text, fromUser = message.fromUser)
}

@Composable
private fun MessageBubble(text: String, fromUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 250.dp)
                .background(
                    color = if (fromUser) Accent else Surface,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (fromUser) 16.dp else 6.dp,
                        bottomEnd = if (fromUser) 6.dp else 16.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            BasicText(
                text,
                style = TextStyle(
                    color = if (fromUser) Color.White else Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
            )
        }
    }
}

@Composable
private fun Composer(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    val enabled = input.text.isNotBlank()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Surface, RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (input.text.isEmpty()) {
                        BasicText("Ask Atom…", style = TextStyle(color = Muted, fontSize = 13.sp))
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(if (enabled) Accent else Color.White.copy(alpha = 0.07f), CircleShape)
                .clickable(enabled = enabled) { onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = 2.dp.toPx()
                val tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f)
                drawLine(
                    tint,
                    Offset(size.width / 2f, size.height),
                    Offset(size.width / 2f, 0f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(tint, Offset(size.width / 2f, 0f), Offset(0f, size.height / 2f), stroke, StrokeCap.Round)
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
}
