package com.example.onepass.tv.data

import android.util.Log
import com.example.onepass.tv.model.TvChannel
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * B站戏曲**点播**源（不是直播）。
 *
 * 为什么走点播而不是直播（结论来自实测）：
 *   · B站**没有戏曲直播分区**，按人气翻遍相关分区 360 个房间，戏曲相关 0 个；
 *     `search_type=live_room` 对「京剧/越剧/黄梅」返回 0 条。
 *   · 但**点播内容极丰富**：搜「豫剧 全场戏」20 条、京剧/黄梅戏/越剧各 20 条，
 *     都是免费的整场戏与名家名段（1080P、带字幕），实测直链 1MB/s。
 *   → 所以直播仍走 IPTV（梨园 / CCTV-11），**点播补 B站**，两者互补。
 *
 * 实测出来的三条硬约束（踩过坑，改代码前先看）：
 *   1. **必须带 Referer** `https://www.bilibili.com/`。不带 Referer 直接失败；
 *      带 Referer 时**不需要 cookie**（app 里也就不必维护登录态）。
 *   2. **搜索接口有频率风控**：连续快速调用会返回非 JSON（风控页）。
 *      所以本类**只在缓存过期（24h）时**才发起搜索，且失败一律保留旧缓存，
 *      绝不因为风控把 B站内容从列表里清空。
 *   3. **播放地址是短时效签名 URL**，不能存进频道列表 ——
 *      列表里存的是 `bili://<bvid>/<cid>`，真正地址在播放前才换（[resolvePlayUrl]）。
 *
 * 流量：`platform=html5` 拿到的 mp4 约 **217 kbps（≈100 MB/小时）**，
 * 比 HLS 直播（2 Mbps）省一个数量级，老人流量下看戏也能接受。
 */
object BiliClient {

    private const val TAG = "BiliClient"

    /** 点播条目的伪协议前缀：`bili://<bvid>/<cid>` */
    const val SCHEME = "bili://"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"

    /**
     * 搜索关键词。刻意带「全场」而不是「名段」：
     * 名段合集多为八九十年代的录像带翻录（源本身只有 240P，任何方式都救不回来），
     * 而「全场」更容易命中近年的舞台版/艺术剧院官方录制（源就是 720P/1080P）。
     *
     * 实测依据（2026-09-11）：
     *   · 豫剧名家名段合集（老录像）→ html5 模式给 **320×240**
     *   · 黄梅戏《女驸马》2026 新录 → **720P**（205MB/127分钟）
     *   · 京剧《穆桂英挂帅》高清舞台版 → **1080P**（810MB/105分钟）
     *   · 登录与否**完全不影响**这三个结果 —— 清晰度由源视频决定，不由账号决定。
     */
    /**
     * 搜索关键词。**两类混搭**（这是「数量」和「画质」的折中）：
     *   · 「名段」类：命中大量经典唱段合集，分 P 就是独立唱段（6~7 分钟一段，老人一段一段听正合适），
     *     缺点是多为八九十年代录像带翻录，源本身只有 240P/360P；
     *   · 「全场 高清」类：命中近年舞台版 / 艺术剧院官方录制，源本身就是 720P/1080P。
     *
     * 采集是**按关键词分批 + 独立缓存**的（见 `TvRepository.loadBiliChannels`）：
     * 某个关键词被风控，只是这一批缺，已成功的批次照样保留，下次自动补搜。
     */
    val KEYWORDS = listOf(
        "豫剧 名段", "京剧 名段", "黄梅戏 名段", "越剧 名段",
        "豫剧 全场 高清", "京剧 全场 高清", "黄梅戏 全场", "越剧 全场"
    )
    /**
     * 高清线索词：命中者排到前面。
     *
     * 为什么靠标题而不是靠探测：探测每个视频的清晰度要**每视频多一次 API 调用**，
     * 而搜索接口有风控（见类注释），调用量必须克制。标题里的「1080/高清/舞台版/艺术剧院」
     * 已经足够把新录制的真高清和老录像区分开。
     */
    private val HD_HINTS = listOf(
        "1080", "720", "4k", "4K", "高清", "超清", "修复", "舞台版",
        "艺术剧院", "剧院", "全剧", "全场"
    )

    /** 每个关键词取前几个视频（调用量 = 关键词数 + 视频数，风控下要克制） */
    private const val VIDEOS_PER_KEYWORD = 3

    /**
     * 视频之间的间隔。
     *
     * **实测出来的风控规律**（2026-09-11）：B站 限的是**请求频率**，不是累计量 ——
     * 一个关键词内部「1 次搜索 + 4 次 view」连发就会触发，
     * 日志里表现为**成功/失败严格交替**（18 条 → 0 条 → 11 条 → 0 条）。
     * 所以 view 之间必须留间隔，宁可慢几秒，也不要整批被拦。
     */
    private const val VIDEO_GAP_MS = 700L

