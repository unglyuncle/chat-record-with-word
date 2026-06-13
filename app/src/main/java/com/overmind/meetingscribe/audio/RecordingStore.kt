package com.overmind.meetingscribe.audio

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingStore {
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    fun newRecordingFile(context: Context, format: RecordingFormat): File {
        val dir = recordingsDir(context)
        val stamp = synchronized(fileStamp) { fileStamp.format(Date()) }
        var file = File(dir, "meeting_recording_$stamp.${format.extension}")
        var suffix = 1
        while (file.exists()) {
            file = File(dir, "meeting_recording_${stamp}_$suffix.${format.extension}")
            suffix += 1
        }
        return file
    }

    fun recordingsDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "music")
        return File(root, "recordings").apply { mkdirs() }
    }

    fun listRecordings(context: Context): List<File> {
        return recordingsDir(context)
            .listFiles { file ->
                file.isFile && file.extension.lowercase(Locale.US) in setOf("wav", "m4a")
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun scan(context: Context, file: File, mimeType: String) {
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null,
        )
    }
}
