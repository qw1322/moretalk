package com.example.onepass.tv.data

import android.content.Context
import android.util.Log
import com.example.onepass.tv.model.TvCategory
import com.example.onepass.tv.model.TvChannel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 频道源仓库。
 *
 * 职责边界（沿用项目 Repository 分层的约定：平台异常在此收敛，不向 UI 泄漏）：
 *   1. 拉取远程 m3u 清单 → 解析 → 合并内置种子 → 落盘缓存
 *   2. 提供「同步读缓存」（供播放页秒开）与「挂起拉取」（供频道页刷新）两个入口
 *   3. 远程全挂 / 断网 / 缓存损坏时，**永远至少返回内置种子**，不让 UI 拿到空列表
 *   4. 提供「按分类取台」([byCategory]) —— 分类判定本身在 [TvClassifier]
 *
 * 缓存策略：TTL 6 小时（与主流公开源站的更新节奏一致）。
 */
object TvRepository {

    private const val TAG = "TvRepository"
    private const val CACHE_FILE = "tv_channels_cache.json"
    private const val CACHE_TIME_FILE = "tv_channels_cache.time"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 远程清单源。顺序即优先级；单个失败不影响整体。
     * 想加源就往这里加，改一行即可。
     */
    val remoteSources: List<String> = listOf(
        "https://live.zbds.top/tv/iptv4.m3u",
        // 明月源：gitee 上会先 302 到 raw.giteeusercontent.com，OkHttp 的 followRedirects 能跟上
        "https://gitee.com/yygitee118/IPTV/raw/master/iptv_sources.m3u8"
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<TvChannel>>() {}.type

    // ------------------------------------------------------------------ 对外 API

    /** 同步读缓存：播放页 onCreate 里直接调，保证「点一下就有画面」，不卡白屏 */
    fun cached(context: Context): List<TvChannel> = readCache(context) ?: BuiltinChannelSeed.channels()

    /** 缓存是否还在有效期内 */
    fun isCacheFresh(context: Context): Boolean =
        System.currentTimeMillis() - cacheTime(context) < CACHE_TTL_MS

    /**
     * 加载频道列表。已在 IO 线程执行，UI 直接 await 即可。
     *
     * @param forceRefresh true = 忽略 TTL 强制刷新（设置页「重新获取频道」用）
     */
    suspend fun load(context: Context, forceRefresh: Boolean = false): List<TvChannel> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && isCacheFresh(context)) {
                readCache(context)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            }
            val remote = fetchRemote()
            val channels = if (remote.isNotEmpty()) {
                saveCache(context, remote)
                remote
            } else {
                // 远程全挂：退回旧缓存，再退回内置种子
                readCache(context)?.takeIf { it.isNotEmpty() } ?: BuiltinChannelSeed.channels()
            }
            Log.d(TAG, "load: 共 ${channels.size} 个频道（远程 ${remote.size}）")
            channels
        }

    /**
     * 取某个分类下**给老人看的**频道列表。
     *
     * 刻意放在这一层而不是 `load()` 里：清单缓存保持完整（几百条全存），
     * 这样调整 [TvClassifier] 的判定规则立刻生效，不用等 6 小时缓存过期。
     */
    fun byCategory(channels: List<TvChannel>, category: TvCategory): List<TvChannel> =
        channels.filter { it.playable && TvClassifier.categoryOf(it) == category }

    /** 各分类各有多少个台（分类页显示用；分类判定为 null 的台不计入） */
    fun counts(channels: List<TvChannel>): Map<TvCategory, Int> {
        val result = LinkedHashMap<TvCategory, Int>()
        TvCategory.ordered.forEach { cat -> result[cat] = 0 }
        channels.filter { it.playable }.forEach { ch ->
            val cat = TvClassifier.categoryOf(ch) ?: return@forEach
            result[cat] = (result[cat] ?: 0) + 1
        }
        return result
    }

    /** 当前用的是不是内置种子兜底（频道页用它显示正确的来源提示） */
    fun isUsingBuiltinFallback(context: Context): Boolean =
        readCache(context).isNullOrEmpty()

    // ------------------------------------------------------------------ 内部实现

    /** 逐个拉远程源，成功即累积；全失败返回空列表（由调用方兜底） */
    private fun fetchRemote(): List<TvChannel> {
        val collected = LinkedHashMap<String, TvChannel>()
        for (url in remoteSources) {
            val text = runCatching { fetchText(url) }.getOrElse {
                Log.w(TAG, "拉取失败 $url : ${it.javaClass.simpleName}")
                null
            } ?: continue
            M3uParser.parse(text).forEach { ch ->
                val exist = collected[ch.id]
                if (exist == null) {
                    collected[ch.id] = ch
                } else {
                    // 同名频道多源合并：地址去重后拼在一起，形成备用源
                    val extra = ch.urls.filter { it !in exist.urls }
                    if (extra.isNotEmpty()) collected[ch.id] = exist.copy(urls = exist.urls + extra)
                }
            }
        }
        return mergeWithBuiltin(collected.values.toList())
    }

    /**
     * 把内置种子并进远程清单：
     * 远程没有的种子频道追加进来；远程已有了的，**把种子地址放到最前面**当主源。
     *
     * 为什么种子要放前面（踩过坑）：
     *   种子里是**人工逐个实测过**的地址；远程清单里同名频道的地址大量已失效
     *   （实测 CCTV-11 的 6 条远程地址全军覆没，其中一条还会「播放列表返回 200 但切片下不动」，
     *   让老人干等十几秒才失败）。如果把种子排在后面，播放器会先把这一串坏地址试一遍，
     *   体感就是「点了半天不出画面 / 一直连不上」。所以：**种子优先，远程地址去重后作备用**。
     */
    private fun mergeWithBuiltin(remote: List<TvChannel>): List<TvChannel> {
        if (remote.isEmpty()) return BuiltinChannelSeed.channels()
        val byId = LinkedHashMap<String, TvChannel>()
        remote.forEach { byId[it.id] = it }
        BuiltinChannelSeed.channels().forEach { seed ->
            val exist = byId[seed.id]
            byId[seed.id] = if (exist == null) {
                seed
            } else {
                val extra = exist.urls.filter { it !in seed.urls }
                seed.copy(urls = seed.urls + extra)
            }
        }
        return byId.values.toList()
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)
    private fun cacheTimeFile(context: Context) = File(context.filesDir, CACHE_TIME_FILE)

    private fun readCache(context: Context): List<TvChannel>? = runCatching {
        val f = cacheFile(context)
        if (!f.exists()) return null
        gson.fromJson<List<TvChannel>>(f.readText(), listType)?.filter { it.urls.isNotEmpty() }
    }.getOrElse {
        Log.w(TAG, "缓存损坏，忽略: ${it.javaClass.simpleName}")
        null
    }

    private fun saveCache(context: Context, channels: List<TvChannel>) = runCatching {
        cacheFile(context).writeText(gson.toJson(channels))
        cacheTimeFile(context).writeText(System.currentTimeMillis().toString())
    }.onFailure { Log.w(TAG, "写缓存失败: ${it.javaClass.simpleName}") }

    private fun cacheTime(context: Context): Long = runCatching {
        val f = cacheTimeFile(context)
        if (f.exists()) f.readText().toLongOrNull() ?: 0L else 0L
    }.getOrDefault(0L)
}
