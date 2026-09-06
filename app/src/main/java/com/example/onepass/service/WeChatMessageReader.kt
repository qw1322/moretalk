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
import com.example.onepass.service.AccessibilityNodeHelper.safeRecycle
import com.example.onepass.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
        /** 点读时沿父链向上查找消息条目的最大层数（长消息气泡层级更深） */
        private const val MAX_PARENT_WALK = 8
        /** 点读播报文本长度上限，防止超长粘贴文本无限朗读 */
        private const val MAX_SPEECH_LEN = 2000

        private const val KEY_BROADCAST_VOLUME = "broadcast_volume"
        private const val KEY_LEGACY_WEATHER_VOLUME = "weather_volume"
        private const val KEY_SPEECH_RATE = "speech_rate"

        // DOT_MATCHES_ALL：长消息的 contentDescription 可能包含换行，默认 "." 不匹配 \n 会导致解析失败
        private val SENDER_CONTENT_REGEX =
            Regex("^(.*?)\\s*说：\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
    }

    private val appContext = context.applicationContext
    private val speechSupport = BundledSpeechSupport(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readerScope = CoroutineScope(Dispatchers.Main + Job())

    private val recentSpoken = ArrayDeque<String>()
    private var lastReadTime = 0L

    // 通过窗口状态事件跟踪当前是否在微信聊天页（部分 ROM 上根节点类名是 FrameLayout，不可依赖）
    private var isOnWeChatChatPage = false

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var bundledEngine: BundledSpeechEngine? = null

    /** 内置语音引擎是否已启动预热（只在主线程读写） */
    private var warmUpStarted = false
    /** 当前播报任务：新播报先取消旧的，保证同一时刻只有一个合成/播放流程 */
    private var speechJob: Job? = null

    fun isEnabled(): Boolean =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WECHAT_MSG_READ_ENABLED, false)

    fun init() {
        textToSpeech = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
        // 预热内置语音引擎：Matcha 模型从 assets 加载需要数秒，
        // 若等到首次点击才加载，第一次点读会明显卡顿。
        warmUpBundledEngine()
    }

    fun shutdown() {
        readerScope.cancel()
        speechJob?.cancel()
        speechJob = null
        mainHandler.removeCallbacksAndMessages(null)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        bundledEngine?.close()
        bundledEngine = null
    }

    /**
     * 后台预热内置语音引擎（仅一次）。条件：点读开关开启 + 未强制使用系统 TTS。
     * 开关在设置页开启后，下一次微信无障碍事件到来时也会触发预热。
     */
    private fun warmUpBundledEngine() {
        if (warmUpStarted) return
        warmUpStarted = true
        if (!isEnabled()) return
        if (speechSupport.getMode() == SpeechEngineMode.SYSTEM) return
        readerScope.launch {
            withContext(Dispatchers.IO) { ensureBundledEngine() }
        }
    }

    /**
     * 处理无障碍事件。rootProvider 用于懒取当前活跃窗口根节点。
     */
    fun handleEvent(event: AccessibilityEvent?, rootProvider: () -> AccessibilityNodeInfo?) {
        if (!isEnabled()) return
        warmUpBundledEngine()
        val pkg = event?.packageName?.toString() ?: return

        // 微信拨号自动化进行中，不抢读
        if (WeChatData.index != 0) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 跟踪微信当前页面：窗口状态事件携带真实活动类名
                if (pkg == "com.tencent.mm") {
                    isOnWeChatChatPage = event.className?.toString()?.contains("ChattingUI") == true
                    Logger.d("$TAG 窗口状态: ${event.className} isChatPage=$isOnWeChatChatPage")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg != "com.tencent.mm" || !isOnWeChatChatPage) return
                val now = System.currentTimeMillis()
                if (now - lastReadTime < READ_THROTTLE_MS) return
                lastReadTime = now
                val root = runCatching { rootProvider() }.getOrNull() ?: return
                val messages = mutableListOf<Pair<String, String>>()
                collectMessages(root, messages, 0)
                root.recycle()
                speakNewMessages(messages)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 点读：用户点按微信消息时朗读该消息内容（明确意图，不走去重）
                if (pkg != "com.tencent.mm") return
                val source = event.source
                val speech = if (source != null) {
                    resolveMessageSpeech(source)
                } else {
                    null
                }
                // 三种判据任一成立即朗读：
                // 1) 窗口状态跟踪到聊天页；2) 解析出"发送者 说：内容"消息条目；3) 点击位置在消息区域
                val isParsedMessage = speech?.contains("说：") == true
                val inMessageArea = source != null && isInMessageArea(source)
                Logger.d(
                    "$TAG VIEW_CLICKED 解析: $speech isChatPage=$isOnWeChatChatPage inArea=$inMessageArea"
                )
                if (!speech.isNullOrBlank() && (isOnWeChatChatPage || isParsedMessage || inMessageArea)) {
                    speak(speech)
                }
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

    /**
     * 从被点击节点解析可播报内容：
     * 1) 沿父链找消息条目的 contentDescription（"发送者 说：内容"）；
     * 2) 找不到消息条目时，取父链上**最长**的一段文本作为兜底。
     *    - 长消息的文本可能超过旧版 200 字符上限，导致兜底为空、点击无反应；
     *    - 点击命中的可能是气泡内的子节点（时间戳/按钮等短文本），
     *      取最长文本能命中真正的消息内容本身。
     * 遍历中获取的节点统一回收。
     */
    private fun resolveMessageSpeech(node: AccessibilityNodeInfo): String? {
        val chain = mutableListOf<AccessibilityNodeInfo>()
        var bestFallback: String? = null
        var result: String? = null
        try {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < MAX_PARENT_WALK) {
                chain.add(current)
                val desc = current.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty()) {
                    val parsed = parseMessage(desc)
                    if (parsed != null) {
                        result = "${parsed.first}说：${parsed.second}"
                        break
                    }
                }
                // 兜底：取父链上最长的文本（最可能是完整消息内容）
                val text = current.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && text.length > (bestFallback?.length ?: 0)) {
                    bestFallback = text
                }
                current = current.parent
                depth++
            }
        } finally {
            chain.forEach { it.safeRecycle() }
        }
        return result ?: bestFallback?.take(MAX_SPEECH_LEN)
    }

    /**
     * 消息区域判定：聊天页的标题栏在顶部、输入栏在底部，消息气泡只出现在屏幕中部区域。
     * 部分 ROM 收不到微信窗口状态事件，用位置作为兜底判据。
     */
    private fun isInMessageArea(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val screenH = appContext.resources.displayMetrics.heightPixels
        val centerY = (rect.top + rect.bottom) / 2
        return centerY in 300..(screenH - 300)
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
        // 先取消上一段播报：快速连点/新消息自动播报时，
        // 若多个协程同时走 BundledSpeechEngine.speak（内部 stop+write），
        // 会出现两个 AudioTrack 互相打断、爆音/断续。取消旧任务保证同一时刻只有一个播放流程。
        speechJob?.cancel()
        speechJob = readerScope.launch {
            val spoken = withContext(Dispatchers.IO) {
                ensureBundledEngine()
                bundledEngine?.speak(text, rate, volume) == true
            }
            // 任务已被新播报取消时不再回退系统 TTS，避免新旧两段声音叠加
            if (!spoken && mode == SpeechEngineMode.AUTO && isActive) {
                speakSystem(text, volume, rate)
            }
        }
    }

    @Synchronized
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