    /** 每个视频最多展开几个分 P（有些合集上百 P，全展开会把列表淹掉） */
    private const val PARTS_PER_VIDEO = 8

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 拉取**单个关键词**的戏曲唱段（搜索 → 展开分 P）。
     *
     * 为什么按关键词拆开而不是一次拉全部：
     * 搜索接口有风控，一次把 8 个关键词全打过去，一旦被限流就是**整批失败、列表腰斩**。
     * 拆开后配合 [com.example.onepass.tv.data.TvRepository] 的「按关键词独立缓存 + 分批补搜」，
     * 风控只会让某几批缺席，已拿到的照常可用，下一轮自动补齐。
     *
     * @return 空列表表示这一批失败（调用方**不要**把它记成"已完成"，下次会重试）
     */
    suspend fun fetchByKeyword(context: android.content.Context, keyword: String): List<TvChannel> =
        withContext(Dispatchers.IO) {
            val cookie = BiliAccount.sessData(context)
            val videos = runCatching { search(keyword, cookie) }.getOrElse {
                Log.w(TAG, "搜索失败「$keyword」: ${it.javaClass.simpleName}")
                return@withContext emptyList()
            }
            if (videos.isEmpty()) {
                Log.w(TAG, "搜索「$keyword」无结果（可能被风控）")
                return@withContext emptyList()
            }
            val out = LinkedHashMap<String, TvChannel>()
            for (v in videos) {
                runCatching { expandVideo(v, cookie) }.getOrElse {
                    Log.w(TAG, "展开失败 ${v.bvid}: ${it.javaClass.simpleName}")
                    emptyList()
                }.forEach { ch -> out[ch.id] = ch }
                delay(VIDEO_GAP_MS)   // 见 VIDEO_GAP_MS 注释：连发 view 会被限流
            }
            Log.d(TAG, "关键词「$keyword」→ ${out.size} 条")
            sortHdFirst(out.values.toList())
        }

    /**
     * 高清源排前面：标题里带「1080/高清/舞台版/艺术剧院」的多是近年录制，
     * 源本身就有 720P/1080P；老录像排在后面（内容仍是经典，只是画质受限于母带）。
     */
    fun sortHdFirst(list: List<TvChannel>): List<TvChannel> {
        val (hd, sd) = list.partition { ch ->
            HD_HINTS.any { ch.name.contains(it, ignoreCase = true) }
        }
        return hd + sd
    }

    /**
     * 把 `bili://<bvid>/<cid>` 换成真实 mp4 直链。失败返回 null（调用方按「换下一条源」处理）。
     *
     * 两条取流路径，按「能不能真的拉下来」决定用哪条：
     *   ① **标准模式**（登录后）：qn=480P/720P，画质好；但直链**可能需要 Referer/cookie**，
     *      所以拿到 URL 后先用 OkHttp 探一次（Range 1KB），探通了才交播放器。
     *   ② **html5 模式**（兜底）：免登录、免 Referer 必定能拉，但平台只给 **320×240**。
     * 这样保证「有登录 → 尽量高清；高清拉不动 → 自动退回能播的」，不会让老人看到黑屏。
     */
    suspend fun resolvePlayUrl(context: android.content.Context, biliUrl: String): String? =
        withContext(Dispatchers.IO) {
            val body = biliUrl.removePrefix(SCHEME)
            val parts = body.split('/')
            val bvid = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@withContext null
            val cid = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@withContext null
            val cookie = BiliAccount.sessData(context)
            val qn = BiliAccount.effectiveQn(context)

            if (cookie != null) {
                val std = runCatching { standardPlayUrl(bvid, cid, qn, cookie) }.getOrNull()
                if (std != null && probePlayable(std)) {
                    Log.d(TAG, "用标准模式 qn=$qn 播放 $bvid")
                    return@withContext std
                }
                Log.w(TAG, "标准模式不可用（qn=$qn），退回 html5 模式 $bvid")
            }
            runCatching { html5PlayUrl(bvid, cid) }.getOrElse {
                Log.w(TAG, "解析播放地址失败 $bvid/$cid: ${it.javaClass.simpleName}")
                null
            }
        }

    // ------------------------------------------------------------------ 内部

    private data class VideoBrief(val bvid: String, val title: String)

