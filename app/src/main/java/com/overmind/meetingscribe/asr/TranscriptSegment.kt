package com.overmind.meetingscribe.asr

import java.util.UUID

/**
 * A finalized line of transcript. [speaker] is a 0-based speaker index, or -1 when unknown.
 *
 * For the offline engine, [speaker] is first set by the live (provisional) clusterer while
 * recording, then may be overwritten by the authoritative post-recording diarization pass
 * via [ASRListener.onSpeakersCorrected] (keyed by [id]).
 */
data class TranscriptSegment(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val speaker: Int = UNKNOWN_SPEAKER,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    /** Optional emotion/event tag (e.g. SenseVoice "HAPPY", "ANGRY"); null when unavailable. */
    val emotion: String? = null,
) {
    companion object {
        const val UNKNOWN_SPEAKER = -1
    }
}
