package com.overmind.meetingscribe.asr.online

import android.util.Base64
import android.util.Log
import com.overmind.meetingscribe.asr.ASREngine
import com.overmind.meetingscribe.asr.ASRListener
import com.overmind.meetingscribe.asr.EngineState
import com.overmind.meetingscribe.asr.EngineType
import com.overmind.meetingscribe.asr.TranscriptSegment
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.util.Net
import com.overmind.meetingscribe.util.RealtimeAudioQueue
import com.overmind.meetingscribe.util.shortsToLeBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞 RTASR (实时语音转写) over OkHttp WebSocket.
 *
 * Auth:   signa = Base64(HMAC-SHA1(key = apiKey, data = MD5hex(appId + ts))).
 * Audio:  raw PCM16/16k/mono, 1280-byte frames paced ~40 ms.
 * End:    text frame {"end": true}.
 * Result: cn.st.rt[].ws[].cw[].w ; st.type "0"=final / "1"=interim ; cw[].rl = speaker role.
 *
 * This is the most complete online engine — it needs only AppID + ApiKey. Role separation parsing
 * (rl) always works; the enabling query param differs by 讯飞 product and is left configurable.
 */
class XfyunRtasrEngine(private val settings: AppSettings) : ASREngine {

    override val type = EngineType.XFYUN_RTASR
    private var listener: ASRListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var pcmChannel: Channel<ByteArray>? = null

    override fun setListener(listener: ASRListener?) {
        this.listener = listener
    }

    override suspend fun init(): Result<Unit> = runCatching {
        require(settings.xfyunAppId.isNotBlank() && settings.xfyunApiKey.isNotBlank()) {
            "请先在设置中填写讯飞 AppID 与 ApiKey"
        }
    }

    override fun startStreaming() {
        val appId = settings.xfyunAppId
        val ts = (System.currentTimeMillis() / 1000).toString()
        val signa = try {
            sign(appId, settings.xfyunApiKey, ts)
        } catch (t: Throwable) {
            listener?.onError("讯飞签名失败: ${t.message}", t)
            return
        }
        val url = buildString {
            append("wss://rtasr.xfyun.cn/v1/ws?appid=").append(appId)
            append("&ts=").append(ts)
            append("&signa=").append(URLEncoder.encode(signa, "UTF-8"))
            // 角色分离：标准版/大模型参数不一致，官方文档未统一确认，故仅在开启时附加占位参数，
            // 请按你实际开通的产品在控制台核对参数名/取值。rl 字段解析与此开关无关。
            if (settings.xfyunRoleSeparation) append("&roleType=2")
        }
        val ch = RealtimeAudioQueue.channel<ByteArray>()
        pcmChannel = ch
        listener?.onState(EngineState.INITIALIZING)
        ws = Net.client.newWebSocket(Request.Builder().url(url).build(), socketListener())
        scope.launch { sender(ch) }
    }

    private fun sign(appId: String, apiKey: String, ts: String): String {
        val md5Hex = MessageDigest.getInstance("MD5")
            .digest((appId + ts).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(md5Hex.toByteArray()), Base64.NO_WRAP)
    }

    private suspend fun sender(ch: Channel<ByteArray>) {
        val pending = ByteArray(FRAME)
        var pendingSize = 0
        try {
            for (chunk in ch) {
                var offset = 0
                while (offset < chunk.size) {
                    val copy = minOf(FRAME - pendingSize, chunk.size - offset)
                    System.arraycopy(chunk, offset, pending, pendingSize, copy)
                    pendingSize += copy
                    offset += copy
                    if (pendingSize == FRAME) {
                        ws?.send(pending.toByteString(0, FRAME))
                        pendingSize = 0
                        delay(FRAME_INTERVAL_MS)
                    }
                }
            }
            if (pendingSize > 0) ws?.send(pending.toByteString(0, pendingSize))
            ws?.send(END_FRAME)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // switching/release
        } catch (t: Throwable) {
            Log.e(TAG, "sender error", t)
        }
    }

    override fun feedAudio(pcm: ShortArray, length: Int) {
        pcmChannel?.trySend(shortsToLeBytes(pcm, length))
    }

    override fun stop() {
        pcmChannel?.close()
        scope.launch {
            delay(2000) // let trailing results arrive
            runCatching { ws?.close(1000, "bye") }
            listener?.onState(EngineState.STOPPED)
        }
    }

    override fun release() {
        runCatching { pcmChannel?.close() }
        runCatching { ws?.cancel() }
        ws = null
        runCatching { scope.cancel() }
        listener = null
    }

    private fun socketListener() = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener?.onError("讯飞连接失败: ${t.message}", t)
        }
    }

    private fun handle(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("action")) {
            "started" -> listener?.onState(EngineState.LISTENING)
            "error" -> listener?.onError("讯飞错误[${obj.optString("code")}]: ${obj.optString("desc")}")
            "result" -> parseResultData(obj.optString("data"))
            else -> when {
                obj.has("cn") -> parseCn(obj)
                obj.has("data") -> parseResultData(obj.optString("data"))
            }
        }
    }

    private fun parseResultData(data: String?) {
        if (data.isNullOrBlank()) return
        runCatching { JSONObject(data) }.getOrNull()?.let { parseCn(it) }
    }

    private fun parseCn(root: JSONObject) {
        val st = root.optJSONObject("cn")?.optJSONObject("st") ?: return
        val type = st.optString("type", "0")
        val bg = st.optString("bg", "0").toLongOrNull() ?: 0L
        val ed = st.optString("ed", "0").toLongOrNull() ?: 0L
        val rt = st.optJSONArray("rt") ?: return

        val sb = StringBuilder()
        var speaker = TranscriptSegment.UNKNOWN_SPEAKER
        for (i in 0 until rt.length()) {
            val wsArr = rt.getJSONObject(i).optJSONArray("ws") ?: continue
            for (j in 0 until wsArr.length()) {
                val cw = wsArr.getJSONObject(j).optJSONArray("cw") ?: continue
                if (cw.length() == 0) continue
                val w0 = cw.getJSONObject(0)
                sb.append(w0.optString("w"))
                if (speaker < 0 && w0.has("rl")) speaker = w0.optInt("rl", -1)
            }
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) return
        if (type == "0") {
            listener?.onSegment(TranscriptSegment(text = out, speaker = speaker, startMs = bg, endMs = ed))
        } else {
            listener?.onPartial(out)
        }
    }

    private companion object {
        const val TAG = "XfyunRtasrEngine"
        const val FRAME = 1280
        const val FRAME_INTERVAL_MS = 40L
        const val END_FRAME = "{\"end\": true}"
    }
}
