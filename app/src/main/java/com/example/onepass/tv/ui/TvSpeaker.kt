package com.example.onepass.tv.ui

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 看电视功能的语音播报。
 *
 * 独立于 MainActivity 里那套 TTS（那套是 private，且和天气/联系人播报耦合较深），
 * 但**沿用同一个「播报音量」偏好**（OnePassPrefs / broadcast_volume），
 * 保证家属在设置页调一次音量，全 App 统一生效。
 *
 * 老人场景播报原则：
 *   - 只在「换台、打不开、回来了」这三类关键时刻说话，不要每 5 秒播一次；
 *   - 音量走全局设置，不自己另起一套。
 */
class TvSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.language = Locale.CHINA }
                    .onFailure { Log.w(TAG, "设置中文语音失败: ${it.javaClass.simpleName}") }
            } else {
                Log.w(TAG, "TTS 初始化失败，将静默运行")
            }
        }
    }

    /** 播报一句话（打断上一句，避免换台时播报排队） */
    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        runCatching {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, readGlobalVolume())
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        }.onFailure { Log.w(TAG, "播报失败: ${it.javaClass.simpleName}") }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    /** 全局播报音量（0~1），默认 50%，与 MainActivity.getBroadcastVolume() 保持一致 */
    private fun readGlobalVolume(): Float {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val percent = if (prefs.contains(KEY_BROADCAST_VOLUME)) {
            prefs.getInt(KEY_BROADCAST_VOLUME, 50)
        } else {
            prefs.getInt(KEY_LEGACY_WEATHER_VOLUME, 50)
        }
        return (percent / 100f).coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "TvSpeaker"
        const val UTTERANCE_ID = "tv_speak"
        const val PREFS_NAME = "OnePassPrefs"
        const val KEY_BROADCAST_VOLUME = "broadcast_volume"
        const val KEY_LEGACY_WEATHER_VOLUME = "weather_volume"
    }
}
