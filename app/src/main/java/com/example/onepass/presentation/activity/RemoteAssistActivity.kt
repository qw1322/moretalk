package com.example.onepass.presentation.activity

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.onepass.service.RemoteAssistService
import com.example.onepass.utils.Logger

/**
 * 远程协助入口：请求录屏授权 → 启动 RemoteAssistService → 立即关闭。
 *
 * 注意：部分 ROM 上本应用窗口位于前台时私有虚拟显示镜像失效（content=false），
 * 因此启动服务后立即 finish，让应用转后台后再开始采集。
 */
class RemoteAssistActivity : AppCompatActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Logger.d("RemoteAssist", "授权回调 resultCode=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            try {
                RemoteAssistService.start(this, result.resultCode, result.data!!)
                Logger.d("RemoteAssist", "服务启动请求已发出")
            } catch (e: Exception) {
                Logger.e("RemoteAssist", "启动服务失败: ${e.message}", e)
                Toast.makeText(this, "启动远程协助失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "未授权录屏，远程协助未启动", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
