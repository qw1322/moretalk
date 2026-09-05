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
 * 注意：本 ROM 上任何应用自身窗口（含透明窗口）位于前台时，私有虚拟显示镜像失效
 * （content=false → 采集停摆），因此启动服务后必须立即 finish，让应用转后台采集。
 * 服务保活依赖 realme 后台白名单（设置→电池→后台运行管理→允许 MoreTalk）。
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
                Toast.makeText(
                    this,
                    "远程协助已启动，房间 ${RemoteAssistService.tunnelRoom}",
                    Toast.LENGTH_LONG
                ).show()
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
        // 防堆积：服务已在运行时，直接告知并关闭，不重复请求录屏授权会话
        if (RemoteAssistService.isRunning) {
            Toast.makeText(
                this,
                "远程协助已在运行（房间 ${RemoteAssistService.tunnelRoom}），未重复启动",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
