package com.overmind.meetingscribe.asr

enum class EngineState {
    /** No engine selected / fully stopped. */
    IDLE,

    /** Loading models or establishing the connection. */
    INITIALIZING,

    /** Initialized and ready to stream, but not yet receiving audio. */
    READY,

    /** Actively receiving and transcribing audio. */
    LISTENING,

    /** Recording stopped; running the post-pass (e.g. offline speaker diarization). */
    FINALIZING,

    /** Session finished, results final. */
    STOPPED,

    /** A non-recoverable error occurred; see the error message. */
    ERROR,
}
