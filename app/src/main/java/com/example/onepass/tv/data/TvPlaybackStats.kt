package com.example.onepass.tv.data

import android.content.Context
import android.util.Log

/**
 * 频道播放健康度：**让列表自己变干净**。
 *
 * 为什么需要它：
 *   直播源站下发的地址里有相当比例的坏源 —— 实测 688 条源里央视只有 17 条能下分片，
 *   能下的里面还有一批是 **MP2 音频**（安卓不带解码器 → 有画面没声音）或
 *   **码率远超带宽**（→ 花屏、卡顿）。老人点进去看到花屏/无声，只会更困惑。
 *
 *   源的好坏是**随时变化**的（今天好的明天可能挂），硬编码白名单维护不过来。
 *   所以这里记录运行时的真实结果：**连续失败到阈值就把它从分类里隐藏**，
 *   一旦某次成功立刻清零恢复 —— 不需要人工干预，也不需要联网探测。
 *
 * 与「换源重试」的分工：
 *   [com.example.onepass.tv.ui.TvPlayerActivity] 负责一次播放内的换源/换台重试；
 *   本类负责**跨会话**记住「这个台长期不行」，避免老人反复踩。
 */
object TvPlaybackStats {

    private const val TAG = "TvPlaybackStats"
    private const val PREFS = "tv_stats_prefs"
    private const val KEY_PREFIX = "fail_"

    /** 连续失败几次就隐藏（太敏感会误伤偶发抽风，太迟钝老人要多踩几次） */
    const val HIDE_THRESHOLD = 3

    /** 失败记录的有效期：超过这个时间不再算数（源可能已经修好了） */
    private const val STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val KEY_TIME_PREFIX = "fail_at_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录一次失败。返回累计连续失败次数。 */
    fun recordFail(context: Context, channelId: String): Int {
        val p = prefs(context)
        val n = failCount(context, channelId) + 1
        p.edit()
            .putInt(KEY_PREFIX + channelId, n)
            .putLong(KEY_TIME_PREFIX + channelId, System.currentTimeMillis())
            .apply()
        if (n >= HIDE_THRESHOLD) Log.d(TAG, "频道 $channelId 连续失败 $n 次，将从分类中隐藏")
        return n
    }

    /** 播放成功（出了首帧）就清零 —— 源恢复了要立刻让它回来 */
    fun recordSuccess(context: Context, channelId: String) {
        val p = prefs(context)
        if (p.getInt(KEY_PREFIX + channelId, 0) == 0) return
        Log.d(TAG, "频道 $channelId 播放成功，失败计数清零")
        p.edit().remove(KEY_PREFIX + channelId).remove(KEY_TIME_PREFIX + channelId).apply()
    }

    fun failCount(context: Context, channelId: String): Int {
        val p = prefs(context)
        val at = p.getLong(KEY_TIME_PREFIX + channelId, 0L)
        // 记录太旧就当作没发生过（避免源修好了还一直被隐藏）
        if (at > 0 && System.currentTimeMillis() - at > STALE_MS) {
            p.edit().remove(KEY_PREFIX + channelId).remove(KEY_TIME_PREFIX + channelId).apply()
            return 0
        }
        return p.getInt(KEY_PREFIX + channelId, 0)
    }

    /** 是否该从分类里隐藏 */
    fun isHidden(context: Context, channelId: String): Boolean =
        failCount(context, channelId) >= HIDE_THRESHOLD

    /** 清空所有记录（设置页「重新获取频道」时一并调用，给所有台一次机会） */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 当前被隐藏的频道数（诊断用） */
    fun hiddenCount(context: Context): Int =
        prefs(context).all.keys.count { it.startsWith(KEY_PREFIX) && isHidden(context, it.removePrefix(KEY_PREFIX)) }
}
