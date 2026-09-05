package com.example.onepass.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
    }

    private var windowManager: WindowManager? = null
    private var floatView: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        addFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        removeFloatingView()
        super.onDestroy()
    }

    private fun addFloatingView() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        val ballSize = (BALL_SIZE_DP * density).toInt()

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
            setOnClickListener { goHome() }
        }
        floatView = view
        try {
            wm.addView(view, params)
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
