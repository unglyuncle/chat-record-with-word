package com.overmind.meetingscribe.util

/** Convert [length] PCM-16 samples to little-endian bytes (the wire format all engines expect). */
fun shortsToLeBytes(samples: ShortArray, length: Int): ByteArray {
    val out = ByteArray(length * 2)
    var j = 0
    for (i in 0 until length) {
        val s = samples[i].toInt()
        out[j++] = (s and 0xFF).toByte()
        out[j++] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}
