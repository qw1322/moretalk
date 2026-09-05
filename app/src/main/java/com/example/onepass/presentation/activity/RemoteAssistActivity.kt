package com.example.onepass.presentation.activity

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.onepass.service.RemoteAssistService
import com.example.onepass.utils.Logger

/**
 * 远程协助控制页：请求录屏授权 → 启动 RemoteAssistService → 保持前台展示
 * 连接信息与停止按钮（保持前台可避免部分 ROM 立即清理前台服务）。
 */
class RemoteAssistActivity : AppCompatActivity() {

    private lateinit var textInfo: TextView
    private lateinit var textStatus: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.d("RemoteAssist", "授权回调 resultCode=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                RemoteAssistService.start(this, result.resultCode, result.data!!)
                Logger.d("RemoteAssist", "服务启动请求已发出")
                textStatus.text = "正在启动…"
            } catch (e: Exception) {
                Logger.e("RemoteAssist", "启动服务失败: ${e.message}", e)
                Toast.makeText(this, "启动远程协助失败：${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            Toast.makeText(this, "未授权录屏，远程协助未启动", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 控制页前台时保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildContentView()

        // 已运行中则直接展示状态，否则请求录屏授权
        if (RemoteAssistService.isRunning) {
            textStatus.text = "运行中"
        } else {
            textStatus.text = "请求录屏授权…"
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "远程协助"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val ip = RemoteAssistService.getLocalIpAddress() ?: "获取IP失败"
        textInfo = TextView(this).apply {
            text = "请让家属在浏览器打开：\nhttp://$ip:${RemoteAssistService.PORT}"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 8)
        }
        root.addView(textInfo)

        textStatus = TextView(this).apply {
            text = "请求录屏授权…"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(textStatus)

        val btnStop = Button(this).apply {
            text = "停止远程协助"
            textSize = 22f
            setOnClickListener {
                RemoteAssistService.stop(this@RemoteAssistActivity)
                finish()
            }
        }
        root.addView(btnStop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 64 })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (RemoteAssistService.isRunning) {
            textStatus.text = "运行中（点击画面即可远程操作）"
        }
    }
}
