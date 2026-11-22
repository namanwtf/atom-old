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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    title,
                    style = TextStyle(
                        color = AtomOverlayColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .background(AtomOverlayColors.Card2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText(
                        if (replying) "Replying…" else "Ready",
                        style = TextStyle(color = AtomOverlayColors.Muted, fontSize = 11.sp),
                    )
                }
            }
            CloseButton(onClose)
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(AtomOverlayColors.Card, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = AtomOverlayColors.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(AtomOverlayColors.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onInputFocusChanged(it.isFocused) },
                    decorationBox = { inner ->
                        if (input.text.isEmpty()) {
                            BasicText(
                                "Message Atom…",
                                style = TextStyle(color = AtomOverlayColors.Muted, fontSize = 14.sp),
                            )
                        }
                        inner()
                    },
                )
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

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(AtomOverlayColors.Card2, CircleShape)
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 1.8.dp.toPx()
            drawLine(
                AtomOverlayColors.Text,
                Offset(0f, 0f),
                Offset(size.width, size.height),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                AtomOverlayColors.Text,
                Offset(size.width, 0f),
                Offset(0f, size.height),
                stroke,
                StrokeCap.Round,
            )
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
            painter = painterResource(R.drawable.atom_bubble_face),
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
                        color = AtomOverlayColors.Text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        } else {
            BasicText(
                message.text,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                style = TextStyle(
                    color = AtomOverlayColors.Text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
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
                if (enabled) AtomOverlayColors.Accent else AtomOverlayColors.Card2,
                CircleShape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val tint = if (enabled) AtomOverlayColors.OnAccent else AtomOverlayColors.Muted
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
