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

    /** B站点播列表的独立缓存：**24 小时**才重搜一次（搜索接口有风控，见 [BiliClient]） */
    private const val BILI_CACHE_FILE = "tv_bili_cache.json"
    private const val BILI_CACHE_TIME_FILE = "tv_bili_cache.time"
    private const val BILI_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    /**
     * 远程清单源。顺序即优先级；单个失败不影响整体。
     * 想加源就往这里加，改一行即可。
     */
    val remoteSources: List<String> = listOf(
        "https://live.zbds.top/tv/iptv4.m3u",
        // 明月源：gitee 上会先 302 到 raw.giteeusercontent.com，OkHttp 的 followRedirects 能跟上
        "https://gitee.com/yygitee118/IPTV/raw/master/iptv_sources.m3u8"
    )

    /**
     * **动态地址刷新**：戏曲两个台的地址带签名、会过期，硬编码迟早失效。
     *
     * 实测（2026-09-11）：`waadri.top` 的频道页是**实时生成**播放地址的
     * （页面里的 timestamp 与访问时刻精确同步），而且会**按访问者网络**给线路。
     * 所以每次刷新清单时抓一次页面，把拿到的地址插到**最前面**当主源，旧地址降级为备用。
     *
     * 为什么必须做这件事：内置种子里 CCTV-11 的几条源实测是
     * **H.264 视频 + MP2 音频** —— 安卓系统不带 MP2 解码器，
     * 表现就是「画面出来了但一点声音都没有」；而咪咕这条是 h264 + **aac**，声画都正常。
     */
    private data class DynamicSource(
        val channelId: String,
        val pageUrl: String,
        val urlPattern: Regex
    )

    private val dynamicSources: List<DynamicSource> = listOf(
        DynamicSource(
            channelId = "cctv11",
            pageUrl = "https://www.waadri.top/TV/CCTV/cctv11.php?id=3",
            urlPattern = Regex("""https://[^\s"'<>]*miguvideo[^\s"'<>]*\.m3u8[^\s"'<>]*""")
        ),
        DynamicSource(
            channelId = "梨园",
            // 河南台「梨园频道」页（路径里有中文，用百分号编码，避免 OkHttp 报 URL 非法）
            pageUrl = "https://www.waadri.top/TV/%E6%B2%B3%E5%8D%97/HNLYPD.php",
            urlPattern = Regex("""https://[^\s"'<>]*hntv\.tv[^\s"'<>]*\.m3u8[^\s"'<>]*""")
        )
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
            val bili = loadBiliChannels(context)
            val base = when {
                remote.isNotEmpty() -> remote
                else -> readCache(context)?.takeIf { it.isNotEmpty() } ?: BuiltinChannelSeed.channels()
            }
            // 点播唱段追加在最后：老人先看到「打开就有」的直播台，往下翻才是唱段
            val merged = base + bili.filter { b -> base.none { it.id == b.id } }
            if (remote.isNotEmpty()) saveCache(context, merged)
            Log.d(TAG, "load: 共 ${merged.size} 个频道（远程 ${remote.size} + B站点播 ${bili.size}）")
            merged
        }

    /**
     * 取某个分类下**给老人看的**频道列表。
     *
     * 刻意放在这一层而不是 `load()` 里：清单缓存保持完整（几百条全存），
     * 这样调整 [TvClassifier] 的判定规则立刻生效，不用等 6 小时缓存过期。
     */
    fun byCategory(channels: List<TvChannel>, category: TvCategory): List<TvChannel> {
        val list = channels.filter { it.playable && TvClassifier.categoryOf(it) == category }
        val pins = PINNED_FIRST[category] ?: return list
        val pinned = list.filter { ch -> pins.any { ch.name.contains(it) } }
        if (pinned.isEmpty()) return list
        val pinnedIds = pinned.map { it.id }.toSet()
        return pinned + list.filterNot { it.id in pinnedIds }
    }

    /**
     * 分类里的**置顶台**。
     *
     * 戏曲分类必须把「梨园」放第一个：CCTV-11 深夜（实测 02:10、02:47）几个镜像都在播
     * **央视自己的测试卡**（彩条 + 测试图），属于频道自身播出安排；而梨园 24 小时有真节目。
     * 老人进分类默认播列表第一个，所以第一个必须是「打开就有戏」的那个。
     * 直播源站给的下发顺序不可控（实测 CCTV-11 排在梨园前面），所以在这里强制置顶。
     */
    private val PINNED_FIRST: Map<TvCategory, List<String>> = mapOf(
        TvCategory.OPERA to listOf("梨园")
    )

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
        return applyDynamicSources(mergeWithBuiltin(collected.values.toList()))
    }

    /**
     * 抓 waadri 页面刷新戏曲两台的签名地址，插到各自频道的最前面。
     * 抓不到就原样返回（静态源继续兜底），绝不让刷新失败影响整个列表。
     */
    private fun applyDynamicSources(channels: List<TvChannel>): List<TvChannel> {
        if (channels.isEmpty()) return channels
        val fresh = HashMap<String, String>()
        for (src in dynamicSources) {
            val url = runCatching { fetchDynamicUrl(src) }.getOrElse {
                Log.w(TAG, "动态地址失败 ${src.channelId}: ${it.javaClass.simpleName}")
                null
            } ?: continue
            fresh[src.channelId] = url
        }
        if (fresh.isEmpty()) return channels
        Log.d(TAG, "动态刷新成功: ${fresh.keys}")
        return channels.map { ch ->
            val u = fresh[ch.id] ?: return@map ch
            if (u in ch.urls) ch else ch.copy(urls = listOf(u) + ch.urls)
        }
    }

    private fun fetchDynamicUrl(src: DynamicSource): String? {
        val html = fetchText(src.pageUrl) ?: return null
        return src.urlPattern.find(html)?.value
    }

    /**
     * B站点播唱段：独立缓存 24h。
     *
     * 搜索接口有风控，所以**宁可拿旧列表也不频繁重搜**：
     * 过期才搜一次；搜不到（风控/断网）时保留旧缓存，不把 B站内容清空。
     */
    private suspend fun loadBiliChannels(context: Context): List<TvChannel> {
        val cached = readBiliCache(context)
        if (cached != null &&
            System.currentTimeMillis() - biliCacheTime(context) < BILI_CACHE_TTL_MS
        ) {
            return cached
        }
        val fetched = runCatching { BiliClient.fetchOperaChannels() }
            .getOrElse {
                Log.w(TAG, "B站拉取失败: ${it.javaClass.simpleName}")
                emptyList()
            }
        return if (fetched.isNotEmpty()) {
            saveBiliCache(context, fetched)
            fetched
        } else {
            cached ?: emptyList()
        }
    }

    private fun biliCacheFile(context: Context) = File(context.filesDir, BILI_CACHE_FILE)
    private fun biliCacheTimeFile(context: Context) = File(context.filesDir, BILI_CACHE_TIME_FILE)

    private fun readBiliCache(context: Context): List<TvChannel>? = runCatching {
        val f = biliCacheFile(context)
        if (!f.exists()) return null
        gson.fromJson<List<TvChannel>>(f.readText(), listType)?.filter { it.urls.isNotEmpty() }
    }.getOrElse {
        Log.w(TAG, "B站缓存损坏，忽略: ${it.javaClass.simpleName}")
        null
    }

    private fun saveBiliCache(context: Context, channels: List<TvChannel>) = runCatching {
        biliCacheFile(context).writeText(gson.toJson(channels))
        biliCacheTimeFile(context).writeText(System.currentTimeMillis().toString())
    }.onFailure { Log.w(TAG, "写B站缓存失败: ${it.javaClass.simpleName}") }

    private fun biliCacheTime(context: Context): Long = runCatching {
        val f = biliCacheTimeFile(context)
        if (f.exists()) f.readText().toLongOrNull() ?: 0L else 0L
    }.getOrDefault(0L)

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
