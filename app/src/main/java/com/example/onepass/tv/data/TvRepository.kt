package com.example.onepass.tv.data

import android.content.Context
import android.util.Log
import com.example.onepass.tv.model.TvCategory
import com.example.onepass.tv.model.TvChannel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
     * B站点播缓存：**按关键词**存一份（`Map<关键词, 该批结果>`）。
     *
     * 拆开存的意义见 [loadBiliChannels] —— 风控只会打掉某一批，不会让整个列表腰斩。
     * 单批 3 天才重搜：视频内容变化很慢，而搜索接口有风控，调用越少越安全。
     */
    private const val BILI_CACHE_FILE = "tv_bili_cache.json"
    private const val BILI_TTL_MS = 3 * 24 * 60 * 60 * 1000L

    /** 每轮最多补搜几个关键词（把突发请求摊开，降低风控概率） */
    private const val BILI_BATCH = 3

    /** 动态地址抓取的并发度（18 个源站页面，并发太高会把源站打挂） */
    private const val DYNAMIC_CONCURRENCY = 6

    /** 关键词之间的间隔（实测：B站 限频率，连发必被拦） */
    private const val BILI_GAP_MS = 1500L

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

    /** 咪咕源（h264 + **aac**，720p/480p，国内 CDN）—— 央视各台用这个 */
    private val MIGU_RE = Regex("""https://[^\s"'<>]*miguvideo[^\s"'<>]*\.m3u8[^\s"'<>]*""")

    /** 河南台官方源（腾讯云防盗链，txTime 到 2035 年） */
    private val HNTV_RE = Regex("""https://[^\s"'<>]*hntv\.tv[^\s"'<>]*\.m3u8[^\s"'<>]*""")

    /**
     * 每个源站页面的 `id` 是**线路号**，不同频道能出地址的线路不一样
     * （实测：cctv1/2/5/6 走 id=2，cctv3/cctv11 走 id=3）——
     * 所以按顺序试几个，取第一个能用的，别写死一个。
     */
    private val LINE_IDS = listOf(2, 3, 1, 4)

    private val dynamicSources: List<DynamicSource> = buildList {
        // 央视 1~17：静态源实测 104 条里只有 17 条能下分片，且 CCTV-1 唯一那条是跨境源
        // （下行仅 68KB/s，720p 必然卡顿 + 音画不同步）；咪咕这条是国内 CDN，实测 1MB/s 级。
        for (i in 1..17) {
            add(
                DynamicSource(
                    channelId = "cctv$i",
                    pageUrl = "https://www.waadri.top/TV/CCTV/cctv$i.php",
                    urlPattern = MIGU_RE
                )
            )
        }
        // 戏曲：梨园（河南台官方源）
        add(
            DynamicSource(
                channelId = "梨园",
                // 河南台「梨园频道」页（路径里有中文，用百分号编码，避免 OkHttp 报 URL 非法）
                pageUrl = "https://www.waadri.top/TV/%E6%B2%B3%E5%8D%97/HNLYPD.php",
                urlPattern = HNTV_RE
            )
        )
    }



    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<TvChannel>>() {}.type
    private val biliMapType = object : TypeToken<Map<String, BiliCacheEntry>>() {}.type

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
    fun byCategory(context: Context, channels: List<TvChannel>, category: TvCategory): List<TvChannel> {
        // 先把「连续失败到阈值」的台滤掉 —— 让列表自己变干净（见 [TvPlaybackStats]）
        val alive = channels.filterNot { TvPlaybackStats.isHidden(context, it.id) }
        val list = alive.filter { it.playable && TvClassifier.categoryOf(it) == category }
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
    private suspend fun fetchRemote(): List<TvChannel> {
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
     * 抓源站页面刷新动态地址，插到各自频道的最前面。
     * 抓不到就原样返回（静态源继续兜底），绝不让刷新失败影响整个列表。
     *
     * 18 台串行抓要十几秒、明显拖慢「第一次进看电视」的体感，所以**分批并发**；
     * 并发压到 [DYNAMIC_CONCURRENCY] 是为了别把源站打挂（打挂了大家都没得看）。
     */
    private suspend fun applyDynamicSources(channels: List<TvChannel>): List<TvChannel> =
        withContext(Dispatchers.IO) {
            if (channels.isEmpty()) return@withContext channels
            val fresh = mutableMapOf<String, String>()
            dynamicSources.chunked(DYNAMIC_CONCURRENCY).forEach { batch ->
                val got = batch.map { src ->
                    async {
                        src.channelId to runCatching { fetchDynamicUrl(src) }.getOrNull()
                    }
                }.awaitAll()
                got.forEach { (id, url) -> if (!url.isNullOrBlank()) fresh[id] = url }
            }
            if (fresh.isEmpty()) return@withContext channels
            Log.d(TAG, "动态刷新成功 ${fresh.size} 台: ${fresh.keys}")
            channels.map { ch ->
                val u = fresh[ch.id] ?: return@map ch
                if (u in ch.urls) ch else ch.copy(urls = listOf(u) + ch.urls)
            }
        }

    /** 依次试几条线路（不同频道能出地址的线路不同），拿到第一个可用地址 */
    private suspend fun fetchDynamicUrl(src: DynamicSource): String? {
        for (line in LINE_IDS) {
            val html = fetchText("${src.pageUrl}?id=$line") ?: continue
            val url = src.urlPattern.find(html)?.value
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    /**
     * B站点播唱段：**按关键词独立缓存 + 分批补搜**。
     *
     * 为什么要这么麻烦（两个坑换来的）：
     *   ① **风控腰斩**：一次把 8 个关键词全打过去，被限流就是整批失败 ——
     *      实测发生过「4 个关键词挂 2 个，唱段从 43 条掉到 12 条」。
     *      拆成按关键词缓存后，失败的批次只是缺席，已成功的一直留着，下一轮自动补搜。
     *   ② **协程取消**：一轮拉取十几秒，挂在 Activity 的协程上时，老人从分类页点进播放页
     *      就会 `JobCancellationException` —— 请求白费、缓存也没写成。所以补搜用
     *      [NonCancellable] 护住，务必把结果落盘。
     *
     * 每轮最多补搜 [BILI_BATCH] 个关键词、之间留 [BILI_GAP_MS] 间隔，把突发请求摊开；
     * 单批成功即写盘、立即返回当前全部内容（老人不必等 8 批全跑完）。
     */
    private suspend fun loadBiliChannels(context: Context): List<TvChannel> {
        val cache = readBiliMap(context).toMutableMap()
        val now = System.currentTimeMillis()
        val stale = BiliClient.KEYWORDS.filter { kw ->
            val e = cache[kw]
            e == null || e.channels.isEmpty() || now - e.at >= BILI_TTL_MS
        }
        if (stale.isNotEmpty()) {
            val batch = stale.take(BILI_BATCH)
            Log.d(TAG, "B站补搜 ${batch.size} 批（共 ${stale.size} 批待补）")
            var changed = false
            withContext(NonCancellable) {
                for (kw in batch) {
                    val chs = runCatching { BiliClient.fetchByKeyword(context, kw) }
                        .onFailure { Log.w(TAG, "B站「$kw」失败: ${it.javaClass.simpleName}") }
                        .getOrNull()
                    if (!chs.isNullOrEmpty()) {
                        cache[kw] = BiliCacheEntry(now, chs)
                        changed = true
                    }
                    delay(BILI_GAP_MS)   // 摊开请求，降低风控概率
                }
            }
            if (changed) saveBiliMap(context, cache)
        }
        val all = cache.values.flatMap { it.channels }.distinctBy { it.id }
        Log.d(
            TAG,
            "B站唱段合计 ${all.size} 条（已成功 ${cache.count { it.value.channels.isNotEmpty() }}/${BiliClient.KEYWORDS.size} 批）"
        )
        return BiliClient.sortHdFirst(all)
    }

    private fun biliCacheFile(context: Context) = File(context.filesDir, BILI_CACHE_FILE)

    /** 关键词 → 该批结果与时间戳 */
    private data class BiliCacheEntry(
        val at: Long = 0L,
        val channels: List<TvChannel> = emptyList()
    )

    private fun readBiliMap(context: Context): Map<String, BiliCacheEntry> = runCatching {
        val f = biliCacheFile(context)
        if (!f.exists()) return emptyMap()
        gson.fromJson<Map<String, BiliCacheEntry>>(f.readText(), biliMapType) ?: emptyMap()
    }.getOrElse {
        Log.w(TAG, "B站缓存损坏，忽略: ${it.javaClass.simpleName}")
        emptyMap()
    }

    private fun saveBiliMap(context: Context, map: Map<String, BiliCacheEntry>) = runCatching {
        biliCacheFile(context).writeText(gson.toJson(map))
    }.onFailure { Log.w(TAG, "写B站缓存失败: ${it.javaClass.simpleName}") }

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
