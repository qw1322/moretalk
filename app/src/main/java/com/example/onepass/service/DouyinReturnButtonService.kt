package com.example.onepass.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.example.onepass.R
import com.example.onepass.utils.Logger
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

/**
 * 抖音安心刷「回首页」大按钮：
 * 只在抖音处于前台时显示（轮询无障碍服务维护的前台状态），离开抖音自动隐藏。
 * 点击后一键返回抖音视频首页（循环按返回，卡住自动重启抖音）。
 * 大小/透明度跟随「悬浮球大小/透明度」设置；依赖无障碍服务与悬浮窗权限。
 */
class DouyinReturnButtonService : Service() {

    companion object {
        private const val TAG = "DouyinReturnBtn"
        private const val POLL_INTERVAL_MS = 500L
        private const val BTN_SIZE_DP = 88
        private const val EDGE_MARGIN_DP = 12
        /** 离开抖音后的宽限期：短暂的非抖音窗口（弹窗/抽屉动画）不会让按钮闪烁消失 */
        private const val HIDE_GRACE_MS = 3000L
        private const val PREFS_NAME = "OnePassPrefs"

        /** 退出抖音后自动隐藏按钮（默认开；关 = 保留 3 秒宽限期防闪烁） */
        const val KEY_HIDE_ON_EXIT = "douyin_btn_hide_on_exit"
        /** 按钮大小百分比（相对默认 88dp，范围 50%..150%） */
        const val KEY_BTN_SIZE_PCT = "douyin_btn_size_pct"
        /** 按钮不透明度百分比（范围 20%..100%，100=不透明） */
        const val KEY_BTN_ALPHA_PCT = "douyin_btn_alpha_pct"
    }

    private var windowManager: WindowManager? = null
    private var btnView: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSeenDouyinAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // 事件标记为真（在抖音内）时无需实时查询；为假时用实时查询兜底，
            // 避免弹窗/抽屉窗口把按钮误隐藏。
            val flag = SelectToSpeakService.isDouyinForeground
            val douyinActive = if (flag) {
                true
            } else {
                SelectToSpeakService.getActiveWindowPackage() == SelectToSpeakService.PKG_DOUYIN
            }
            if (douyinActive) {
                lastSeenDouyinAt = now
                showButton()
            } else {
                // 设置开启（默认）：退出抖音立即隐藏；关闭：保留宽限期防闪烁
                val hideOnExit = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(KEY_HIDE_ON_EXIT, true)
                val grace = if (hideOnExit) 0L else HIDE_GRACE_MS
                if (now - lastSeenDouyinAt > grace) {
                    hideButton()
                }
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mainHandler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hideButton()
        super.onDestroy()
    }

    private fun showButton() {
        if (btnView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        // 应用独立的按钮大小/透明度设置（默认 100%）
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val sizeDp = BTN_SIZE_DP * prefs.getInt(KEY_BTN_SIZE_PCT, 100).coerceIn(50, 150) / 100
        val size = (sizeDp * density).toInt()
        val btnAlpha = prefs.getInt(KEY_BTN_ALPHA_PCT, 100).coerceIn(20, 100) / 100f

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            x = (EDGE_MARGIN_DP * density).toInt()
            // 屏幕中部偏上：避开抖音右侧点赞/评论按钮与底部导航
            y = -(resources.displayMetrics.heightPixels / 5)
        }

        val view = TextView(this).apply {
            text = "回首页"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f * (sizeDp / BTN_SIZE_DP.toFloat())
            setTypeface(Typeface.DEFAULT_BOLD)
            setBackgroundResource(R.drawable.bg_douyin_return)
            alpha = btnAlpha
            contentDescription = "返回抖音首页"
            setOnClickListener {
                SelectToSpeakService.returnToDouyinFeed()
            }
        }
        btnView = view
        try {
            wm.addView(view, params)
            Logger.d("$TAG 已显示")
        } catch (e: Exception) {
            Logger.w("$TAG 显示失败（可能缺少悬浮窗权限）: ${e.message}")
            btnView = null
        }
    }

    private fun hideButton() {
        btnView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // 视图可能已被系统移除
            }
            btnView = null
        }
    }
}
