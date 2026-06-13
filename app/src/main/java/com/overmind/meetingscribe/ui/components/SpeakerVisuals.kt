package com.overmind.meetingscribe.ui.components

import androidx.compose.ui.graphics.Color

private val SpeakerPalette = listOf(
    Color(0xFF0F766E),
    Color(0xFF2563EB),
    Color(0xFFB45309),
    Color(0xFF9333EA),
    Color(0xFFBE123C),
    Color(0xFF15803D),
    Color(0xFF0E7490),
    Color(0xFF7C3AED),
)

private val UnknownSpeakerColor = Color(0xFF64748B)

fun speakerColor(speaker: Int): Color =
    if (speaker < 0) UnknownSpeakerColor else SpeakerPalette[speaker % SpeakerPalette.size]

fun speakerLabel(speaker: Int): String =
    if (speaker < 0) "说话人 ?" else "说话人 ${speaker + 1}"
