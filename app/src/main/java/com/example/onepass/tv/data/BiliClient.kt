package com.example.onepass.tv.data

import android.util.Log
import com.example.onepass.tv.model.TvChannel
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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
     * 搜索关键词。刻意用「名段」而不是「全场戏」：
     * 名段合集的分 P 就是一个个独立唱段（6~7 分钟），老人一段一段听正合适；
     * 全场戏动辄 2 小时，中间想换还得手动拖进度条。
     */
    private val KEYWORDS = listOf(
        "豫剧 名段", "京剧 名段", "黄梅戏 名段", "越剧 名段"
    )

    /** 每个关键词取前几个视频（调用量 = 关键词数 + 视频数，风控下要克制） */
    private const val VIDEOS_PER_KEYWORD = 4

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
     * 拉取戏曲唱段列表（搜索 → 展开分 P）。
     *
     * 会发起 `关键词数 + 视频数` 次请求，**只在缓存过期时调用**。
     * 任一环节失败都跳过该条，不抛异常（拿不到就少几条，不要让整个列表挂掉）。
     */
    suspend fun fetchOperaChannels(): List<TvChannel> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, TvChannel>()
        for (kw in KEYWORDS) {
            val videos = runCatching { search(kw) }.getOrElse {
                Log.w(TAG, "搜索失败「$kw」: ${it.javaClass.simpleName}")
                emptyList()
            }
            for (v in videos) {
                runCatching { expandVideo(v) }.getOrElse {
                    Log.w(TAG, "展开失败 ${v.bvid}: ${it.javaClass.simpleName}")
                    emptyList()
                }.forEach { ch -> out[ch.id] = ch }
            }
        }
        Log.d(TAG, "B站戏曲唱段: ${out.size} 条")
        out.values.toList()
    }

    /**
     * 把 `bili://<bvid>/<cid>` 换成真实 mp4 直链。
     * 失败返回 null（调用方按「换下一条源」处理）。
     */
    suspend fun resolvePlayUrl(biliUrl: String): String? = withContext(Dispatchers.IO) {
        val body = biliUrl.removePrefix(SCHEME)
        val parts = body.split('/')
        val bvid = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val cid = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val url = "https://api.bilibili.com/x/player/playurl" +
                "?bvid=$bvid&cid=$cid&qn=32&fnval=1&platform=html5"
            val json = getJson(url) ?: return@runCatching null
            val durl = json.getAsJsonObject("data")?.getAsJsonArray("durl")
            durl?.firstOrNull()?.asJsonObject?.get("url")?.asString
        }.getOrElse {
            Log.w(TAG, "解析播放地址失败 $bvid/$cid: ${it.javaClass.simpleName}")
            null
        }
    }

    // ------------------------------------------------------------------ 内部

    private data class VideoBrief(val bvid: String, val title: String)

    /** 搜索视频（只取需要的字段） */
    private fun search(keyword: String): List<VideoBrief> {
        val url = "https://api.bilibili.com/x/web-interface/search/type" +
            "?search_type=video&keyword=" + java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&page=1"
        val json = getJson(url) ?: return emptyList()
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
    private fun expandVideo(v: VideoBrief): List<TvChannel> {
        val json = getJson("https://api.bilibili.com/x/web-interface/view?bvid=${v.bvid}")
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

    private fun getJson(url: String): com.google.gson.JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            // 关键：不带 Referer 会被拒（实测），带了就不需要 cookie
            .header("Referer", REFERER)
            .build()
        return client.newCall(request).execute().use { resp ->
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
