package com.example.onepass.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.example.onepass.R
import com.example.onepass.utils.Logger

/**
 * 桌面悬浮球：全局悬浮按钮，固定在屏幕右边缘居中偏上位置，单击回到 MoreTalk 主界面。
 * 固定位置不可拖动，避免老人误拖丢失；依赖悬浮窗权限（SYSTEM_ALERT_WINDOW）。
 */
class FloatingHomeButtonService : Service() {

    companion object {
        private const val BALL_SIZE_DP = 76
        private const val EDGE_MARGIN_DP = 16
        private const val FADE_DELAY_MS = 20_000L
        /** 20 秒无点击后的淡化系数（相对当前不透明度） */
        private const val RESTED_FACTOR = 0.4f
        private const val PREFS_NAME = "OnePassPrefs"
        private const val DEFAULT_PCT = 100

        /** 悬浮球大小百分比（相对默认 76dp，范围 50%..150%） */
        const val KEY_FLOAT_BALL_SIZE_PCT = "float_ball_size_pct"
        /** 悬浮球不透明度百分比（范围 20%..100%，100=不透明） */
        const val KEY_FLOAT_BALL_ALPHA_PCT = "float_ball_alpha_pct"

        /** 从设置读取悬浮球大小百分比 */
        fun getSizePct(context: Context): Int = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FLOAT_BALL_SIZE_PCT, DEFAULT_PCT)
            .coerceIn(50, 150)

        /** 从设置读取悬浮球不透明度（0f..1f） */
        fun getAlpha(context: Context): Float = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FLOAT_BALL_ALPHA_PCT, DEFAULT_PCT)
            .coerceIn(20, 100) / 100f
    }

    private var windowManager: WindowManager? = null
    private var floatView: ImageView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var baseAlpha = 1f

    // 20 秒无点击后淡化为半透明，点击恢复不透明并重新计时
    private val fadeRunnable = Runnable {
        floatView?.animate()?.alpha(RESTED_FACTOR * baseAlpha)?.setDuration(400)?.start()
    }

    private fun scheduleFade() {
        mainHandler.removeCallbacks(fadeRunnable)
        mainHandler.postDelayed(fadeRunnable, FADE_DELAY_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        addFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeFloatingView()
        super.onDestroy()
    }

    private fun addFloatingView() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        // 应用设置的大小百分比（默认 100% = 76dp）与不透明度
        val ballSizeDp = BALL_SIZE_DP * getSizePct(this) / 100
        val ballSize = (ballSizeDp * density).toInt()
        baseAlpha = getAlpha(this)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = (EDGE_MARGIN_DP * density).toInt()
            // 居中偏上，避免遮挡底部导航/常用操作区
            y = -(resources.displayMetrics.heightPixels / 5)
        }

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_home)
            setBackgroundResource(R.drawable.bg_float_ball)
            contentDescription = "返回桌面"
            alpha = baseAlpha
            setOnClickListener {
                // 点击：立即恢复不透明，重新计时
                floatView?.alpha = baseAlpha
                scheduleFade()
                goHome()
            }
        }
        floatView = view
        try {
            wm.addView(view, params)
            scheduleFade()
        } catch (e: Exception) {
            Logger.w("悬浮球添加失败（可能缺少悬浮窗权限）: ${e.message}")
            floatView = null
            stopSelf()
        }
    }

    private fun removeFloatingView() {
        floatView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // 视图可能已被系统移除
            }
            floatView = null
        }
    }

    private fun goHome() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
        } catch (e: Exception) {
            Logger.w("悬浮球返回桌面失败: ${e.message}")
        }
    }
}
