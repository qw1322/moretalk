package com.example.onepass.presentation.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.onepass.service.FlashlightController

/**
 * 手电筒快捷入口：作为独立桌面图标存在，点击即开关手电筒并立即返回。
 * 透明主题，无界面跳转感；切换结果用 Toast 反馈。
 */
class TorchActivity : AppCompatActivity() {

    private companion object {
        const val REQ_CAMERA = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FlashlightController.hasFlash(this)) {
            Toast.makeText(this, "该手机不支持手电筒", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        toggleAndFinish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            toggleAndFinish()
        } else {
            Toast.makeText(this, "未获得相机权限，无法使用手电筒", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun toggleAndFinish() {
        val on = FlashlightController.toggle(this)
        Toast.makeText(this, if (on) "手电筒已开" else "手电筒已关", Toast.LENGTH_SHORT).show()
        finish()
    }
}
