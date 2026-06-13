package com.overmind.meetingscribe.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Gzip {
    fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        ByteArrayInputStream(data).use { bis ->
            GZIPInputStream(bis).use { return it.readBytes() }
        }
    }
}
