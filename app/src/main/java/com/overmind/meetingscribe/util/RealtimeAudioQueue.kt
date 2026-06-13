package com.overmind.meetingscribe.util

import com.overmind.meetingscribe.audio.AudioConstants
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Bounded queue for live audio. When a backend falls behind, keeping the newest audio is better
 * than letting an hour-long meeting grow an unbounded in-memory backlog.
 */
object RealtimeAudioQueue {
    private const val BUFFER_SECONDS = 8
    private const val CAPACITY =
        BUFFER_SECONDS * AudioConstants.SAMPLE_RATE / AudioConstants.READ_SAMPLES

    fun <T> channel(): Channel<T> =
        Channel(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
