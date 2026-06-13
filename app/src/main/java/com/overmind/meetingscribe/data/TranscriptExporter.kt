package com.overmind.meetingscribe.data

import android.content.Context
import android.os.Environment
import com.overmind.meetingscribe.asr.TranscriptSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TranscriptExportFormat(val extension: String, val mimeType: String) {
    MARKDOWN("md", "text/markdown"),
    HTML("html", "text/html"),
}

data class ExportedTranscript(
    val path: String,
    val mimeType: String,
)

object TranscriptExporter {
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun export(
        context: Context,
        segments: List<TranscriptSegment>,
        partial: String,
        format: TranscriptExportFormat,
    ): ExportedTranscript {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "documents")
        val dir = File(root, "exports").apply { mkdirs() }
        val stamp = synchronized(fileStamp) { fileStamp.format(Date()) }
        val file = File(dir, "meeting_transcript_$stamp.${format.extension}")
        val body = when (format) {
            TranscriptExportFormat.MARKDOWN -> markdown(segments, partial)
            TranscriptExportFormat.HTML -> html(segments, partial)
        }
        file.writeText(body, Charsets.UTF_8)
        return ExportedTranscript(path = file.absolutePath, mimeType = format.mimeType)
    }

    private fun markdown(segments: List<TranscriptSegment>, partial: String): String {
        val now = displayStamp().format(Date())
        return buildString {
            appendLine("# 会议转写")
            appendLine()
            appendLine("- 导出时间：$now")
            appendLine("- 片段数量：${segments.size}")
            appendLine()
            appendLine("| 时间 | 说话人 | 内容 |")
            appendLine("| --- | --- | --- |")
            for (segment in segments) {
                appendLine(
                    "| ${timeRange(segment)} | ${speakerName(segment.speaker)} | ${markdownCell(segment.text)} |",
                )
            }
            if (partial.isNotBlank()) {
                appendLine("| 进行中 | 未完成 | ${markdownCell(partial)} |")
            }
        }
    }

    private fun html(segments: List<TranscriptSegment>, partial: String): String {
        val now = htmlEscape(displayStamp().format(Date()))
        val rows = buildString {
            for (segment in segments) {
                appendLine(
                    """
                    <article class="message">
                      <div class="meta"><span>${htmlEscape(timeRange(segment))}</span><strong>${htmlEscape(speakerName(segment.speaker))}</strong></div>
                      <p>${htmlEscape(segment.text)}</p>
                    </article>
                    """.trimIndent(),
                )
            }
            if (partial.isNotBlank()) {
                appendLine(
                    """
                    <article class="message partial">
                      <div class="meta"><span>进行中</span><strong>未完成</strong></div>
                      <p>${htmlEscape(partial)}</p>
                    </article>
                    """.trimIndent(),
                )
            }
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>会议转写</title>
              <style>
                body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #f8fafc; color: #0f172a; }
                main { max-width: 860px; margin: 0 auto; padding: 32px 18px; }
                h1 { font-size: 28px; margin: 0 0 8px; }
                .summary { color: #475569; margin: 0 0 24px; }
                .message { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 16px; margin: 12px 0; }
                .message.partial { border-style: dashed; }
                .meta { display: flex; gap: 12px; align-items: center; color: #64748b; font-size: 13px; margin-bottom: 8px; }
                .meta strong { color: #0f766e; }
                p { margin: 0; line-height: 1.75; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <main>
                <h1>会议转写</h1>
                <p class="summary">导出时间：$now · 片段数量：${segments.size}</p>
                $rows
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun timeRange(segment: TranscriptSegment): String {
        val start = formatTime(segment.startMs)
        val end = if (segment.endMs > segment.startMs) formatTime(segment.endMs) else ""
        return if (end.isBlank()) start else "$start-$end"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun speakerName(speaker: Int): String =
        if (speaker < 0) "说话人 ?" else "说话人 ${speaker + 1}"

    private fun displayStamp(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun markdownCell(value: String): String =
        value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
