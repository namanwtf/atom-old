package io.github.namanwtf.atom.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AtomChatMessage(val fromUser: Boolean, val text: String)

/**
 * The floating chat panel shown when the bubble is tapped: a small message
 * list, an input, and a send button. Rendering stays minimal so it works in a
 * focusable overlay window.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Atom",
                style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                "✕",
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp),
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(horizontal = 8.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // Messages
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            messages.forEach { m ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    if (m.fromUser) Spacer(Modifier.width(40.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                if (m.fromUser) Color(0xFF1F6FEB) else Color(0xFF1A1A22),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        BasicText(
                            m.text,
                            style = TextStyle(color = Color.White, fontSize = 14.sp),
                        )
                    }
                }
            }
            if (streaming.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1A1A22), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicText(
                        streaming,
                        style = TextStyle(color = Color.White, fontSize = 14.sp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Input row
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF1A1A22), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box { if (input.text.isEmpty()) BasicText("Ask Atom…", style = TextStyle(color = Color.White.copy(alpha = 0.4f))); inner() }
                },
            )
            Spacer(Modifier.width(8.dp))
            BasicText(
                "Send",
                style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clickable {
                        val t = input.text.trim()
                        if (t.isNotEmpty()) { onSend(t); input = TextFieldValue() }
                    }
                    .background(Color(0xFF1F6FEB), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
