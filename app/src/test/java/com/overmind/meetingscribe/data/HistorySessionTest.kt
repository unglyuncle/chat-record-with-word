package com.overmind.meetingscribe.data

import com.overmind.meetingscribe.asr.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the transcript value types (no Android/JNI deps, so it runs as a plain
 * unit test). Exercises the title-derivation, duration and recording-presence logic that the
 * history list UI relies on.
 */
class HistorySessionTest {

    private fun session(
        segments: List<TranscriptSegment>,
        customTitle: String? = null,
        createdAtMs: Long = 0L,
        recordingPath: String? = null,
    ) = HistorySession(
        id = "test",
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        endedAtMs = null,
        engineType = null,
        recordingPath = recordingPath,
        segments = segments,
        customTitle = customTitle,
    )

    @Test
    fun title_prefersCustomTitle() {
        val s = session(
            segments = listOf(TranscriptSegment(text = "hello world")),
            customTitle = "我的会议",
        )
        assertEquals("我的会议", s.title)
    }

    @Test
    fun title_blankCustomTitleFallsBackToFirstNonBlankSegment() {
        val s = session(
            segments = listOf(
                TranscriptSegment(text = "   "),
                TranscriptSegment(text = "first real line"),
            ),
            customTitle = "   ",
        )
        assertEquals("first real line", s.title)
    }

    @Test
    fun title_collapsesNewlinesAndTruncatesTo32() {
        val multiline = session(segments = listOf(TranscriptSegment(text = "line1\nline2")))
        assertEquals("line1 line2", multiline.title)

        val long = session(segments = listOf(TranscriptSegment(text = "x".repeat(50))))
        assertEquals(32, long.title.length)
    }

    @Test
    fun title_emptyTranscriptUsesDatePlaceholder() {
        val s = session(segments = emptyList())
        assertTrue("expected a 会议 date placeholder, was '${s.title}'", s.title.startsWith("会议 "))
    }

    @Test
    fun durationMs_isMaxEndMs() {
        val s = session(
            segments = listOf(
                TranscriptSegment(text = "a", startMs = 0, endMs = 1_000),
                TranscriptSegment(text = "b", startMs = 1_000, endMs = 4_200),
            ),
        )
        assertEquals(4_200L, s.durationMs)
    }

    @Test
    fun durationMs_emptyIsZero() {
        assertEquals(0L, session(segments = emptyList()).durationMs)
    }

    @Test
    fun hasRecording_falseForNullOrMissingPath() {
        assertFalse(session(segments = emptyList(), recordingPath = null).hasRecording)
        assertFalse(session(segments = emptyList(), recordingPath = "/no/such/file_xyz.m4a").hasRecording)
    }

    @Test
    fun transcriptSegment_defaults() {
        val seg = TranscriptSegment(text = "hi")
        assertEquals(TranscriptSegment.UNKNOWN_SPEAKER, seg.speaker)
        assertTrue(seg.id.isNotBlank())
        assertEquals(0L, seg.startMs)
        assertEquals(null, seg.emotion)
    }
}