    /** 标准模式：登录后可用，qn 生效（32=480P / 64=720P） */
    private fun standardPlayUrl(bvid: String, cid: String, qn: Int, cookie: String): String? {
        val url = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&qn=$qn&fnval=1&fourk=1"
        val json = getJson(url, cookie) ?: return null
        val data = json.getAsJsonObject("data") ?: return null
        // durl = 已合流的 mp4/flv，播放器能直接放；dash 是音视频分离流，这里不走
        val durl = data.getAsJsonArray("durl") ?: return null
        return durl.firstOrNull()?.asJsonObject?.get("url")?.asString
    }

    /** html5 模式：免登录、免 Referer，能拉但只有 320×240 */
    private fun html5PlayUrl(bvid: String, cid: String): String? {
        val url = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&qn=32&fnval=1&platform=html5"
        val json = getJson(url, null) ?: return null
        val durl = json.getAsJsonObject("data")?.getAsJsonArray("durl") ?: return null
        return durl.firstOrNull()?.asJsonObject?.get("url")?.asString
    }

    /**
     * 直链可播性探测：只拉 1KB。
     *
     * 为什么要这一步：标准模式的直链在**没有 Referer/cookie 的播放器**里可能返回 403，
     * 而播放器报错后我们只能走「换下一条源」，老人会白等一轮。先自己探一次，
     * 不行就直接走 html5 兜底，体感是「点开就有画面」。
     */
    private fun probePlayable(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Range", "bytes=0-1023")
            .build()
        client.newCall(request).execute().use { resp ->
            resp.isSuccessful || resp.code == 206
        }
    }.getOrDefault(false)

    /** 搜索视频（只取需要的字段） */
    private fun search(keyword: String, cookie: String?): List<VideoBrief> {
        val url = "https://api.bilibili.com/x/web-interface/search/type" +
            "?search_type=video&keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&page=1"
        val json = getJson(url, cookie) ?: return emptyList()
        val result = json.getAsJsonObject("data")?.getAsJsonArray("result") ?: return emptyList()
        return result.take(VIDEOS_PER_KEYWORD).mapNotNull { el ->
            val o = el.asJsonObject
            val bvid = o.get("bvid")?.asString ?: return@mapNotNull null
            val title = o.get("title")?.asString.orEmpty()
                .replace(Regex("<[^>]+>"), "")   // 搜索接口会给关键词加 <em> 高亮
                .trim()
            VideoBrief(bvid, title)
        }
    }

    /** 取分 P，把每个唱段变成一条可播放条目 */
    private fun expandVideo(v: VideoBrief, cookie: String?): List<TvChannel> {
        val json = getJson("https://api.bilibili.com/x/web-interface/view?bvid=${v.bvid}", cookie)
            ?: return emptyList()
        val data = json.getAsJsonObject("data") ?: return emptyList()
        val pages = data.getAsJsonArray("pages") ?: return emptyList()
        if (pages.size() == 0) return emptyList()

        // 单 P 视频：直接用视频标题当条目名
        if (pages.size() == 1) {
            val cid = pages[0].asJsonObject.get("cid")?.asString ?: return emptyList()
            return listOf(makeChannel(v.title, v.bvid, cid))
        }
        // 多 P 合集：每个 P 是一个独立唱段，条目名用分 P 名（比合集名更具体）
        return pages.take(PARTS_PER_VIDEO).mapNotNull { el ->
            val o = el.asJsonObject
            val cid = o.get("cid")?.asString ?: return@mapNotNull null
            val part = o.get("part")?.asString.orEmpty().trim()
            val name = part.ifBlank { v.title }
            makeChannel(name, v.bvid, cid)
        }
    }

    private fun makeChannel(name: String, bvid: String, cid: String): TvChannel =
        TvChannel(
            // id 必须唯一：不同合集里可能有同名唱段，用 bvid/cid 保证不互相覆盖
            id = "bili_${bvid}_$cid",
            name = name,
            group = GROUP_OPERA_SINGLE,
            urls = listOf("$SCHEME$bvid/$cid")
        )

    /** 点播唱段的分组名；[TvClassifier] 靠它归进「戏曲节目」 */
    const val GROUP_OPERA_SINGLE = "戏曲唱段"

    private fun getJson(url: String, cookie: String?): com.google.gson.JsonObject? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            // 关键：不带 Referer 会被拒（实测），带了就不需要 cookie
            .header("Referer", REFERER)
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", "SESSDATA=$cookie")
        }
        return client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: return null
            val trimmed = text.trimStart()
            // 风控时返回的是 HTML，JsonParser 会抛异常，这里先用首字符挡一道
            if (!trimmed.startsWith("{")) {
                Log.w(TAG, "非 JSON 响应（疑似风控）: ${trimmed.take(40)}")
                return null
            }
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        }
    }
}
