package com.example.onepass.tv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.onepass.R
import com.example.onepass.tv.cast.Dlna
import com.example.onepass.tv.cast.DlnaDevice
import com.example.onepass.tv.data.BiliClient
import com.example.onepass.tv.data.BuiltinChannelSeed
import com.example.onepass.tv.data.TvPlaybackStats
import com.example.onepass.tv.data.TvRepository
import com.example.onepass.tv.model.TvCategory
import com.example.onepass.tv.model.TvChannel
import com.example.onepass.tv.player.Media3TvPlaybackEngine
import com.example.onepass.tv.player.SystemPlayerFallback
import com.example.onepass.tv.player.TvPlaybackEngine
import com.example.onepass.tv.player.TvPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 看电视 · 播放页（内置播放器）
 *
 * 这一页就是「为什么必须用内置播放器」的答案：
 * 播放器自带控制条被关掉了，画面上浮的全是为老人重做的控件 ——
 * 三个 104dp 的大按钮、语音报台名、失败自动换源换台、看门狗兜住「转圈不出画面」。
 * 换系统播放器的话，这些一个都做不了。
 *
 * **回家只有一条路**：桌面悬浮球（FloatingHomeButtonService）。这一页刻意不放「回家」按钮，
 * 两个入口容易让老人困惑，也容易行为不一致。
 *
 * 容错设计（三层，对应老人实际会遇到的三类问题）：
 *   ① 单源失效 → 同频道的备用地址重试（[TvChannel.urls] 第二条起）
 *   ② 整个频道失效 → 自动跳到下一个能用的台，最多连跳 [MAX_AUTO_SWITCH] 次
 *   ③ 全都失效 → 大字「这个台看不了」+ 换一个台（另有悬浮球随时能回家），语音提示叫孩子
 *
 * 另有看门狗：直播源有时不报错但也永远不出画面，[WATCHDOG_MS] 后仍无首帧就按失败处理。
 *
 * 「上一个 / 下一个」只在**当前分类**里走（[EXTRA_CATEGORY]），
 * 这样老人从「古装节目」进来的，翻台就一直翻古装剧，不会翻到新闻台去。
 *
 * 底部四个按钮：上一个 / 换台 / 下一个 / 投屏。
 *   - 「换台」是**唯一**还会打开频道列表的入口（点分类直接起播后，列表退居二线）；
 *   - 「投屏」把当前地址推给客厅电视（DLNA），见 [showCastDialog]。
 *
 * 【退出即关闭】页面一旦离开前台（回桌面 / 锁屏 / 跳到频道页或系统播放器），
 * 就在 [onStop] 里把流 `stop()` 断掉，不在后台继续拉流 —— 直击用户反馈的「流量消耗快」。
 * 回到前台由 [onResume] 重新起播（直播会直接接最新位置）。
 *
 * 入口链路（用户要求「去掉选择频道的环节，点击节目直接播放」）：
 *     桌面「看电视」瓦片 → [TvCategoryActivity]（四类节目）
 *          → **直接起播**本类第一个/上次看的台（不再经过频道列表）
 */
class TvPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvPlayer"

        /** 指定起播频道（不传则用「上次看的台」） */
        const val EXTRA_CHANNEL_ID = "extra_channel_id"

        /** 当前分类（限定「上一个 / 下一个」的范围；不传默认央视频道） */
        const val EXTRA_CATEGORY = "extra_category"

        private const val PREFS_NAME = "OnePassPrefs"
        private const val KEY_LAST_CHANNEL = "tv_last_channel_id"
        private const val KEY_TV_VOLUME = "tv_volume"

        /** 超过这个时间还没出画面，就当这个源废了 */
        private const val WATCHDOG_MS = 12_000L
        /** 卡死巡检周期 */
        private const val STALL_TICK_MS = 2_000L
        /**
         * 出过画面之后，卡在缓冲超过这个时间就尝试「跳回直播最新位置」。
         * 比初始看门狗短，因为这时已经是「看着看着卡住了」，越早救越好。
         */
        private const val STALL_RECOVER_MS = 8_000L
        /** 单个源最多自救几次，超过就认命换源/换台（避免在一个坏源上反复跳） */
        private const val MAX_STALL_RECOVER = 2
        /** 控件自动隐藏延时 */
        private const val CONTROLS_HIDE_MS = 4_000L
        /** 连续自动跳台的上限，防止把所有台轮一遍 */
        private const val MAX_AUTO_SWITCH = 3
        /** 投屏设备搜索时长（电视一般 1~2 秒就应答，给够容错） */
        private const val CAST_SEARCH_MS = 4_000L
    }

    private lateinit var engine: TvPlaybackEngine
    private lateinit var speaker: TvSpeaker

    private lateinit var root: View
    private lateinit var statusPanel: View
    private lateinit var statusIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var failureActions: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var channelNameText: TextView
    private lateinit var channelGroupText: TextView

    /** 当前分类：决定「上一个 / 下一个」和「换台」页里能看到哪些台 */
    private var category: TvCategory = TvCategory.CCTV

    /** 当前分类下的频道（已按分类过滤，翻台只在这个范围里走） */
    private var channels: List<TvChannel> = emptyList()
    private var currentIndex = -1

    /** 当前频道内正在用的第几条源 */
    private var urlIndex = 0
    private var autoSwitchCount = 0

    /** 本轮失败已经跳过的频道，避免在几个坏台之间来回弹 */
    private val triedChannelIds = mutableSetOf<String>()

    private var controlsVisible = true
    private var released = false

    /** 播放页是否在前台。后台时不跑卡死巡检，免得误触发 */
    private var inForeground = false

    /**
     * 是否因为「退出页面」把流彻底停掉了（见 [onStop]），回到前台（[onResume]）需要重新起播。
     *
     * 为什么要单独记一个标志：暂停（[Media3TvPlaybackEngine.pause]）和停流
     * （[Media3TvPlaybackEngine.stop]）回来的处理不一样 —— 前者只要 resume() 就能续上，
     * 后者必须重新 prepare()，否则会一直黑屏。
     */
    private var stoppedForBackground = false

    /** 本轮「开始卡在缓冲」的时刻（0 表示当前没在缓冲） */
    private var bufferingSince = 0L
    /** 当前源已经自救过几次 */
    private var stallRecoverCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = MainScope()

    private val hideControlsTask = Runnable { setControlsVisible(false) }

    /** 看门狗：卡在缓冲、不报错也不出画面时兜底 */
    private val watchdogTask = Runnable {
        if (released || isFinishing) return@Runnable
        if (!engine.hasRenderedVideo()) {
            Log.w(TAG, "看门狗触发：${currentChannel()?.name} 超时无画面")
            handleFailure("watchdog-timeout")
        }
    }

    /**
     * 卡死巡检（针对「已经出过画面、看着看着卡住」）。
     *
     * 为什么单独做一套：初始看门狗只管首帧，首帧一出就取消了。
     * 但老人最常见的吐槽恰恰是「刚看着好好的，突然就卡住不动了」——
     * 这类卡死往往既不报错也不结束，播放器安安静静转圈。
     * 这里每 [STALL_TICK_MS] 检查一次，连续卡满 [STALL_RECOVER_MS] 就：
     *   前几次 → 跳回直播最新位置（多数情况一两秒自愈，老人几乎无感）；
     *   超过 [MAX_STALL_RECOVER] 次 → 交给换源/换台流程。
     */
    private val stallWatchdogTask = object : Runnable {
        override fun run() {
            if (released || isFinishing) return
            handler.postDelayed(this, STALL_TICK_MS)

            // 首帧都没出 → 不归它管，交给初始看门狗（那才是「换源换台」的时机）
            if (!inForeground || bufferingSince == 0L || !engine.hasRenderedVideo()) return

            val stuckMs = SystemClock.uptimeMillis() - bufferingSince
            if (stuckMs < STALL_RECOVER_MS) return

            if (stallRecoverCount < MAX_STALL_RECOVER) {
                stallRecoverCount++
                bufferingSince = SystemClock.uptimeMillis()
                Log.w(TAG, "卡在缓冲 ${stuckMs}ms，跳回直播位置（第 $stallRecoverCount 次）")
                engine.seekToLiveEdge()
            } else {
                Log.w(TAG, "反复卡死，按失败处理")
                bufferingSince = 0L
                handleFailure("stall-timeout")
            }
        }
    }

    /**
     * 从频道页选台返回。
     *
     * 频道页会连**分类**一起回传：正常情况就是同一分类（列表本来就是按分类过滤的），
     * 但万一以后加了「跨分类跳转」，这里切一下分类、重算列表，逻辑也不会错。
     */
    private val pickChannel = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val id = data.getStringExtra(TvChannelsActivity.RESULT_CHANNEL_ID)
            ?: return@registerForActivityResult

        val returnedCategory = TvCategory.fromId(
            data.getStringExtra(TvChannelsActivity.RESULT_CATEGORY) ?: category.id
        )
        if (returnedCategory != category) {
            category = returnedCategory
            channels = TvRepository.byCategory(this, TvRepository.cached(this), category)
        }

        val idx = channels.indexOfFirst { it.id == id }
        if (idx >= 0 && idx != currentIndex) {
            currentIndex = idx
            triedChannelIds.clear()
            autoSwitchCount = 0
            playCurrent(announce = true)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveFullscreen()
        setContentView(R.layout.activity_tv_player)
        bindViews()

        engine = Media3TvPlaybackEngine(this)
        engine.attach(findViewById(R.id.tvPlayerContainer))
        engine.setStateListener { onPlaybackState(it) }
        engine.setVolume(readSavedVolume())

        speaker = TvSpeaker(this)

        category = TvCategory.fromId(intent.getStringExtra(EXTRA_CATEGORY))

        // 只取当前分类的台：缓存里是完整清单（几百个台），不过滤的话
        // 「上一个/下一个」会翻到一堆地方台和垃圾条目。分类判定见 TvClassifier。
        channels = TvRepository.byCategory(this, TvRepository.cached(this), category)
        currentIndex = resolveStartIndex()
        setupButtons()

        if (currentIndex < 0) {
            // 这个分类下一个能播的台都没有（极罕见：远程全挂且种子也被过滤光）
            showFailure()
            return
        }

        playCurrent(announce = true)

        // 卡死巡检常驻（自身每 2 秒续一次）
        handler.postDelayed(stallWatchdogTask, STALL_TICK_MS)

        // 后台拉最新清单，不打断当前播放；回来后按 id 对齐当前频道
        uiScope.launch {
            val fresh = runCatching { TvRepository.load(this@TvPlayerActivity) }
                .onFailure { Log.w(TAG, "刷新频道失败: ${it.javaClass.simpleName}") }
                .getOrNull() ?: return@launch
            applyRefreshedChannels(fresh)
        }
    }

    /**
     * 真正的沉浸式全屏。
     *
     * 为什么必须显式做（而不是靠主题里的 windowFullscreen）：
     * targetSdk 35+ 起系统强制「边到边」，主题那个老属性已经不可靠了。
     * 真机上表现为**画面底部压着一条浅色的系统导航栏**（用户说的「底部有白边」），
     * 同时可见区域被挤小、画面看着「有点靠下」。
     * 这里把状态栏和导航栏都藏掉，并允许从边缘上滑临时唤出，画面才是干净的满屏。
     */
    @Suppress("DEPRECATION") // statusBarColor / navigationBarColor 在 API35+ 已废弃，但这里是为老系统兜底
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // 上滑/点按临时唤出系统栏，随后自动隐藏 —— 不给老人留常驻的白条
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        bufferingSince = 0L
        // 从设置页等场景回来时系统栏可能又被显示出来，再收一次
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        if (currentIndex < 0) return
        if (stoppedForBackground) {
            // 【退出即关闭】回来时重新起播（见 onStop 的说明）
            stoppedForBackground = false
            playCurrent(announce = false)
        } else {
            engine.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        engine.pause()
    }

    /**
     * 【退出即关闭】防止后台偷跑流量。
     *
     * 为什么不能只靠 [onPause] 里的 pause()：
     *   `pause()` 只是把 playWhenReady 置 false，播放器**仍握着连接、可能继续把缓冲拉满**；
     *   个别直播源甚至在暂停后还会零星续拉切片。老人把页面退回桌面/锁屏后，
     *   流量就这么在后台一点点被吃掉（用户反馈的「流量消耗快」）。
     *
     * 所以这里在页面**离开前台**（[onStop]：回桌面、锁屏、跳到频道页/系统播放器）时，
     * 直接把流 `stop()` 掉 —— 连网络加载一起停掉，后台零拉流。
     * 代价是回来时要重新缓冲 1~2 秒，由 [onResume] 负责重新起播。
     */
    override fun onStop() {
        super.onStop()
        inForeground = false
        cancelWatchdog()
        if (currentIndex >= 0 && !released) {
            stoppedForBackground = true
            engine.stop()
        }
    }

    override fun onDestroy() {
        released = true
        handler.removeCallbacksAndMessages(null)
        uiScope.cancel()
        engine.release()
        speaker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 初始化

    private fun bindViews() {
        root = findViewById(R.id.tvRoot)
        statusPanel = findViewById(R.id.tvStatusPanel)
        statusIcon = findViewById(R.id.tvStatusIcon)
        statusText = findViewById(R.id.tvStatusText)
        failureActions = findViewById(R.id.tvFailureActions)
        topBar = findViewById(R.id.tvTopBar)
        bottomBar = findViewById(R.id.tvBottomBar)
        channelNameText = findViewById(R.id.tvChannelName)
        channelGroupText = findViewById(R.id.tvChannelGroup)
    }

    private fun setupButtons() {
        // 点画面空白处 = 唤出/收起控件（播放器自带控制条已关闭，这是唯一的唤出方式）
        root.setOnClickListener { setControlsVisible(!controlsVisible) }

        findViewById<View>(R.id.tvBtnPrev).setOnClickListener {
            setControlsVisible(true)
            switchChannel(-1)
        }
        findViewById<View>(R.id.tvBtnNext).setOnClickListener {
            setControlsVisible(true)
            switchChannel(+1)
        }
        findViewById<View>(R.id.tvBtnList).setOnClickListener {
            setControlsVisible(true)
            val intent = Intent(this, TvChannelsActivity::class.java)
                .putExtra(TvChannelsActivity.EXTRA_CURRENT_ID, currentChannel()?.id)
                .putExtra(TvChannelsActivity.EXTRA_CATEGORY, category.id)
            pickChannel.launch(intent)
        }
        findViewById<View>(R.id.tvBtnCast).setOnClickListener {
            setControlsVisible(true)
            showCastDialog()
        }
        // 注意：这里**没有**「回家」按钮 —— 回家统一走桌面悬浮球，
        // 少一个按钮老人更不容易看花眼。详见类注释。

        findViewById<View>(R.id.tvFailureNext).setOnClickListener {
            failureActions.visibility = View.GONE
            autoSwitchCount = 0
            triedChannelIds.clear()
            switchChannel(+1)
        }
        // 失败兜底页保留一个出口：这时可能连悬浮球都被挡（全屏异常），
        // 留一个「回家」比让老人困在黑屏上强。它只在这张兜底页出现，不是常驻按钮。
        findViewById<View>(R.id.tvFailureHome).setOnClickListener { finish() }

        // 顶部栏：点击同样能唤出/收起控件
        topBar.setOnClickListener { setControlsVisible(!controlsVisible) }
        // 家属专属：长按顶部台名 → 用系统播放器兜底打开（老人不会长按，不构成干扰）
        topBar.setOnLongClickListener {
            showSystemPlayerFallback()
            true
        }
    }

    /**
     * 系统播放器兜底入口（家属用）。
     *
     * 只在「内置播放器实在播不了」时使用：极少数厂商私有编码的流，
     * 系统播放器带厂商解码器能放。跳出去就失去界面控制权，所以不做成老人可见的按钮。
     */
    private fun showSystemPlayerFallback() {
        val ch = currentChannel() ?: return
        val url = ch.urls.firstOrNull() ?: return
        if (!SystemPlayerFallback.canOpen(this, url)) {
            Toast.makeText(this, R.string.tv_system_player_none, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_system_player_title)
            .setMessage(getString(R.string.tv_system_player_message, ch.name))
            .setPositiveButton(R.string.tv_system_player_open) { _, _ ->
                engine.pause()
                SystemPlayerFallback.open(this, url)
            }
            .setNegativeButton(R.string.tv_failure_home_back, null)
            .show()
    }

    /**
     * 投屏：把当前正在看的这个直播地址推给客厅电视（DLNA）。
     *
     * 交互刻意做得「一步到位」——点「投屏」→ 自动搜索 → 列表里点电视名就好了，
     * 没有配对、没有二维码、没有密码（老人记不住也填不动）。
     *
     * 分两步弹窗（踩过坑，别合并）：
     *   ① 「正在找电视…」进度框（只有 message，不给列表）；
     *   ② 搜到之后关掉进度框，用 **setItems** 弹一个纯列表的设备选择框。
     *
     * 为什么不做一个「边搜边往里加」的实时列表：AppCompat 的 AlertDialog 里
     * `setMessage` 和 `setAdapter` 同时用时，**message 会把列表挤掉**（真机实测：
     * 只显示提示文字，设备一条都看不到）；而且列表在 show() 时是空的，
     * 之后 notifyDataSetChanged() 也不会重新测量高度，新加的设备会被裁掉。
     * 所以改成「先搜完、再一次性用 setItems 弹」，两个坑一起绕开。
     *
     * 成功后**把手机这一端暂停**：否则手机和电视会同时出声，反而更乱。
     */
    private fun showCastDialog() {
        val ch = currentChannel() ?: return
        // 推当前正在用的那条源；没有就退而求其次用第一条
        val url = ch.urls.getOrNull(urlIndex)?.takeIf { it.isNotBlank() }
            ?: ch.urls.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return

        setControlsVisible(true)

        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.tv_cast_title)
            .setMessage(
                getString(R.string.tv_cast_searching) + "\n" + getString(R.string.tv_cast_hint)
            )
            .setNegativeButton(R.string.tv_cast_close, null)
            .create()
        progress.show()

        val found = mutableListOf<DlnaDevice>()
        Dlna.discover(
            context = this,
            timeoutMs = CAST_SEARCH_MS,
            onDevice = { device -> runOnUiThread { found.add(device) } },
            onFinished = {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    // 用户中途点了「取消」就别再弹结果框了
                    if (!progress.isShowing) return@runOnUiThread
                    progress.dismiss()

                    val list = found.distinctBy { it.controlUrl }
                    if (list.isEmpty()) {
                        Toast.makeText(
                            this@TvPlayerActivity,
                            R.string.tv_cast_none,
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    AlertDialog.Builder(this@TvPlayerActivity)
                        .setTitle(R.string.tv_cast_title)
                        .setItems(list.map { it.friendlyName }.toTypedArray()) { _, which ->
                            list.getOrNull(which)?.let { castTo(it, ch, url) }
                        }
                        .setNegativeButton(R.string.tv_cast_close, null)
                        .show()
                }
            }
        )
    }

    /** 真正下发投屏：SetAVTransportURI + Play 都在子线程，别卡住 UI */
    private fun castTo(device: DlnaDevice, channel: TvChannel, url: String) {
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Dlna.cast(device, url, channel.name) }.getOrDefault(false)
            }
            if (released) return@launch
            if (ok) {
                // 投屏成功就把手机这端停了，避免两个喇叭同时响
                engine.pause()
                Toast.makeText(
                    this@TvPlayerActivity,
                    getString(R.string.tv_cast_ok, device.friendlyName),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@TvPlayerActivity,
                    R.string.tv_cast_fail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 起播哪个台，优先级：intent 指定 > 上次看的台 > 内置默认（CCTV-8）> 本分类第一个。
     *
     * 注意 [channels] 已经是**当前分类**的子集，所以「上次看的台」如果不在这个分类里，
     * 会自动落到本分类的第一个台 —— 比如刚从「戏曲节目」进来、上次看的是 CCTV-8，
     * 就会直接播第一个戏曲台，而不是莫名其妙跳出分类。
     */
    private fun resolveStartIndex(): Int {
        val remembered = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_CHANNEL, null)
        val target = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: remembered
        if (!target.isNullOrBlank()) {
            val idx = channels.indexOfFirst { it.id == target }
            if (idx >= 0) return idx
        }
        val fallback = channels.indexOfFirst { it.id == BuiltinChannelSeed.DEFAULT_CHANNEL_ID }
        return if (fallback >= 0) fallback else channels.indexOfFirst { it.playable }
    }

    // ------------------------------------------------------------------ 播放控制

    private fun currentChannel(): TvChannel? = channels.getOrNull(currentIndex)

    private fun playCurrent(announce: Boolean) {
        val ch = currentChannel() ?: return
        urlIndex = 0
        // 这是一次全新的起播，清掉「因退出而停流」的标志，免得 onResume 又重播一遍
        stoppedForBackground = false
        updateChannelTitle(ch)
        showStatus(getString(R.string.tv_status_loading))
        if (announce) speaker.speak(getString(R.string.tv_speak_opening, ch.name))
        saveLastChannel(ch.id)
        playUrlAt(0)
    }

    private fun playUrlAt(index: Int) {
        val ch = currentChannel() ?: return
        if (index >= ch.urls.size) {
            handleFailure("no-more-source")
            return
        }
        urlIndex = index
        bufferingSince = 0L
        stallRecoverCount = 0
        startWatchdog()
        val raw = ch.urls[index]
        if (raw.startsWith(BiliClient.SCHEME)) {
            // B站点播条目：列表里存的是 `bili://<bvid>/<cid>`，真实地址是**短时效签名 URL**，
            // 只能在播放前才换。换不到就当这条源失败，走「换下一条源」的既有容错逻辑。
            uiScope.launch {
                val real = BiliClient.resolvePlayUrl(this@TvPlayerActivity, raw)
                if (released || urlIndex != index) return@launch
                if (real.isNullOrBlank()) {
                    Log.w(TAG, "B站直链解析失败，换下一条源: $raw")
                    playUrlAt(index + 1)
                } else {
                    engine.play(real)
                }
            }
        } else {
            engine.play(raw)
        }
    }

    /** 上一个 / 下一个台（跳过没有地址的条目） */
    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        triedChannelIds.clear()
        autoSwitchCount = 0
        val size = channels.size
        for (step in 1..size) {
            val i = (((currentIndex + delta * step) % size) + size) % size
            if (channels[i].playable) {
                currentIndex = i
                break
            }
        }
        playCurrent(announce = true)
    }

    private fun onPlaybackState(state: TvPlaybackState) {
        if (released) return
        when (state) {
            TvPlaybackState.Buffering -> {
                // 记录「开始卡」的时刻；若画面已经出过，就只默默等它恢复，不弹大面板打断老人
                if (bufferingSince == 0L) bufferingSince = SystemClock.uptimeMillis()
                if (!engine.hasRenderedVideo()) showStatus(getString(R.string.tv_status_loading))
            }
            TvPlaybackState.Playing -> {
                cancelWatchdog()
                bufferingSince = 0L
                stallRecoverCount = 0
                autoSwitchCount = 0
                triedChannelIds.clear()
                hideStatus()
                // 播起来了 → 清掉这个台的失败计数（源恢复了要立刻让它回到列表）
                currentChannel()?.let { TvPlaybackStats.recordSuccess(this, it.id) }
            }
            TvPlaybackState.Ended -> switchChannel(+1)
            TvPlaybackState.Idle -> Unit
            is TvPlaybackState.NoAudio -> {
                // 画面能出但音轨是 MP2/MP1（安卓没有解码器）→ 当成「这条源不能用」，
                // 走和源失效完全相同的换源/换台链路，老人不用知道背后发生了什么。
                Log.w(TAG, "源音频不支持(${state.mime})，换源：${currentChannel()?.name}")
                handleFailure("no-audio:${state.mime}")
            }
            is TvPlaybackState.Failed -> {
                Log.w(TAG, "播放失败(${currentChannel()?.name}): ${state.reason}")
                handleFailure(state.reason)
            }
        }
    }

    /**
     * 失败处理：先换源 → 再换台 → 最后才认输。
     * 顺序很重要：绝大多数「看不了」其实只是单个地址失效，换条源就好了。
     */
    private fun handleFailure(reason: String) {
        cancelWatchdog()
        val ch = currentChannel() ?: return

        // ① 同频道还有备用源
        if (urlIndex + 1 < ch.urls.size) {
            Log.d(TAG, "${ch.name} 换第 ${urlIndex + 2} 条源重试（$reason）")
            playUrlAt(urlIndex + 1)
            return
        }

        // ② 自动跳到下一个没试过的台
        if (autoSwitchCount < MAX_AUTO_SWITCH) {
            autoSwitchCount++
            triedChannelIds.add(ch.id)
            // 这个台连同它的所有备用源都不行（含「音频安卓解不了」）→ 记一笔。
            // 连续几次后 [TvPlaybackStats] 会让它从分类里消失，老人不必反复踩同一个坑。
            TvPlaybackStats.recordFail(this, ch.id)
            showStatus(getString(R.string.tv_status_trying))
            speaker.speak(getString(R.string.tv_speak_failed))
            uiScope.launch {
                delay(1500) // 停顿一下，别让老人觉得画面在乱闪
                if (!released) moveToNextUsableChannel()
            }
            return
        }

        // ③ 认输：大字提示 + 两个出口
        showFailure()
    }

    private fun moveToNextUsableChannel() {
        if (channels.isEmpty()) return
        val size = channels.size
        for (step in 1..size) {
            val i = (currentIndex + step) % size
            val candidate = channels[i]
            if (candidate.playable && candidate.id !in triedChannelIds) {
                currentIndex = i
                playCurrent(announce = false)
                return
            }
        }
        showFailure()
    }

    // ------------------------------------------------------------------ 界面状态

    private fun updateChannelTitle(ch: TvChannel) {
        channelNameText.text = ch.name
        channelGroupText.text = ch.group
    }

    private fun showStatus(text: String) {
        statusIcon.visibility = View.VISIBLE
        statusText.text = text
        statusPanel.visibility = View.VISIBLE
        setControlsVisible(true)
    }

    private fun hideStatus() {
        statusPanel.visibility = View.GONE
        failureActions.visibility = View.GONE
    }

    private fun showFailure() {
        cancelWatchdog()
        statusIcon.visibility = View.VISIBLE
        statusText.setText(R.string.tv_status_failed)
        statusPanel.visibility = View.VISIBLE
        failureActions.visibility = View.VISIBLE
        setControlsVisible(true)
        handler.removeCallbacks(hideControlsTask) // 认输界面不自动隐藏
        speaker.speak(getString(R.string.tv_speak_all_failed))
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        topBar.animate().cancel()
        bottomBar.animate().cancel()
        handler.removeCallbacks(hideControlsTask)

        if (visible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            topBar.animate().alpha(1f).setDuration(180).start()
            bottomBar.animate().alpha(1f).setDuration(180).start()
            handler.postDelayed(hideControlsTask, CONTROLS_HIDE_MS)
        } else {
            topBar.animate().alpha(0f).setDuration(180)
                .withEndAction { topBar.visibility = View.GONE }.start()
            bottomBar.animate().alpha(0f).setDuration(180)
                .withEndAction { bottomBar.visibility = View.GONE }.start()
        }
    }

    // ------------------------------------------------------------------ 看门狗

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogTask)
        handler.postDelayed(watchdogTask, WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        handler.removeCallbacks(watchdogTask)
    }

    // ------------------------------------------------------------------ 数据

    /**
     * 后台刷新回来的清单：只更新列表，**不打断正在播的台**。
     * 靠 id 重新定位当前频道，避免索引错位。
     */
    private fun applyRefreshedChannels(fresh: List<TvChannel>) {
        val playingId = currentChannel()?.id
        channels = TvRepository.byCategory(this, fresh, category)
        if (playingId != null) {
            val idx = channels.indexOfFirst { it.id == playingId }
            if (idx >= 0) currentIndex = idx
        }
    }

    private fun saveLastChannel(id: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_LAST_CHANNEL, id).apply()
    }

    /** 播放音量（0~1）。框架预留：后续加音量按钮时直接改这个值并保存 */
    private fun readSavedVolume(): Float =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_TV_VOLUME, 100)
            .coerceIn(0, 100) / 100f
}
