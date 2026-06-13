package com.overmind.meetingscribe.data

import com.overmind.meetingscribe.util.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads model components on first use and unpacks `.tar.bz2` archives via commons-compress.
 * Cancellation-aware; cleans up partial files on failure so a retry starts fresh.
 */
class ModelDownloader(private val manager: ModelManager) {

    suspend fun ensure(component: ModelComponent, onProgress: (Float) -> Unit = {}): Result<Unit> {
        if (manager.isReady(component)) {
            onProgress(1f)
            return Result.success(Unit)
        }
        return download(component, onProgress)
    }

    suspend fun download(component: ModelComponent, onProgress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val tmp = File(manager.modelsDir, ".dl_" + component.name)
            runCatching {
                manager.modelsDir.mkdirs()
                if (tmp.exists()) tmp.delete()

                val request = Request.Builder().url(component.url).build()
                Net.downloadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                    val body = resp.body ?: error("下载失败：空响应体")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                            val buf = ByteArray(1 shl 16)
                            var downloaded = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val r = input.read(buf)
                                if (r < 0) break
                                output.write(buf, 0, r)
                                downloaded += r
                                if (total > 0) {
                                    onProgress((downloaded.toFloat() / total).coerceIn(0f, 0.99f))
                                }
                            }
                        }
                    }

                    if (component.archive) {
                        extractTarBz2(tmp, manager.modelsDir)
                        tmp.delete()
                    } else {
                        val dest = File(manager.modelsDir, component.expectedFiles.first())
                        dest.parentFile?.mkdirs()
                        if (dest.exists()) dest.delete()
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
                if (!manager.isReady(component)) error("下载后文件校验失败，请重试")
                onProgress(1f)
            }.onFailure { tmp.delete() }
        }

    private fun extractTarBz2(archive: File, outDir: File) {
        val canonicalOut = outDir.canonicalPath
        FileInputStream(archive).buffered().use { fin ->
            BZip2CompressorInputStream(fin).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(outDir, entry.name)
                        // zip-slip guard
                        if (outFile.canonicalPath != canonicalOut &&
                            !outFile.canonicalPath.startsWith(canonicalOut + File.separator)
                        ) {
                            error("非法的压缩包路径: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(outFile)).use { tar.copyTo(it) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }
}
