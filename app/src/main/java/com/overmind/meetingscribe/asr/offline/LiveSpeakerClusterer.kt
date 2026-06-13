package com.overmind.meetingscribe.asr.offline

import com.overmind.meetingscribe.audio.AudioConstants

/**
 * Greedy online speaker clustering for provisional labels while recording. For each finalized
 * speech segment it computes an embedding and either matches an existing speaker centroid (cosine
 * >= [threshold]) or starts a new one. Cheap and approximate — the authoritative labels come later
 * from the whole-recording [OfflineDiarizer] pass.
 *
 * All access is synchronized because [assign] runs on the engine worker thread.
 */
class LiveSpeakerClusterer(
    private val embedder: SpeakerEmbedder,
    private val threshold: Float = 0.5f,
) {
    private class Centroid(val vec: FloatArray, var count: Int)

    private val centroids = ArrayList<Centroid>()

    /** Returns a 0-based speaker index, or -1 when no reliable assignment could be made. */
    @Synchronized
    fun assign(samples: FloatArray): Int {
        if (samples.size < MIN_SAMPLES) {
            return if (centroids.isEmpty()) -1 else 0
        }
        return try {
            val emb = embedder.embed(samples)
            var bestIdx = -1
            var bestSim = -1f
            for (i in centroids.indices) {
                val sim = SpeakerEmbedder.cosine(emb, centroids[i].vec)
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = i
                }
            }
            if (bestIdx >= 0 && bestSim >= threshold) {
                val c = centroids[bestIdx]
                val n = c.count
                for (k in emb.indices) c.vec[k] = (c.vec[k] * n + emb[k]) / (n + 1)
                c.count = n + 1
                bestIdx
            } else {
                centroids.add(Centroid(emb.copyOf(), 1))
                centroids.size - 1
            }
        } catch (t: Throwable) {
            -1
        }
    }

    @Synchronized
    fun reset() {
        centroids.clear()
    }

    private companion object {
        // Ignore very short blips (< 0.5 s) — embeddings are unreliable there.
        const val MIN_SAMPLES = AudioConstants.SAMPLE_RATE / 2
    }
}
