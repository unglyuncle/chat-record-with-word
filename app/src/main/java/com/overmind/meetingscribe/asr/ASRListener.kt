package com.overmind.meetingscribe.asr

/**
 * Callbacks every engine reports through. Implementations (e.g. EngineController) must be
 * tolerant of being called from arbitrary background threads — engines do not guarantee a
 * particular dispatcher.
 */
interface ASRListener {

    /** Engine lifecycle transitions. */
    fun onState(state: EngineState)

    /**
     * A volatile, still-changing hypothesis for the current utterance. Replaces any previously
     * emitted partial. Cleared (empty string) once the utterance is finalized via [onSegment].
     */
    fun onPartial(text: String)

    /** A finalized transcript line was produced and should be appended. */
    fun onSegment(segment: TranscriptSegment)

    /**
     * Speaker labels were recomputed (offline post-pass). Maps [TranscriptSegment.id] to the
     * corrected 0-based speaker index for any segments whose speaker changed.
     */
    fun onSpeakersCorrected(speakerById: Map<String, Int>)

    fun onError(message: String, cause: Throwable? = null)
}
