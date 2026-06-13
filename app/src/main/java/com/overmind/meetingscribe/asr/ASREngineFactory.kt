package com.overmind.meetingscribe.asr

import android.content.Context
import com.overmind.meetingscribe.asr.offline.SherpaOfflineEngine
import com.overmind.meetingscribe.asr.online.AliFunAsrEngine
import com.overmind.meetingscribe.asr.online.VolcDoubaoEngine
import com.overmind.meetingscribe.asr.online.XfyunRtasrEngine
import com.overmind.meetingscribe.data.AppSettings
import com.overmind.meetingscribe.data.ModelManager

/** Builds a fresh engine instance for [AppSettings.engine]. The UI never references concretes. */
object ASREngineFactory {
    fun create(context: Context, settings: AppSettings): ASREngine = when (settings.engine) {
        EngineType.SHERPA_OFFLINE ->
            SherpaOfflineEngine(ModelManager(context.applicationContext), settings)
        EngineType.XFYUN_RTASR -> XfyunRtasrEngine(settings)
        EngineType.ALI_FUNASR -> AliFunAsrEngine(settings)
        EngineType.VOLC_DOUBAO -> VolcDoubaoEngine(settings)
    }
}
