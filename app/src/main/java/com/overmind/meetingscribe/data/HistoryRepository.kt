package com.overmind.meetingscribe.data

import android.content.Context
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.audio.RecordingStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class HistorySession(
    val id: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val endedAtMs: Long?,
    val engineType: String?,
    val recordingPath: String?,
    val segments: List<TranscriptSegment>,
    val customTitle: String? = null,
    val pinned: Boolean = false,
    val favorite: Boolean = false,
) {
    val title: String
        get() = customTitle?.takeIf { it.isNotBlank() }
            ?: segments.firstOrNull { it.text.isNotBlank() }
            ?.text
            ?.replace('\n', ' ')
            ?.take(32)
            ?: "会议 ${displayDate(createdAtMs)}"

    val durationMs: Long
        get() = segments.maxOfOrNull { it.endMs } ?: 0L

    val hasRecording: Boolean
        get() = !recordingPath.isNullOrBlank() && File(recordingPath).exists()

    companion object {
        fun displayDate(ms: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

object HistoryRepository {
    private const val DIR = "history_sessions"

    @Synchronized
    fun createSession(context: Context, recordingPath: String?, engineType: String?): HistorySession {
        val now = System.currentTimeMillis()
        val session = HistorySession(
            id = UUID.randomUUID().toString(),
            createdAtMs = now,
            updatedAtMs = now,
            endedAtMs = null,
            engineType = engineType,
            recordingPath = recordingPath,
            segments = emptyList(),
        )
        write(context, session)
        return session
    }

    @Synchronized
    fun updateSession(
        context: Context,
        id: String,
        recordingPath: String?,
        segments: List<TranscriptSegment>,
        endedAtMs: Long? = null,
    ) {
        val old = load(context, id) ?: return
        val session = old.copy(
            updatedAtMs = System.currentTimeMillis(),
            endedAtMs = endedAtMs ?: old.endedAtMs,
            recordingPath = recordingPath ?: old.recordingPath,
            segments = segments,
        )
        if (session.segments.isEmpty() && !session.hasRecording && session.endedAtMs != null) {
            fileFor(context, id).delete()
        } else {
            write(context, session)
        }
    }

    @Synchronized
    fun list(context: Context): List<HistorySession> {
        val dir = dir(context)
        val saved = dir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { runCatching { parse(it.readText(Charsets.UTF_8)) }.getOrNull() }
            ?: emptyList()
        val knownRecordings = saved.mapNotNull { it.recordingPath }.toSet()
        val orphanRecordings = RecordingStore.listRecordings(context)
            .filter { it.absolutePath !in knownRecordings }
            .map { file ->
                val time = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                HistorySession(
                    id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray(Charsets.UTF_8)).toString(),
                    createdAtMs = time,
                    updatedAtMs = time,
                    endedAtMs = time,
                    engineType = null,
                    recordingPath = file.absolutePath,
                    segments = emptyList(),
                    customTitle = null,
                    pinned = false,
                    favorite = false,
                )
            }
        return (saved + orphanRecordings)
            .sortedWith(compareByDescending<HistorySession> { it.pinned }.thenByDescending { it.createdAtMs })
    }

    @Synchronized
    fun load(context: Context, id: String): HistorySession? {
        val file = fileFor(context, id)
        if (!file.exists()) return null
        return runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    @Synchronized
    fun delete(context: Context, session: HistorySession, deleteRecording: Boolean = false) {
        fileFor(context, session.id).delete()
        if (deleteRecording) session.recordingPath?.let { File(it).delete() }
    }

    @Synchronized
    fun rename(context: Context, session: HistorySession, title: String) {
        write(context, session.copy(customTitle = title.trim().takeIf { it.isNotBlank() }, updatedAtMs = System.currentTimeMillis()))
    }

    @Synchronized
    fun setPinned(context: Context, session: HistorySession, pinned: Boolean) {
        write(context, session.copy(pinned = pinned, updatedAtMs = System.currentTimeMillis()))
    }

    @Synchronized
    fun setFavorite(context: Context, session: HistorySession, favorite: Boolean) {
        write(context, session.copy(favorite = favorite, updatedAtMs = System.currentTimeMillis()))
    }

    private fun write(context: Context, session: HistorySession) {
        val file = fileFor(context, session.id)
        file.parentFile?.mkdirs()
        // Compact (no indentation): this file is rewritten on a timer during long meetings, so the
        // pretty-print CPU + 2-3x size overhead is not worth paying hundreds of times per session.
        file.writeText(toJson(session).toString(), Charsets.UTF_8)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, id: String): File =
        File(dir(context), "$id.json")

    private fun toJson(session: HistorySession): JSONObject =
        JSONObject()
            .put("id", session.id)
            .put("createdAtMs", session.createdAtMs)
            .put("updatedAtMs", session.updatedAtMs)
            .put("endedAtMs", session.endedAtMs ?: JSONObject.NULL)
            .put("engineType", session.engineType ?: JSONObject.NULL)
            .put("recordingPath", session.recordingPath ?: JSONObject.NULL)
            .put("customTitle", session.customTitle ?: JSONObject.NULL)
            .put("pinned", session.pinned)
            .put("favorite", session.favorite)
            .put(
                "segments",
                JSONArray().apply {
                    session.segments.forEach { segment ->
                        put(
                            JSONObject()
                                .put("id", segment.id)
                                .put("text", segment.text)
                                .put("speaker", segment.speaker)
                                .put("startMs", segment.startMs)
                                .put("endMs", segment.endMs)
                                .put("emotion", segment.emotion ?: JSONObject.NULL),
                        )
                    }
                },
            )

    private fun parse(raw: String): HistorySession {
        val obj = JSONObject(raw)
        val segments = obj.optJSONArray("segments") ?: JSONArray()
        return HistorySession(
            id = obj.getString("id"),
            createdAtMs = obj.optLong("createdAtMs"),
            updatedAtMs = obj.optLong("updatedAtMs"),
            endedAtMs = obj.optNullableLong("endedAtMs"),
            engineType = obj.optNullableString("engineType"),
            recordingPath = obj.optNullableString("recordingPath"),
            customTitle = obj.optNullableString("customTitle"),
            pinned = obj.optBoolean("pinned", false),
            favorite = obj.optBoolean("favorite", false),
            segments = buildList {
                for (i in 0 until segments.length()) {
                    val item = segments.getJSONObject(i)
                    add(
                        TranscriptSegment(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            text = item.optString("text"),
                            speaker = item.optInt("speaker", TranscriptSegment.UNKNOWN_SPEAKER),
                            startMs = item.optLong("startMs"),
                            endMs = item.optLong("endMs"),
                            emotion = item.optNullableString("emotion"),
                        ),
                    )
                }
            },
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}
