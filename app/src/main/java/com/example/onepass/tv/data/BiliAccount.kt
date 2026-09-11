package com.example.onepass.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * B站账号与画质偏好（本地存储，**绝不入库/上报**）。
 *
 * 为什么要有这一层：
 *   · 未登录时 B站只发 **320×240**（`accept_quality` 只有 6），老人看不清字幕；
 *     登录后能拿 480P/720P。
 *   · `SESSDATA` 等同账号凭证，必须只存本机 —— 所以单独放一个 prefs 文件，
 *     与频道缓存分开，方便「退出登录」时精确清除。
 *
 * 有效期认知：B站 `SESSDATA` 是**滑动过期**（默认 30 天，有请求就往后推）。
 *   本 App 每天都会刷一次戏曲列表，所以正常使用不会过期；
 *   但用户改密码 / 主动退出会立即失效 —— 因此设置页要能一眼看到「登录状态」。
 */
object BiliAccount {

    private const val PREFS = "tv_bili_prefs"
    private const val KEY_SESSDATA = "sessdata"
    private const val KEY_QUALITY_MODE = "quality_mode"

    /** 自动：WiFi 高清、流量低清（默认） */
    const val MODE_AUTO = "auto"

    /** 始终高清（WiFi/流量都用高码率） */
    const val MODE_HIGH = "high"

    /** 始终低清（省流量） */
    const val MODE_LOW = "low"

    /** B站清晰度代号：32=480P，64=720P（登录后才拿得到，未登录一律 320×240） */
    /**
     * B站清晰度代号。**实测（2026-09-11）html5 模式只有两档**：
     *
     * ```
     * 请求 qn=16 / 32 / 64  → 平台只给 360P（102MB / 105 分钟，约 58MB/小时）
     * 请求 qn=80 及以上     → 给 1080P（810MB / 105 分钟，约 460MB/小时）
     * ```
     *
     * **没有 720P 中间档** —— 所以这个设置是「省流量」和「最清楚」二选一，
     * 界面上别再写 720P 误导用户（原先就写错了，实测才发现拿不到）。
     * 另外最终清晰度还受**片源本身**限制：八九十年代的老录像只有 240P/360P，选高清也救不回来。
     */
    const val QN_LOW = 32    // → 360P，约 58MB/小时
    const val QN_HIGH = 80   // → 1080P，约 460MB/小时

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 登录态

    /** 取登录凭证；没登录返回 null */
    fun sessData(context: Context): String? =
        prefs(context).getString(KEY_SESSDATA, null)?.trim()?.takeIf { it.isNotBlank() }

    fun isLoggedIn(context: Context): Boolean = sessData(context) != null

    fun saveSessData(context: Context, value: String?) {
        val v = value?.trim().orEmpty()
        prefs(context).edit().apply {
            if (v.isBlank()) remove(KEY_SESSDATA) else putString(KEY_SESSDATA, v)
            apply()
        }
    }

    fun logout(context: Context) = saveSessData(context, null)

    // ------------------------------------------------------------------ 画质

    fun qualityMode(context: Context): String =
        prefs(context).getString(KEY_QUALITY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun saveQualityMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_QUALITY_MODE, mode).apply()
    }

    /** 当前网络是不是 WiFi（判断不出时按「不是 WiFi」处理，宁可省流量） */
    fun isWifi(context: Context): Boolean = runCatching {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /**
     * 本次播放应请求的清晰度。
     *
     * 注意这只是**请求值**：未登录时 B站会把它压回 320×240（`accept_quality` 限制），
     * 这是平台行为，不是 App 的 bug。
     */
    fun effectiveQn(context: Context): Int = when (qualityMode(context)) {
        MODE_HIGH -> QN_HIGH
        MODE_LOW -> QN_LOW
        else -> if (isWifi(context)) QN_HIGH else QN_LOW
    }

    /**
     * 给设置页显示的一句话状态。
     *
     * 注意措辞：**登录不改变戏曲唱段的清晰度**（实测：同一视频登录前后 quality 完全相同，
     * html5 模式的清晰度由源视频决定）。登录的实际作用是访问「需要登录才能看」的内容，
     * 以及让搜索接口更稳定。所以这里如实写，避免让用户以为"登录了就更清楚"。
     */
    fun describe(context: Context): String {
        val logged = isLoggedIn(context)
        return if (logged) {
            "已登录（本机保存）· 戏曲清晰度由片源决定，登录不改变画质"
        } else {
            "未登录 · 可正常看戏曲（清晰度由片源决定）"
        }
    }

    /** 画质偏好的一句话说明（只有 360P / 1080P 两档，且最终受片源限制） */
    fun describeQuality(context: Context): String {
        val net = if (isWifi(context)) "WiFi" else "流量"
        return when (qualityMode(context)) {
            MODE_HIGH -> "始终请求高清档（1080P，约 460MB/小时）"
            MODE_LOW -> "始终请求省流档（360P，约 58MB/小时）"
            else -> "自动：当前 $net → ${if (isWifi(context)) "高清档 1080P" else "省流档 360P"}"
        }
    }
}
