package com.overmind.meetingscribe.audio

object AudioConstants {
    const val ASR_SAMPLE_RATE = 16_000
    const val SAMPLE_RATE = ASR_SAMPLE_RATE
    const val RECORDING_SAMPLE_RATE = 48_000
    const val RECORDING_BIT_RATE = 128_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2

    /** ~100 ms read granularity from AudioRecord. */
    const val READ_SAMPLES = ASR_SAMPLE_RATE / 10
    const val RECORDING_READ_SAMPLES = RECORDING_SAMPLE_RATE / 10
}
