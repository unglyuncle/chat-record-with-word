package com.overmind.meetingscribe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmind.meetingscribe.asr.TranscriptSegment

val TranscriptChatBackground = Color(0xFFEDEDED)

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun displayEmotion(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val up = raw.uppercase()
    if (up.contains("NEUTRAL") || up.contains("UNKNOWN")) return null
    return when {
        up.contains("HAPPY") -> "😊 高兴"
        up.contains("SAD") -> "😔 低落"
        up.contains("ANGRY") -> "😠 生气"
        up.contains("FEAR") -> "😨 紧张"
        up.contains("DISGUST") -> "😖 厌恶"
        up.contains("SURPRISE") -> "😮 惊讶"
        else -> raw
    }
}

/** A finalized line, rendered as a group-chat bubble. Speakers alternate sides for a dialogue feel. */
@Composable
fun SegmentItem(
    segment: TranscriptSegment,
    onClick: () -> Unit = {},
    highlighted: Boolean = false,
) {
    val onRight = segment.speaker >= 0 && segment.speaker % 2 == 1
    val accent = speakerColor(segment.speaker)
    val bubbleColor = if (onRight) Color(0xFF95EC69) else Color.White
    val highlightColor = Color(0xFF07C160).copy(alpha = 0.13f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) {
                    highlightColor
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = if (onRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!onRight) {
            Avatar(segment.speaker, accent)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (onRight) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = speakerLabel(segment.speaker),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                displayEmotion(segment.emotion)?.let {
                    Spacer(Modifier.width(6.dp))
                    EmotionChip(it)
                }
            }
            Spacer(Modifier.height(4.dp))
            ChatBubble(onRight = onRight, color = bubbleColor, onClick = onClick) {
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF111111),
                )
            }
            if (segment.startMs > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatTimestamp(segment.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A8A8A),
                )
            }
        }
        if (onRight) {
            Spacer(Modifier.width(8.dp))
            Avatar(segment.speaker, accent)
        }
    }
}

/** The volatile live hypothesis, shown as a neutral left "typing" bubble. */
@Composable
fun PartialItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFB8D7A8)),
            contentAlignment = Alignment.Center,
        ) {
            Text("…", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(0.78f),
            shadowElevation = 0.5.dp,
        ) {
            Box(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF333333),
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(
    onRight: Boolean,
    color: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (onRight) 6.dp else 2.dp,
        bottomEnd = if (onRight) 2.dp else 6.dp,
    )
    Surface(onClick = onClick, color = color, shape = shape, shadowElevation = 0.5.dp) {
        Box(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) { content() }
    }
}

@Composable
private fun Avatar(speaker: Int, accent: Color) {
    Box(
        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (speaker < 0) "?" else "${speaker + 1}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmotionChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
