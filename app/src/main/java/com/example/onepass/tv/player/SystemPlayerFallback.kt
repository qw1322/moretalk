package com.example.onepass.tv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 系统播放器兜底。
 *
 * 内置播放器连续换源都失败时，给家属一个「用手系统播放器试试」的出口 ——
 * 某些国产 ROM 的系统播放器带厂商解码器，能播内置播放器播不了的流。
 *
 * ⚠️ 它不该是主路径：一旦跳出去，App 就失去了界面控制权，
 * 老人的「回家球」虽然还在（悬浮窗层级高于普通 Activity），但体验是割裂的。
 * 所以这里只作为最后的备用手段，由家属操作，不占老人主流程。
 */
object SystemPlayerFallback {

    private const val TAG = "SystemPlayerFallback"

    /** 是否有能处理该地址的应用（用来决定要不要显示兜底按钮） */
    fun canOpen(context: Context, url: String): Boolean = runCatching {
        val intent = buildIntent(url)
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /** @return 是否成功唤起 */
    fun open(context: Context, url: String): Boolean = runCatching {
        context.startActivity(buildIntent(url))
        true
    }.getOrElse {
        Log.w(TAG, "唤起系统播放器失败: ${it.javaClass.simpleName}")
        false
    }

    private fun buildIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
