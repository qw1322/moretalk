package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.onepass.domain.model.WeChatActivity
import com.example.onepass.domain.model.WeChatData
import com.example.onepass.domain.model.WeChatId
import com.example.onepass.service.AccessibilityNodeHelper.safeRecycle
import com.example.onepass.service.AccessibilityNodeHelper.safeRecycleAll
import com.example.onepass.service.WeChatMessageReader
import com.example.onepass.utils.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信视频通话无障碍自动化服务
 *
 * 流程说明：
 * 步骤1: 确保在微信首页
 * 步骤2: 点击搜索按钮
 * 步骤3: 输入联系人昵称
 * 步骤4: 选择联系人进入聊天界面
 * 步骤5: 点击更多按钮(+)
 * 步骤6: 点击视频/语音通话
 * 步骤7: 点击确认通话
 */
class SelectToSpeakService : AccessibilityService() {
    companion object {
        private const val TAG = "WechatAccessibility"
        private const val MAX_RETRY_COUNT = 3
        private const val MAX_NAVIGATION_ATTEMPTS = 3
        /** 微信冷启动开屏广告/启动页等待上限（10 次 × 800ms ≈ 8 秒） */
        private const val MAX_LAUNCH_WAIT = 10

        /** 禁用下拉通知栏开关（存于 OnePassPrefs，默认关闭） */
        const val KEY_BLOCK_NOTIFICATION_SHADE = "block_notification_shade"
        /** 通知栏收起冷却，避免同一事件流重复触发 */
        private const val SHADE_DISMISS_COOLDOWN_MS = 600L
        /** 轮询间隔：部分 ROM 不派发 systemui 事件，用活动窗口检测兜底 */
        private const val SHADE_POLL_INTERVAL_MS = 500L
        /** 通知栏/快捷面板窗口类名特征（仅 Android 10 及以下用返回键时要求强证据） */
        private val SHADE_CLASS_PATTERNS =
            listOf("NotificationShade", "NotificationPanel", "QuickSettings", "Shade", "StatusBar")

        /** 抖音包名 */
        const val PKG_DOUYIN = "com.ss.android.ugc.aweme"
        /** 抖音安心刷主开关（存于 OnePassPrefs，默认关闭） */
        const val KEY_DOUYIN_SAFE_MODE = "douyin_safe_mode"
        /** 回到抖音首页最多按返回次数（超限直接重启抖音兜底） */
        private const val MAX_DOUYIN_BACK_PRESSES = 6
        private const val DOUYIN_BACK_INTERVAL_MS = 700L
        private const val DOUYIN_LAUNCH_WAIT_MS = 1600L
        /** 弹窗自动关闭冷却，避免误关/刷屏 */
        private const val POPUP_CLOSE_COOLDOWN_MS = 8_000L
        /** 直播语音提示冷却 */
        private const val LIVE_HINT_COOLDOWN_MS = 60_000L
        /** 弹窗关闭按钮候选文案（只对抖音生效） */
        private val CLOSE_BUTTON_TEXTS = listOf(
            "以后再说", "暂不更新", "暂不", "取消", "知道了", "我知道了", "好的", "忽略", "关闭", "跳过", "不用了"
        )
        /**
         * 弹窗类名特征：只匹配真正的对话框/更新弹窗。
         * 刻意排除 Popup/ActionSheet/Tip 等——抖音评论区、分享面板类名常含这些词，
         * 误判会把用户正常关闭面板的操作"加倍"（多按一次返回）。
         */
        private val DIALOG_CLASS_PATTERNS = listOf("Dialog", "Update", "Upgrade", "Version")
        /** 更新/升级类弹窗文案特征（只有命中才允许按返回兜底关闭） */
        private val UPDATE_TEXT_MARKERS = listOf("更新", "升级", "新版本", "版本")
        /** 直播间识别特征（命中任意即语音提示；只扫屏幕顶部区域，取顶部可见特征） */
        private val LIVE_MARKERS = listOf("人在看", "直播中", "直播间", "观看直播")
        /**
         * 侧边栏/抽屉特征文案：这些条目出现说明首页被半屏面板遮挡，
         * 即使树里同时存在「已选中，推荐」也不能算已回首页，需继续返回。
         */
        private val DRAWER_MARKERS = listOf("观看历史", "我的钱包", "离线缓存", "扫一扫", "乘车码")

        @Volatile
        private var instance: SelectToSpeakService? = null

        /** 抖音是否在前台（无障碍服务维护，悬浮按钮服务轮询读取） */
        @Volatile
        var isDouyinForeground: Boolean = false
            private set

        /**
         * 远程协助：注入一次屏幕点击（无障碍手势）
         */
        fun performTap(x: Float, y: Float): Boolean {
            val svc = instance ?: return false
            svc.performClick(x, y)
            return true
        }

        /**
         * 抖音安心刷：一键回到抖音视频首页（循环按返回，卡在复杂界面则重启抖音）
         */
        fun returnToDouyinFeed() {
            instance?.startReturnToFeed()
        }

        /**
         * 实时查询当前活动窗口包名（悬浮按钮服务轮询用）。
         * 事件标记可能因权限弹窗等临时窗口而过期，实时查询更可靠，
         * 只取根节点属性不拉全树，开销小。
         */
        fun getActiveWindowPackage(): String? {
            val svc = instance ?: return null
            return runCatching {
                val root = svc.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                root?.safeRecycle()
                pkg
            }.getOrNull()
        }

        /**
         * 远程协助：注入一次滑动（无障碍手势），用于滚动/翻页
         */
        fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val svc = instance ?: return false
            svc.performSwipeGesture(x1, y1, x2, y2, durationMs)
            return true
        }
    }

    // 协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    /** 返回抖音首页循环专用：后台线程，避免巨树读取阻塞主线程 */
    private val returnScope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    // 状态管理 - 使用AtomicBoolean确保线程安全
    private val isProcessing = AtomicBoolean(false)
    private var retryCount = 0
    private var navigationAttempts = 0
    private var lastWindowClassName = ""
    /** 最近一次微信内事件源包名（仅记录微信界面事件） */
    private var lastEventPackage = ""
    /** 微信冷启动开屏广告/启动页等待计数 */
    private var launchWaitCount = 0

    // 主动触发下一步的延迟任务
    private var nextStepRunnable: Runnable? = null

    // 微信消息点读
    private val messageReader by lazy { WeChatMessageReader(this) }

    /** 上次收起通知栏的时间（防重复触发） */
    private var lastShadeDismissAt = 0L

    /** 抖音弹窗自动关闭冷却时间点 */
    private var lastPopupCloseAt = 0L
    /** 抖音直播语音提示冷却时间点 */
    private var lastLiveHintAt = 0L
    /** 回抖音首页导航进行中（防止重复触发/与弹窗关闭互相干扰） */
    private var douyinReturning = false
    /** 最近一次抖音窗口状态事件的类名（页面切换会触发；fragment 级页面不触发，用于辅助判断） */
    private var lastDouyinClassName = ""
    /** 输入法（键盘）包名缓存，用于前台跟踪时排除 IME 窗口，避免按钮误隐藏 */
    private var cachedImePackage: String? = null

    /**
     * 通知栏拦截轮询：realme/ColorOS 等 ROM 派发的 systemui 事件类名是
     * android.widget.FrameLayout，无法按类名识别；改用「活动窗口包名」判断——
     * 通知栏打开时活动窗口就是 com.android.systemui。事件触发为快速路径，轮询兜底。
     */
    private val shadePollRunnable = object : Runnable {
        override fun run() {
            if (isNotificationShadeBlockEnabled()) {
                dismissShadeIfOpen()
            }
            mainHandler.postDelayed(this, SHADE_POLL_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 微信消息点读：优先处理，不影响拨号自动化（内部有开关与状态保护）
        runCatching { messageReader.handleEvent(event) { rootInActiveWindow } }

        // 禁用下拉通知栏（设置项开启时）：检测到通知栏/快捷面板打开立即收起
        handleNotificationShadeBlock(event)

        // 抖音安心刷：前台跟踪 / 弹窗自动关闭 / 直播语音提示
        handleDouyinSafeMode(event)

        val currentActivity = event?.className?.toString() ?: run {
            Log.d(TAG, "事件为空或className为null")
            return
        }
        val eventPackage = event?.packageName?.toString() ?: ""

        // 跳过系统组件和无效事件
        if (shouldSkipActivity(currentActivity, eventPackage)) {
            Log.d(TAG, "跳过Activity: $currentActivity pkg=$eventPackage")
            return
        }

        // 门卫：拨号自动化只在微信界面内执行。
        // 若事件源不是微信（MoreTalk 主界面/桌面/系统UI），不更新 lastWindowClassName，
        // 也不触发任何步骤，避免自动化在错误界面执行返回/重置。
        if (WeChatData.index > 0 && eventPackage != "com.tencent.mm") {
            return
        }

        lastWindowClassName = currentActivity
        lastEventPackage = eventPackage
        Log.d(TAG, "Current Activity: $currentActivity, Step: ${WeChatData.index}")

        // 如果正在处理，跳过新事件（使用原子操作确保线程安全）
        if (isProcessing.get()) {
            Log.d(TAG, "正在处理中，跳过新事件: $currentActivity")
            return
        }


        when (WeChatData.index) {
            1 -> processStep1(currentActivity)
            2 -> processStep2(currentActivity)
            3 -> processStep3(currentActivity)
            4 -> processStep4(currentActivity)
            5 -> processStep5(currentActivity)
            6 -> processStep6(currentActivity)
            7 -> processStep7(currentActivity)
        }
    }

    /**
     * 事件快速路径：systemui 有窗口状态变化时立即检查（比轮询更快响应）。
     * 真正判断在 dismissShadeIfOpen（活动窗口包名），不依赖事件类名。
     */
    private fun handleNotificationShadeBlock(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != "com.android.systemui") return
        dismissShadeIfOpen()
    }

    /**
     * 核心判断：活动窗口包名为 com.android.systemui 即视为通知栏/快捷面板打开，立即收起。
     * Android 11+ 用 DISMISS_NOTIFICATION_SHADE（面板未打开时是无害空操作）；
     * Android 10 及以下用返回键，此时要求根节点类名含通知栏特征，避免误按返回。
     */
    private fun dismissShadeIfOpen() {
        if (!isNotificationShadeBlockEnabled()) return
        // 锁屏时活动窗口也是 systemui，跳过以免干扰解锁/锁屏通知
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString()
        val cls = root.className?.toString()
        root.recycle()

        if (pkg != "com.android.systemui") return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (cls == null || SHADE_CLASS_PATTERNS.none { cls.contains(it) })
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastShadeDismissAt < SHADE_DISMISS_COOLDOWN_MS) return
        lastShadeDismissAt = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        Log.d(TAG, "检测到通知栏打开，已收起 (activeWindow=$pkg/$cls)")
    }

    private fun isNotificationShadeBlockEnabled(): Boolean {
        return getSharedPreferences("OnePassPrefs", MODE_PRIVATE)
            .getBoolean(KEY_BLOCK_NOTIFICATION_SHADE, false)
    }

    // ==================== 抖音安心刷 ====================

    private fun isDouyinSafeModeEnabled(): Boolean {
        return getSharedPreferences("OnePassPrefs", MODE_PRIVATE)
            .getBoolean(KEY_DOUYIN_SAFE_MODE, false)
    }

    /**
     * 抖音安心刷入口：跟踪抖音前台状态（悬浮按钮服务据此显示/隐藏），
     * 抖音出现弹窗时自动关闭，识别到直播间时语音提示。
     * 输入法（键盘）与系统 UI 的窗口变化不会改变前台归属，避免按钮"闪没"。
     */
    private fun handleDouyinSafeMode(event: AccessibilityEvent?) {
        if (!isDouyinSafeModeEnabled()) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg == PKG_DOUYIN -> {
                isDouyinForeground = true
                lastDouyinClassName = event.className?.toString() ?: ""
            }
            pkg == "com.android.systemui" || isImePackage(pkg) -> Unit
            else -> isDouyinForeground = false
        }
        if (pkg != PKG_DOUYIN) return
        // 自己正在导航返回时不干预
        if (douyinReturning) return
        handleDouyinPopup(event)
        checkDouyinLive()
    }

    /** 判断事件包名是否为输入法（键盘），避免键盘弹出导致按钮误隐藏 */
    private fun isImePackage(pkg: String): Boolean {
        val ime = cachedImePackage ?: runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore("/")
        }.getOrNull() ?: return false
        cachedImePackage = ime
        return pkg == ime
    }

    /**
     * 弹窗自动关闭：只处理真正的对话框/更新弹窗。
     * 先找白名单关闭按钮点击；找不到时**仅当确认是更新/升级类弹窗**才按返回兜底，
     * 避免误关评论区/分享面板等正常界面（那会让用户的操作被"加倍"）。
     * 带冷却防误关/防刷屏；只处理抖音包事件。
     */
    private fun handleDouyinPopup(event: AccessibilityEvent?) {
        val cls = event?.className?.toString() ?: return
        val isDialogLike = DIALOG_CLASS_PATTERNS.any { cls.contains(it) }
        if (!isDialogLike) return

        val now = System.currentTimeMillis()
        if (now - lastPopupCloseAt < POPUP_CLOSE_COOLDOWN_MS) return
        lastPopupCloseAt = now
        Log.d(TAG, "检测到抖音弹窗: $cls")

        serviceScope.launch {
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@launch
            try {
                val closeBtn = findCloseButton(root)
                if (closeBtn != null) {
                    closeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    closeBtn.safeRecycle()
                    Log.d(TAG, "抖音弹窗已点击关闭按钮")
                } else {
                    // 只有更新/升级类弹窗才按返回兜底
                    val isUpdateDialog = UPDATE_TEXT_MARKERS.any { hasAnyText(root, it) }
                    if (isUpdateDialog) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        Log.d(TAG, "抖音更新弹窗，按返回关闭")
                    } else {
                        Log.d(TAG, "抖音弹窗无关闭按钮且非更新类，不干预")
                    }
                }
            } finally {
                root.safeRecycle()
            }
        }
    }

    /** 在根节点中查找可点击的关闭按钮（白名单文案），返回的节点由调用方回收 */
    private fun findCloseButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (text in CLOSE_BUTTON_TEXTS) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull()
            if (nodes != null) {
                for (node in nodes) {
                    if (node.isClickable) {
                        nodes.filter { it !== node }.safeRecycleAll()
                        return node
                    }
                }
                nodes.safeRecycleAll()
            }
        }
        return null
    }

    /**
     * 直播语音提示（尽力识别）：扫描屏幕顶部区域命中直播特征即播报一次。
     * 识别不到/误报都只是偶尔多一句提示，不影响使用；60s 冷却。
     */
    private fun checkDouyinLive() {
        val now = System.currentTimeMillis()
        if (now - lastLiveHintAt < LIVE_HINT_COOLDOWN_MS) return
        serviceScope.launch {
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@launch
            val hit = scanTopRegion(root) { text, desc ->
                LIVE_MARKERS.any { text.contains(it) || desc.contains(it) }
            }
            if (hit) {
                lastLiveHintAt = System.currentTimeMillis()
                messageReader.speak("您正在看直播，点红色返回按钮可以退出")
                Log.d(TAG, "识别到直播间，已语音提示")
            }
        }
    }

    /**
     * 一键回抖音首页：循环按返回直到识别到抖音首页，按满次数仍没到则直接重启抖音。
     * 读不到界面（根节点为 null，如无无障碍内容的弹层窗口）时也先按返回，
     * 避免空等；循环在后台线程执行，防止巨树读取阻塞主线程。
     */
    private fun startReturnToFeed() {
        if (douyinReturning) return
        douyinReturning = true
        Log.d(TAG, "开始返回抖音首页")
        returnScope.launch {
            try {
                var remaining = MAX_DOUYIN_BACK_PRESSES
                while (remaining > 0) {
                    val root = runCatching { rootInActiveWindow }.getOrNull()
                    if (root == null) {
                        // 无法读取界面（弹层/抽屉窗口常无无障碍内容）：默认按返回
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        remaining--
                        delay(DOUYIN_BACK_INTERVAL_MS)
                        continue
                    }
                    val pkg = root.packageName?.toString()
                    // isDouyinMainFeed 内部负责回收 root
                    if (isDouyinMainFeed(root)) {
                        break
                    }
                    if (pkg != PKG_DOUYIN) {
                        // 已不在抖音（返回键退到桌面等）：直接重启抖音回首页
                        Log.d(TAG, "抖音已不在前台，重启抖音")
                        launchDouyin()
                        delay(DOUYIN_LAUNCH_WAIT_MS)
                        break
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    remaining--
                    delay(DOUYIN_BACK_INTERVAL_MS)
                }
                if (remaining <= 0) {
                    // 循环结束仍未到首页：重启抖音兜底（CLEAR_TASK 全新进入视频流）
                    Log.d(TAG, "返回次数用尽仍未到首页，重启抖音兜底")
                    launchDouyin()
                    delay(DOUYIN_LAUNCH_WAIT_MS)
                }
                messageReader.speak("已回到抖音首页")
            } finally {
                douyinReturning = false
            }
        }
    }

    /**
     * 抖音首页判定：
     * 1) 窗口事件类名已知时，非 main.MainActivity 的页面（视频详情/直播/搜索等）直接排除；
     * 2) 顶部频道标签条「推荐」选中（content-desc「已选中，推荐」），且只信**可见**节点——
     *    用户主页/半屏面板是同一窗口内的覆盖层，被盖住的首页标签条仍留在节点树里，
     *    必须用 isVisibleToUser 过滤，否则拿到的是"看不见的首页"。
     * 3) 排除侧边栏/抽屉特征（出现即继续返回）。
     * 内部负责回收获取的全部节点（含 root）。
     */
    private fun isDouyinMainFeed(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName?.toString() != PKG_DOUYIN) return false
        if (lastDouyinClassName.isNotBlank() &&
            !lastDouyinClassName.contains("main.MainActivity")
        ) {
            return false
        }
        val feedTabSelected = scanTopRegion(root) { _, desc ->
            desc.contains("已选中") && desc.contains("推荐")
        }
        if (!feedTabSelected) return false
        // 半屏侧边栏/抽屉打开时首页仍被遮挡：出现这些条目就继续返回
        return DRAWER_MARKERS.none { hasAnyText(root, it) }
    }

    /**
     * 有界扫描节点树：只深入屏幕顶部区域且**仅信任对用户可见的节点**
     * （isVisibleToUser=false 的节点是被覆盖/隐藏的，不算数），
     * 跳过下方视频内容的大块子树，保证毫秒级完成。match 返回 true 即命中；
     * 内部负责回收全部获取的节点。
     */
    private fun scanTopRegion(
        root: AccessibilityNodeInfo,
        match: (text: String, desc: String) -> Boolean
    ): Boolean {
        var visited = 0
        val acquired = mutableListOf<AccessibilityNodeInfo>()
        val limitTop = minOf(700, resources.displayMetrics.heightPixels * 3 / 10)
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            acquired.add(root)
            while (queue.isNotEmpty() && visited < 400) {
                val node = queue.removeFirst()
                visited++
                // 不可见节点（被其他页面/面板覆盖）直接跳过，其子树同样不可见
                if (!node.isVisibleToUser) continue
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                if (match(text, desc)) return true
                // 整棵子树都在顶部区域之外：跳过，不深入
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.top > limitTop) continue
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    acquired.add(child)
                    queue.add(child)
                }
            }
        } finally {
            acquired.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    private fun hasAnyText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull()
        if (nodes != null) {
            if (nodes.isNotEmpty()) {
                nodes.safeRecycleAll()
                return true
            }
            nodes.safeRecycleAll()
        }
        return false
    }

    /** 重启抖音（CLEAR_TASK 清理任务栈，直接回到全新视频流） */
    private fun launchDouyin() {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(PKG_DOUYIN)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }.onFailure { e ->
            Log.e(TAG, "启动抖音失败: ${e.message}")
        }
    }

    /**
     * 判断是否需要跳过某些事件
     */
    private fun shouldSkipActivity(activityName: String, eventPackage: String): Boolean {
        return activityName.contains("Toast") ||
                activityName.contains("SoftInputWindow") ||
                activityName == "com.example.onepass.MainActivity" ||
                eventPackage == "com.android.systemui" ||
                eventPackage == "android" ||
                eventPackage == "com.example.onepass" ||
                eventPackage == "com.android.settings"
    }

    // ==================== 步骤处理 ====================

    /**
     * 步骤1: 确保在微信首页
     * 逻辑：如果在首页 -> 进入步骤2；如果在其他页面 -> 执行返回直到回到首页
     */
    private fun processStep1(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤1，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            try {
                Log.d(TAG, "步骤1 - 当前Activity: $currentActivity")
                when {
                    // 已在首页
                    isWechatHomePage(currentActivity) -> {
                        Log.d(TAG, ">>> 已在微信首页，点击底部【微信】按钮返回聊天列表 <<<")
                        var rootNode: AccessibilityNodeInfo? = null
                        var wechatTab: List<AccessibilityNodeInfo>? = null
                        try {
                            rootNode = rootInActiveWindow
                            if (rootNode != null) {
                                // 方法1: 通过View ID查找
                                wechatTab = rootNode.findAccessibilityNodeInfosByViewId(WeChatId.BOTTOM_WECHAT.id)
                                if (wechatTab.isEmpty()) {
                                    // 方法2: 通过文本"微信"查找
                                    wechatTab = rootNode.findAccessibilityNodeInfosByText("微信")
                                }
                                if (wechatTab.isEmpty()) {
                                    // 方法3: 通过contentDescription查找
                                    wechatTab = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/dn")
                                }

                                if (wechatTab.isNotEmpty()) {
                                    val tabNode = wechatTab.first()
                                    val clickResult = if (tabNode.isClickable) {
                                        tabNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    } else {
                                        tabNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                                    }
                                    Log.d(TAG, "点击底部【微信】按钮结果: $clickResult")
                                    waitStep(500)
                                } else {
                                    Log.d(TAG, "未找到底部【微信】按钮，直接进入步骤2")
                                }
                            }
                            Log.d(TAG, ">>> 进入步骤2 <<<")
                            resetRetryAndNavigation()
                            WeChatData.updateIndex(2)
                            setProcessing(false)
                            scheduleNextStep(500)
                            return@launch
                        } finally {
                            wechatTab?.safeRecycleAll()
                            rootNode?.safeRecycle()
                        }
                    }
                    // 在搜索界面，清空搜索框并进入步骤3
                    isSearchPage(currentActivity) -> {
                        Log.d(TAG, ">>> 在搜索界面，清空搜索框并进入步骤3 <<<")
                        var rootNode: AccessibilityNodeInfo? = null
                        try {
                            rootNode = rootInActiveWindow
                            if (rootNode != null) {
                                val inputNode = findInputField(rootNode)
                                if (inputNode != null && inputNode.isEditable) {
                                    val clearResult = clearInputField(inputNode)
                                    if (clearResult) {
                                        waitStep(200)
                                        resetRetryAndNavigation()
                                        WeChatData.updateIndex(3)
                                        setProcessing(false)
                                        scheduleNextStep(500)
                                        return@launch
                                    } else {
                                        Log.e(TAG, "清空输入框失败，重试")
                                        handleRetry("清空输入框失败", 1)
                                        setProcessing(false)
                                        return@launch
                                    }
                                } else {
                                    Log.d(TAG, "未找到输入框，直接进入步骤3")
                                    resetRetryAndNavigation()
                                    WeChatData.updateIndex(3)
                                    setProcessing(false)
                                    scheduleNextStep(500)
                                    return@launch
                                }
                            } else {
                                Log.d(TAG, "rootNode为空，直接进入步骤3")
                                resetRetryAndNavigation()
                                WeChatData.updateIndex(3)
                                setProcessing(false)
                                scheduleNextStep(500)
                                return@launch
                            }
                        } finally {
                            rootNode?.safeRecycle()
                        }
                    }
                    // 在聊天界面
                    isChatPage(currentActivity) -> {
                        Log.d(TAG, ">>> 在聊天界面，执行返回 <<<")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        waitStep(500)
                        incrementNavigationAttempts()
                        setProcessing(false)
                        scheduleNextStep(500)
                        return@launch
                    }
                    // 有弹窗
                    // 疑似弹窗
                    isDialogPage(currentActivity) -> {
                        Log.d(TAG, ">>> 疑似弹窗: $currentActivity, pkg=$lastEventPackage <<<")
                        if (lastEventPackage == "com.tencent.mm") {
                            // 在微信内：冷启动的开屏广告页/启动页 className 常与弹窗相似
                            // （如 com.tencent.mm.ui.widget.dialog）。此时绝不能返回（会把微信退掉），
                            // 应耐心等待其自动进入首页。开屏广告一般最多停留数秒。
                            if (launchWaitCount >= MAX_LAUNCH_WAIT) {
                                Log.d(TAG, ">>> 启动等待超时仍疑似弹窗，尝试关闭并重置 <<<")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                waitStep(500)
                                launchWaitCount = 0
                            } else {
                                Log.d(TAG, ">>> 微信内疑似弹窗/开屏广告页，等待 (${launchWaitCount + 1}/$MAX_LAUNCH_WAIT) <<<")
                                launchWaitCount++
                            }
                            setProcessing(false)
                            scheduleNextStep(800)
                            return@launch
                        }
                        // 非微信内的系统弹窗（如权限弹窗）：关闭
                        Log.d(TAG, ">>> 非微信弹窗，关闭 <<<")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        waitStep(500)
                        setProcessing(false)
                        scheduleNextStep(500)
                        return@launch
                    }
                    // 其他页面
                    else -> {
                        Log.d(TAG, ">>> 在其他页面，当前Activity: $currentActivity, pkg=$lastEventPackage <<<")
                        val inWechat = lastEventPackage == "com.tencent.mm"
                        if (inWechat) {
                            // 在微信内但非首页：通常是冷启动开屏广告页/启动页。
                            // 此时不能返回（会把微信退掉），耐心等待其自动进入首页。
                            if (launchWaitCount >= MAX_LAUNCH_WAIT) {
                                Log.d(TAG, ">>> 启动等待超时仍非首页，执行返回并重置计数 <<<")
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                waitStep(500)
                                launchWaitCount = 0
                            } else {
                                Log.d(TAG, ">>> 微信内非首页，等待进入首页 (${launchWaitCount + 1}/$MAX_LAUNCH_WAIT) <<<")
                                launchWaitCount++
                            }
                            setProcessing(false)
                            scheduleNextStep(800)
                            return@launch
                        }
                        // 不在微信内（回桌面/其它应用）：执行返回回到微信
                        if (navigationAttempts < MAX_NAVIGATION_ATTEMPTS) {
                            Log.d(TAG, ">>> 不在微信内，执行返回 (${navigationAttempts + 1}/$MAX_NAVIGATION_ATTEMPTS) <<<")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            waitStep(500)
                            incrementNavigationAttempts()
                            setProcessing(false)
                            scheduleNextStep(500)
                            return@launch
                        } else {
                            Log.d(TAG, ">>> 导航尝试次数达到上限，重置 <<<")
                            resetAndStop()
                            setProcessing(false)
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤1处理失败", e)
                handleError(1)
                setProcessing(false)
            }
        }
    }

    /**
     * 步骤2: 点击搜索按钮
     */
    private fun processStep2(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤2，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            PerformanceMonitor.startTimer("step2_clickSearch")
            setProcessing(true)
            var rootNode: AccessibilityNodeInfo? = null
            try {
                rootNode = rootInActiveWindow ?: run {
                    Log.d(TAG, "rootNode为空")
                    setProcessing(false)
                    PerformanceMonitor.endTimer("step2_clickSearch")
                    return@launch
                }

                // 查找搜索按钮 - 优先查找可点击的搜索图标
                val searchNode = findSearchButton(rootNode)

                if (searchNode != null) {
                    Log.d(TAG, "点击搜索按钮")
                    val clickResult = searchNode.click()
                    Log.d(TAG, "搜索按钮点击结果: $clickResult")
                    if (!clickResult) {
                        Log.e(TAG, "搜索按钮点击失败，重试")
                        handleRetry("搜索按钮点击失败", 1)
                        setProcessing(false)
                        PerformanceMonitor.endTimer("step2_clickSearch")
                        return@launch
                    }
                    waitStep(500)
                    resetRetryAndNavigation()
                    WeChatData.updateIndex(3)
                    setProcessing(false)
                    PerformanceMonitor.endTimer("step2_clickSearch")
                    scheduleNextStep(500)
                    return@launch
                } else {
                    Log.d(TAG, "未找到搜索按钮，可能已在搜索界面")
                    // 检查是否已经在搜索界面
                    if (isSearchPage(currentActivity)) {
                        Log.d(TAG, "已在搜索界面，进入步骤3")
                        resetRetryAndNavigation()
                        WeChatData.updateIndex(3)
                        setProcessing(false)
                        PerformanceMonitor.endTimer("step2_clickSearch")
                        scheduleNextStep(500)
                        return@launch
                    } else {
                        handleRetry("未找到搜索按钮", 1)
                        setProcessing(false)
                        PerformanceMonitor.endTimer("step2_clickSearch")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤2处理失败", e)
                handleError(1)
                setProcessing(false)
                PerformanceMonitor.endTimer("step2_clickSearch")
            } finally {
                rootNode?.safeRecycle()
            }
        }
    }

    /**
     * 步骤3: 输入联系人昵称
     */
    private fun processStep3(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤3，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            var rootNode: AccessibilityNodeInfo? = null
            try {
                rootNode = rootInActiveWindow ?: run {
                    Log.d(TAG, "rootNode为空")
                    setProcessing(false)
                    return@launch
                }

                // 查找输入框
                val inputNode = findInputField(rootNode)

                if (inputNode != null && inputNode.isEditable) {
                    Log.d(TAG, "输入联系人: ${WeChatData.value}")
                    val result = inputNode.input(WeChatData.value)
                    Log.d(TAG, "输入结果: $result")
                    if (!result) {
                        Log.e(TAG, "输入失败，重试")
                        handleRetry("输入失败", 2)
                        setProcessing(false)
                        return@launch
                    }
                    Log.d(TAG, "输入成功，等待搜索结果")
                    waitStep(500)
                    resetRetryAndNavigation()
                    WeChatData.updateIndex(4)
                    setProcessing(false)
                    scheduleNextStep(500)
                    return@launch
                } else {
                    handleRetry("未找到输入框", 2)
                    setProcessing(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤3处理失败", e)
                handleError(2)
                setProcessing(false)
            } finally {
                rootNode?.safeRecycle()
            }
        }
    }

    /**
     * 步骤4: 选择第一个搜索结果进入聊天界面
     */
    private fun processStep4(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤4，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            var rootNode: AccessibilityNodeInfo? = null
            try {
                rootNode = rootInActiveWindow ?: run {
                    Log.d(TAG, "rootNode为空")
                    setProcessing(false)
                    return@launch
                }

                // 查找搜索结果列表
                val contactNode = findSearchResult(rootNode)

                if (contactNode != null) {
                    Log.d(TAG, "点击联系人")
                    val clickResult = contactNode.click()
                    if (!clickResult) {
                        Log.e(TAG, "点击联系人失败，重试")
                        handleRetry("点击联系人失败", 3)
                        setProcessing(false)
                        return@launch
                    }
                    waitStep(500)
                    resetRetryAndNavigation()
                    WeChatData.updateIndex(5)
                    setProcessing(false)
                    scheduleNextStep(500)
                    return@launch
                } else {
                    // 搜索结果可能还未加载，不重试，等待下一个事件
                    Log.d(TAG, "搜索结果未出现，继续等待")
                    setProcessing(false)
                    scheduleNextStep(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤4处理失败", e)
                handleError(3)
                setProcessing(false)
            } finally {
                rootNode?.safeRecycle()
            }
        }
    }

    /**
     * 步骤5: 点击更多按钮(+)
     */
    private fun processStep5(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤5，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            var rootNode: AccessibilityNodeInfo? = null
            try {
                rootNode = rootInActiveWindow ?: run {
                    Log.d(TAG, "rootNode为空")
                    setProcessing(false)
                    return@launch
                }

                // 查找更多按钮。
                // 注意：不能再用 isChatPage(className) 判断是否在聊天界面——微信新版聊天界面
                // 的无障碍事件 className 是 LinearLayout/FrameLayout 等视图类名，不等于 ChattingUI，
                // 会导致永远判定"不在聊天界面"而卡死（上游原始 bug）。
                // 正确做法：直接尝试查找"更多"按钮，找不到时用"是否有消息输入框"兜底判断。
                val moreNode = findMoreButton(rootNode)

                if (moreNode != null) {
                    Log.d(TAG, "点击更多按钮")
                    val clickResult = moreNode.click()
                    if (!clickResult) {
                        Log.e(TAG, "点击更多按钮失败，重试")
                        handleRetry("点击更多按钮失败", 5)
                        setProcessing(false)
                        return@launch
                    }
                    waitStep(500)
                    resetRetryAndNavigation()
                    WeChatData.updateIndex(6)
                    setProcessing(false)
                    scheduleNextStep(500)
                    return@launch
                } else {
                    // 找不到更多按钮：用"是否已有消息输入框"判断是否在聊天界面
                    val inChat = hasChatInputField(rootNode)
                    if (inChat) {
                        Log.d(TAG, "已在聊天界面但更多按钮未就绪，等待界面刷新")
                        setProcessing(false)
                        scheduleNextStep(300)
                    } else {
                        Log.d(TAG, "未找到更多按钮且无输入框（可能仍在搜索结果页），重试")
                        handleRetry("未找到更多按钮", 5)
                        setProcessing(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤5处理失败", e)
                handleError(4)
                setProcessing(false)
            } finally {
                rootNode?.safeRecycle()
            }
        }
    }

    /**
     * 步骤6: 点击视频通话菜单（从更多面板中选择）
     */
    private fun processStep6(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤6，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            var menuNodes: List<AccessibilityNodeInfo>? = null
            try {
                // 更多面板展开时，Activity 通常仍为微信聊天界面
                val root = rootInActiveWindow
                val callText = WeChatData.findText(false) // 获取“视频通话”文字
                Log.d(TAG, "步骤6 - 正在查找更多面板中的文字: $callText")

                menuNodes = root?.findAccessibilityNodeInfosByText(callText)

                if (!menuNodes.isNullOrEmpty()) {
                    val targetNode = menuNodes.first()
                    val rect = Rect()
                    targetNode.getBoundsInScreen(rect)

                    Log.d(TAG, "找到通话图标，位置: (${rect.centerX()}, ${rect.centerY()})，执行模拟点击")

                    // 使用你代码中的 performClick 模拟物理点击，解决 performAction 无效的问题
                    performClick(rect.centerX().toFloat(), rect.centerY().toFloat())

                    waitStep(800) // 等待底部菜单弹窗弹出
                    WeChatData.updateIndex(7)
                    setProcessing(false)
                    scheduleNextStep(200)
                } else {
                    Log.e(TAG, "更多面板中未找到文字: $callText")
                    handleRetry("未找到通话图标", 5)
                    setProcessing(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤6处理失败", e)
                handleError(5)
                setProcessing(false)
            } finally {
                menuNodes?.safeRecycleAll()
            }
        }
    }

    /**
     * 步骤7: 点击视频/语音通话选项（底部确认弹窗）
     */
    private fun processStep7(currentActivity: String) {
        Log.d(TAG, ">>> 进入步骤7，当前Activity: $currentActivity <<<")
        serviceScope.launch {
            setProcessing(true)
            var options: List<AccessibilityNodeInfo>? = null
            try {
                // 判断是否在弹窗页面，或者 Activity 还没切换但弹窗已出的情况
                val root = rootInActiveWindow
                val confirmText = WeChatData.findText(true) // 获取弹窗里的“视频通话”
                Log.d(TAG, "步骤7 - 正在查找弹窗选项: $confirmText")

                options = root?.findAccessibilityNodeInfosByText(confirmText)

                if (!options.isNullOrEmpty()) {
                    val optionNode = options.first()

                    // 优先尝试标准点击
                    var clickResult = optionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                    // 如果标准点击失败，尝试坐标点击
                    if (!clickResult) {
                        val rect = Rect()
                        optionNode.getBoundsInScreen(rect)
                        performClick(rect.centerX().toFloat(), rect.centerY().toFloat())
                        clickResult = true
                    }

                    Log.d(TAG, "点击通话选项结果: $clickResult")
                    waitStep(500)
                    Log.d(TAG, ">>> 通话流程完成 <<<")
                    WeChatData.updateIndex(0)
                    setProcessing(false)
                } else {
                    // 如果没找到，可能是弹窗还没加载完，等待下一次事件
                    Log.d(TAG, "未找到通话选项，继续等待...")
                    setProcessing(false)
                    scheduleNextStep(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "步骤7处理失败", e)
                handleError(6)
                setProcessing(false)
            } finally {
                options?.safeRecycleAll()
            }
        }
    }

    // ==================== 页面判断 ====================

    private fun isWechatHomePage(activityName: String): Boolean {
        return activityName == WeChatActivity.INDEX.id
    }

    private fun isChatPage(activityName: String): Boolean {
        return activityName == WeChatActivity.CHAT.id
    }

    private fun isSearchPage(activityName: String): Boolean {
        return activityName == WeChatActivity.SEARCH.id
    }

    private fun isDialogPage(activityName: String): Boolean {
        return activityName == WeChatActivity.DIALOG.id ||
                activityName == WeChatActivity.DIALOG_OLD.id
    }

    // ==================== 节点查找工具 ====================

    private fun findSearchButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过ViewId查找（最快）
        var searchById: List<AccessibilityNodeInfo>? = null
        try {
            searchById = rootNode.findAccessibilityNodeInfosByViewId(WeChatId.SEARCH.id)
            if (searchById.isNotEmpty()) {
                return searchById.first()
            }
        } finally {
            searchById?.safeRecycleAll()
        }

        // 方法2: 通过文本查找
        var searchByText: List<AccessibilityNodeInfo>? = null
        try {
            searchByText = rootNode.findAccessibilityNodeInfosByText("搜索")
            if (searchByText.isNotEmpty()) {
                return searchByText.first()
            }
        } finally {
            searchByText?.safeRecycleAll()
        }

        // 方法3: 查找可点击的搜索图标
        return findClickableNodeByContent(rootNode, "搜索", "Search")
    }

    private fun findInputField(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过ViewId查找（最快）
        var inputById: List<AccessibilityNodeInfo>? = null
        try {
            inputById = rootNode.findAccessibilityNodeInfosByViewId(WeChatId.INPUT.id)
            if (inputById.isNotEmpty()) {
                return inputById.first()
            }
        } finally {
            inputById?.safeRecycleAll()
        }

        // 方法2: 查找可编辑节点
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes)
        if (editableNodes.isNotEmpty()) {
            return editableNodes.first()
        }

        return null
    }

    private fun findSearchResult(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过ViewId查找
        var listById: List<AccessibilityNodeInfo>? = null
        try {
            listById = rootNode.findAccessibilityNodeInfosByViewId(WeChatId.LIST.id)
            if (listById.isNotEmpty()) {
                return listById.first()
            }
        } finally {
            listById?.safeRecycleAll()
        }

        // 方法2: 查找列表类型的节点
        return findFirstClickableListItem(rootNode)
    }

    private fun findMoreButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 方法1: 通过ViewId查找（最快）
        var moreById: List<AccessibilityNodeInfo>? = null
        try {
            moreById = rootNode.findAccessibilityNodeInfosByViewId(WeChatId.MORE.id)
            if (moreById.isNotEmpty()) {
                return moreById.first()
            }
        } finally {
            moreById?.safeRecycleAll()
        }

        // 方法2: 通过文本查找
        var moreByText: List<AccessibilityNodeInfo>? = null
        try {
            moreByText = rootNode.findAccessibilityNodeInfosByText("更多")
            if (moreByText.isNotEmpty()) {
                return moreByText.first()
            }
        } finally {
            moreByText?.safeRecycleAll()
        }

        // 方法3: 查找可点击的更多图标
        return findClickableNodeByContent(rootNode, "更多", "More")
    }

    /**
     * 判断当前是否已进入微信聊天界面：聊天界面有可编辑的消息输入框。
     * 用于步骤5兜底判断（className 在新版微信中不可靠）。
     */
    private fun hasChatInputField(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes)
        if (editableNodes.isNotEmpty()) {
            editableNodes.safeRecycleAll()
            return true
        }
        return false
    }

    private fun findCallButton(): AccessibilityNodeInfo? {
        val callText = WeChatData.findText(true)
        Log.d(TAG, "查找通话按钮: $callText")

        var options: List<AccessibilityNodeInfo>? = null
        try {
            options = rootInActiveWindow?.findAccessibilityNodeInfosByText(callText)
            if (options != null && options.isNotEmpty()) {
                val callNode = options.first()
                Log.d(TAG, "找到通话按钮: $callText, 可点击: ${callNode.isClickable}")
                return callNode
            }
        } finally {
            options?.safeRecycleAll()
        }

        return null
    }

    private fun findConfirmButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 查找确认、确定等按钮
        val confirmTexts = listOf("视频通话", "语音通话", "确定", "确认", "呼叫", "OK", "Confirm")

        for (text in confirmTexts) {
            var nodes: List<AccessibilityNodeInfo>? = null
            try {
                nodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            return node
                        }
                    }
                }
            } finally {
                nodes?.safeRecycleAll()
            }
        }
        return null
    }

    private fun findNodeById(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        var nodes: List<AccessibilityNodeInfo>? = null
        try {
            nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            return if (nodes.isNotEmpty()) nodes.first() else null
        } finally {
            nodes?.safeRecycleAll()
        }
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return if (nodes.isNotEmpty()) nodes.first() else null
    }

    private fun findNodeByDesc(rootNode: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        return findNodeByDescRecursive(rootNode, desc, 0, 20)
    }

    private fun findNodeByDescRecursive(node: AccessibilityNodeInfo?, desc: String, currentDepth: Int, maxDepth: Int): AccessibilityNodeInfo? {
        if (node == null || currentDepth > maxDepth) return null

        if (node.contentDescription?.toString()?.contains(desc) == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val result = findNodeByDescRecursive(node.getChild(i), desc, currentDepth + 1, maxDepth)
            if (result != null) return result
        }
        return null
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        findEditableNodesRecursive(node, result, 0, 20)
    }

    private fun findEditableNodesRecursive(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>, currentDepth: Int, maxDepth: Int) {
        if (node == null || currentDepth > maxDepth) return

        if (node.isEditable) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            findEditableNodesRecursive(node.getChild(i), result, currentDepth + 1, maxDepth)
        }
    }

    private fun findFirstClickableListItem(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        return findFirstClickableListItemRecursive(node, 0, 20)
    }

    private fun findFirstClickableListItemRecursive(node: AccessibilityNodeInfo?, currentDepth: Int, maxDepth: Int): AccessibilityNodeInfo? {
        if (node == null || currentDepth > maxDepth) return null

        if (node.isClickable && node.isEnabled) {
            val className = node.className?.toString() ?: ""
            if (!className.contains("Tab") && !className.contains("Bottom")) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val result = findFirstClickableListItemRecursive(node.getChild(i), currentDepth + 1, maxDepth)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableNodeByContent(rootNode: AccessibilityNodeInfo, vararg texts: String): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    return node
                }
            }
        }
        return null
    }

    // ==================== 节点操作扩展 ====================

    private fun AccessibilityNodeInfo.click(): Boolean {
        return if (isClickable) {
            performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            parent?.click() == true
        }
    }

    private fun AccessibilityNodeInfo.input(text: String): Boolean {
        return if (isEditable) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            parent?.input(text) == true
        }
    }

    // ==================== 错误处理和重试 ====================

    private fun handleError(backToStep: Int) {
        retryCount++
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.e(TAG, "重试次数达到上限，重置流程")
            resetAndStop()
        } else {
            Log.d(TAG, "回到步骤$backToStep (重试 $retryCount/$MAX_RETRY_COUNT)")
            WeChatData.updateIndex(backToStep)
            scheduleNextStep(500)
        }
    }

    private fun handleRetry(message: String, backToStep: Int) {
        Log.d(TAG, "$message，准备重试")
        handleError(backToStep)
    }

    private fun incrementNavigationAttempts() {
        navigationAttempts++
    }

    private fun resetRetryAndNavigation() {
        retryCount = 0
        navigationAttempts = 0
        launchWaitCount = 0
    }

    private fun resetAndStop() {
        WeChatData.updateIndex(0)
        WeChatData.updateValue("")
        lastWindowClassName = ""
        resetRetryAndNavigation()
        cancelNextStep()
    }

    private fun clearInputField(inputNode: AccessibilityNodeInfo): Boolean {
        return try {
            val text = inputNode.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                Log.d(TAG, "清空输入框: $text")
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                val result = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "清空输入框结果: $result")
                result
            } else {
                Log.d(TAG, "输入框为空，无需清空")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "清空输入框失败", e)
            false
        }
    }

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
        messageReader.init()
        // 启动通知栏拦截轮询（快速路径为无障碍事件，轮询兜底）
        mainHandler.removeCallbacks(shadePollRunnable)
        mainHandler.post(shadePollRunnable)
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
        mainHandler.removeCallbacks(shadePollRunnable)
        resetAndStop()
        isProcessing.set(false)
        cancelNextStep()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "无障碍服务已断开")
        mainHandler.removeCallbacks(shadePollRunnable)
        if (instance === this) instance = null
        messageReader.shutdown()
        resetAndStop()
        isProcessing.set(false)
        cancelNextStep()
        serviceScope.cancel()
        returnScope.cancel()
        return super.onUnbind(intent)
    }

    // ==================== isProcessing辅助函数 ====================

    private fun setProcessing(value: Boolean) {
        isProcessing.set(value)
    }

    // ==================== 主动触发下一步 ====================

    private fun getStepDelay(delayMs: Long): Long = maxOf(delayMs * 2, 1000L)

    private suspend fun waitStep(delayMs: Long) {
        delay(getStepDelay(delayMs))
    }

    private fun scheduleNextStep(delayMs: Long = 300) {
        cancelNextStep()
        nextStepRunnable = Runnable {
            // 主动触发保护：只有上次事件确认在微信内才执行下一步，
            // 避免在 MoreTalk 主界面/桌面残留的类名触发自动化（导致误返回/退出微信）。
            if (!isProcessing.get() && WeChatData.index > 0 && WeChatData.index <= 7 &&
                lastEventPackage == "com.tencent.mm"
            ) {
                Log.d(TAG, ">>> 主动触发步骤 ${WeChatData.index} <<<")
                val currentActivity = lastWindowClassName
                when (WeChatData.index) {
                    1 -> processStep1(currentActivity)
                    2 -> processStep2(currentActivity)
                    3 -> processStep3(currentActivity)
                    4 -> processStep4(currentActivity)
                    5 -> processStep5(currentActivity)
                    6 -> processStep6(currentActivity)
                    7 -> processStep7(currentActivity)
                }
            } else {
                Log.d(TAG, "主动触发被跳过（不在微信内或状态不符），index=${WeChatData.index} pkg=$lastEventPackage")
            }
        }
        mainHandler.postDelayed(nextStepRunnable!!, getStepDelay(delayMs))
    }

    private fun cancelNextStep() {
        nextStepRunnable?.let {
            mainHandler.removeCallbacks(it)
            nextStepRunnable = null
        }
    }

    private fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100, false)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipeGesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs, false)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        dispatchGesture(gesture, null, null)
    }
}
