package com.example.onepass.service

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.onepass.domain.model.WeChatData
import com.example.onepass.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * 微信消息语音点读：
 * - 聊天页内容变化时扫描消息节点，播报新消息（"张三说：你好"）；
 * - 微信通知到达时兜底播报（后台收消息场景）；
 * - 去重窗口避免重复朗读；播报中不干扰微信拨号自动化（WeChatData.index != 0 时跳过）。
 */
class WeChatMessageReader(context: Context) {

    companion object {
        private const val PREFS_NAME = "OnePassPrefs"
        const val KEY_WECHAT_MSG_READ_ENABLED = "wechat_msg_read_enabled"

        private const val TAG = "WeChatMsgReader"
        private const val MAX_RECENT = 30
        private const val MAX_SPEAK_PER_BATCH = 3
        private const val READ_THROTTLE_MS = 700L
        private const val MAX_DEPTH = 14

        private const val KEY_BROADCAST_VOLUME = "broadcast_volume"
        private const val KEY_LEGACY_WEATHER_VOLUME = "weather_volume"
        private const val KEY_SPEECH_RATE = "speech_rate"

        private val SENDER_CONTENT_REGEX = Regex("^(.*?)\\s*说：\\s*(.*)$")
    }

    private val appContext = context.applicationContext
    private val speechSupport = BundledSpeechSupport(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readerScope = CoroutineScope(Dispatchers.Main + Job())

    private val recentSpoken = ArrayDeque<String>()
    private var lastReadTime = 0L

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var bundledEngine: BundledSpeechEngine? = null

    fun isEnabled(): Boolean =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WECHAT_MSG_READ_ENABLED, false)

    fun init() {
        textToSpeech = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    fun shutdown() {
        readerScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        bundledEngine?.close()
        bundledEngine = null
    }

    /**
     * 处理无障碍事件。rootProvider 用于懒取当前活跃窗口根节点。
     */
    fun handleEvent(event: AccessibilityEvent?, rootProvider: () -> AccessibilityNodeInfo?) {
        if (!isEnabled()) return
        val pkg = event?.packageName?.toString() ?: return

        // 微信拨号自动化进行中，不抢读
        if (WeChatData.index != 0) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val className = event.className?.toString() ?: return
                if (pkg != "com.tencent.mm" || !className.contains("ChattingUI")) return
                val now = System.currentTimeMillis()
                if (now - lastReadTime < READ_THROTTLE_MS) return
                lastReadTime = now
                val root = runCatching { rootProvider() }.getOrNull() ?: return
                val messages = mutableListOf<Pair<String, String>>()
                collectMessages(root, messages, 0)
                root.recycle()
                speakNewMessages(messages)
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg != "com.tencent.mm") return
                val notification = event.parcelableData as? Notification ?: return
                val extras = notification.extras ?: return
                val title = extras.getString(Notification.EXTRA_TITLE)?.trim().orEmpty()
                val text = extras.getString(Notification.EXTRA_TEXT)?.trim().orEmpty()
                val summary = text.ifBlank { title }
                if (summary.isBlank()) return
                val fingerprint = "$title|$summary"
                if (isRecentlySpoken(fingerprint)) return
                markSpoken(fingerprint)
                speak("微信来新消息，$summary")
            }
        }
    }

    private fun collectMessages(
        node: AccessibilityNodeInfo?,
        out: MutableList<Pair<String, String>>,
        depth: Int
    ) {
        if (node == null || depth > MAX_DEPTH) return
        node.contentDescription?.toString()?.let { desc ->
            parseMessage(desc)?.let { out.add(it) }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMessages(child, out, depth + 1)
            child.recycle()
        }
    }

    /**
     * 从节点 contentDescription 解析消息，如 "张三 说：你好" / "我说：好的"。
     * 返回 (发送者, 内容)，自己发的消息返回 null。
     */
    private fun parseMessage(desc: String): Pair<String, String>? {
        val trimmed = desc.trim()
        val match = SENDER_CONTENT_REGEX.matchEntire(trimmed) ?: return null
        var sender = match.groupValues[1].trim()
        var content = match.groupValues[2].trim()
        if (sender.isEmpty() || content.isEmpty()) return null
        if (sender == "我") return null
        // 发送者可能是 "张三(备注)" 等形式，仅保留主体
        sender = sender.substringBefore("(").trim()
        // 多媒体消息转成可播报的描述
        content = when {
            content.startsWith("[语音]") || content.startsWith("[语音") -> "发来一条语音消息"
            content.startsWith("[图片]") || content.startsWith("[图片") -> "发来一张图片"
            content.startsWith("[视频]") || content.startsWith("[视频") -> "发来一条视频"
            content.startsWith("[动画表情]") || content.startsWith("[表情]") -> "发来一个表情"
            content.startsWith("[链接]") || content.startsWith("[链接") -> "发来一条链接"
            else -> content
        }
        if (content.isEmpty()) return null
        return sender to content
    }

    private fun speakNewMessages(messages: List<Pair<String, String>>) {
        var spoken = 0
        for ((sender, content) in messages) {
            val fingerprint = "$sender|$content"
            if (isRecentlySpoken(fingerprint)) continue
            markSpoken(fingerprint)
            val speech = if (spoken == 0) {
                "新消息，${sender}说：$content"
            } else {
                "${sender}说：$content"
            }
            speak(speech)
            spoken++
            if (spoken >= MAX_SPEAK_PER_BATCH) break
        }
    }

    private fun isRecentlySpoken(fingerprint: String): Boolean = recentSpoken.contains(fingerprint)

    private fun markSpoken(fingerprint: String) {
        if (recentSpoken.size >= MAX_RECENT) {
            recentSpoken.removeFirst()
        }
        recentSpoken.addLast(fingerprint)
    }

    // ==================== 播报 ====================

    fun speak(text: String) {
        val volume = getBroadcastVolume()
        val rate = getSpeechRate()
        val mode = speechSupport.getMode()
        if (mode == SpeechEngineMode.SYSTEM) {
            speakSystem(text, volume, rate)
            return
        }
        // AUTO / BUNDLED_MATCHA：优先内置引擎，AUTO 失败时回退系统 TTS
        readerScope.launch {
            val spoken = withContext(Dispatchers.IO) {
                ensureBundledEngine()
                bundledEngine?.speak(text, rate, volume) == true
            }
            if (!spoken && mode == SpeechEngineMode.AUTO) {
                speakSystem(text, volume, rate)
            }
        }
    }

    private fun ensureBundledEngine() {
        if (bundledEngine != null) return
        if (!speechSupport.hasBundledVoice()) return
        runCatching {
            bundledEngine = BundledSpeechEngine(appContext).apply { initialize() }
        }.onFailure { e ->
            Logger.w("$TAG 内置语音初始化失败: ${e.message}")
        }
    }

    private fun speakSystem(text: String, volume: Float, rate: Float): Boolean {
        if (!ttsReady) return false
        return runCatching {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            }
            textToSpeech?.setSpeechRate(rate)
            textToSpeech?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                params,
                "wechat-msg-${System.currentTimeMillis()}"
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    private fun getBroadcastVolume(): Float {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val progress = if (prefs.contains(KEY_BROADCAST_VOLUME)) {
            prefs.getInt(KEY_BROADCAST_VOLUME, 50)
        } else {
            prefs.getInt(KEY_LEGACY_WEATHER_VOLUME, 50)
        }
        return progress.coerceIn(0, 100) / 100.0f
    }

    private fun getSpeechRate(): Float {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val progress = prefs.getInt(KEY_SPEECH_RATE, 50)
        return 0.5f + (progress / 50f)
    }
}
