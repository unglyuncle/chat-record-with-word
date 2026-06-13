package com.overmind.meetingscribe.asr

/**
 * The single contract the UI layer depends on. Each backend (offline sherpa-onnx, 讯飞, 阿里,
 * 火山) implements this independently; switching engines is always: [stop] + [release] the old
 * one, then [init] (+ later [startStreaming]) the new one.
 *
 * Audio is always 16 kHz / mono / 16-bit PCM. Engines internally re-chunk as their protocol
 * requires, so callers may feed arbitrarily sized buffers.
 *
 * Typical lifecycle:
 *   setListener -> init() -> startStreaming() -> feedAudio()* -> stop() -> release()
 */
interface ASREngine {

    val type: EngineType

    /** Must be set before [init]. Pass null on teardown to drop the reference. */
    fun setListener(listener: ASRListener?)

    /**
     * Acquire everything needed before audio can flow: load on-device models (offline) or
     * validate that credentials are present (online). Heavy work runs off the caller's thread.
     * Returns failure with a human-readable message instead of throwing.
     */
    suspend fun init(): Result<Unit>

    /** Begin a transcription session (reset state; open the network stream for online engines). */
    fun startStreaming()

    /** Feed PCM. [length] is the number of valid samples in [pcm]. */
    fun feedAudio(pcm: ShortArray, length: Int)

    /**
     * End the current session. May transition through [EngineState.FINALIZING] (offline
     * diarization) before reaching [EngineState.STOPPED].
     */
    fun stop()

    /** Release native handles / sockets. The engine is unusable afterwards. */
    fun release()
}
