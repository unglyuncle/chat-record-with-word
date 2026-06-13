package com.overmind.meetingscribe.audio

interface RecordingFileWriter {
    val durationMs: Long
    val mimeType: String
    fun write(samples: ShortArray, length: Int)
    fun close()
}
