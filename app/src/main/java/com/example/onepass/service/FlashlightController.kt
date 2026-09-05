package com.example.onepass.service

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.onepass.utils.Logger

/**
 * 手电筒控制：封装 CameraManager.setTorchMode，并跟踪真实开关状态。
 * 状态以系统回调为准，避免相机应用抢占后本地状态失真。
 */
object FlashlightController {

    var isTorchOn: Boolean = false
        private set

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var callbackRegistered = false
    private var stateListener: ((Boolean) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            mainHandler.post {
                if (cameraId == torchCameraId) {
                    isTorchOn = enabled
                    stateListener?.invoke(enabled)
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (callbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        cameraManager = cm
        try {
            torchCameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Logger.w("FlashlightController 读取相机列表失败: ${e.message}")
        }
        if (torchCameraId != null) {
            try {
                cm.registerTorchCallback(torchCallback, mainHandler)
                callbackRegistered = true
            } catch (e: Exception) {
                Logger.w("FlashlightController 注册 TorchCallback 失败: ${e.message}")
            }
        }
    }

    /** 是否有可用闪光灯（无闪光灯机型按钮应置灰） */
    fun hasFlash(context: Context): Boolean {
        initialize(context)
        return torchCameraId != null
    }

    /** 开关手电筒，返回是否执行成功（未授权/无闪光灯时失败） */
    fun setTorch(context: Context, on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        initialize(context)
        val id = torchCameraId ?: return false
        return try {
            cameraManager?.setTorchMode(id, on)
            isTorchOn = on
            stateListener?.invoke(on)
            true
        } catch (e: Exception) {
            Logger.w("FlashlightController setTorchMode 失败: ${e.message}")
            false
        }
    }

    fun toggle(context: Context): Boolean = setTorch(context, !isTorchOn)

    fun setStateListener(listener: ((Boolean) -> Unit)?) {
        stateListener = listener
    }
}
