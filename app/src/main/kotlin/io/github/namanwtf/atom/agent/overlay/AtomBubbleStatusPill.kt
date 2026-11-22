package io.github.namanwtf.atom.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The status pill that sits beside the bubble, ported from Buddy's collapsed
 * pill (Bubble.java: rounded 18dp card, 1dp accent border, 13sp medium, at most
 * two lines, clickable). It says what the agent is doing while the panel is
 * closed; tapping it opens the chat.
 *
 * No size animation on purpose: the bubble window is WRAP_CONTENT, so animating
 * the pill's width would make the overlay window itself jitter.
 */
@Composable
internal fun AtomBubbleStatusPill(
    status: AtomBubbleStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!status.visible) return
    val accent = when (status.tone) {
        AtomBubbleStatus.Tone.PAUSED -> AtomOverlayColors.Muted
        else -> AtomOverlayColors.Bolt
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .widthIn(max = 250.dp)
            .clip(shape)
            .background(AtomOverlayColors.Card)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = status.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = AtomOverlayColors.Text,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
