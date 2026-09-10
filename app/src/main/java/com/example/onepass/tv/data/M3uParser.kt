package com.example.onepass.tv.data

import com.example.onepass.tv.model.TvChannel

/**
 * 极简 m3u / m3u8 播放列表解析器，兼做**频道清洗**。
 *
 * 两件事：
 *   1. 解析 `#EXTINF` + 地址行；
 *   2. 把公开源里那些「能看但没用」的东西洗掉 —— 垃圾条目、重复分组名、重复频道。
 *
 * 最重要的是**同名频道合并**：一个频道会把多个源的地址收进 [TvChannel.urls]，
 * 播放页失败重试的逻辑就自然有了素材（这就是双源冗余）。
 */
object M3uParser {

    /** 匹配 `key="value"` 形式的属性 */
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /**
     * 公开源里的分组名五花八门，两个源对同一类内容用的名字都不一样（实测过）：
     * 一个叫「央视频道」另一个叫「央视」。不归一化的话频道页会出现两组一模一样的东西。
     * value 为 null 表示**整组丢弃**（垃圾分组）。
     */
    private val GROUP_ALIAS: Map<String, String?> = mapOf(
        "央视频道" to "央视",
        "央视" to "央视",
        "央视·电视剧" to "央视",
        "卫视频道" to "卫视",
        "卫视" to "卫视",
        "地方频道" to "地方台",
        "地方台" to "地方台",
        "地方" to "地方台",
        "电影频道" to "影视轮播",
        "港剧轮播" to "影视轮播",
        "地方影视剧场" to "影视剧场",
        "纪录频道" to "纪录",
        "儿童频道" to "少儿",
        "更新时间" to null
    )

    /** 源站里混进来的非频道条目（打赏、公告、更新标记之类） */
    private val JUNK_NAMES = setOf("支持作者", "更新时间", "公告", "打赏", "广告", "测试")

    /**
     * @param text     m3u/m3u8 文本内容
     * @param builtin  标记解析结果是否来自内置种子
     * @return 清洗、合并后的频道列表，保持文件中的出现顺序
     */
    fun parse(text: String, builtin: Boolean = false): List<TvChannel> {
        if (!text.contains("#EXTINF")) return emptyList()

        val merged = LinkedHashMap<String, MutableChannel>()
        var attrs: Map<String, String> = emptyMap()
        var title: String = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                attrs = ATTR.findAll(line)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                title = extractTitle(line)
                continue
            }
            if (line.startsWith("#")) continue
            // 只认 http/https（mms:// 等历史协议 Media3 不支持）
            if (!line.startsWith("http")) continue

            val displayName = (attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                ?: title.takeIf { it.isNotBlank() })?.trim()
            val rawGroup = attrs["group-title"]
            attrs = emptyMap()
            title = ""
            if (displayName.isNullOrEmpty() || displayName in JUNK_NAMES) continue

            val group = normalizeGroup(rawGroup) ?: continue

            val id = TvChannel.makeId(displayName)
            val ch = merged.getOrPut(id) {
                MutableChannel(id, displayName, group, null, builtin)
            }
            if (!ch.urls.contains(line)) ch.urls.add(line)
        }

        return merged.values.map {
            TvChannel(
                id = it.id,
                name = it.name,
                group = it.group,
                logo = it.logo,
                urls = it.urls.toList(),
                isBuiltin = it.builtin
            )
        }
    }

    /**
     * 分组名归一化。未在别名表里的保留原样（新分组会自然出现，不会被误删）。
     * @param raw #EXTINF 的 group-title，可能为空
     * @return null = 该分组需要整组丢弃
     */
    private fun normalizeGroup(raw: String?): String? {
        val name = raw?.trim().orEmpty().ifEmpty { "其他" }
        return if (GROUP_ALIAS.containsKey(name)) GROUP_ALIAS[name] else name
    }

    /**
     * 取 #EXTINF 行末尾的频道名。
     *
     * 不能简单用「最后一个逗号」切分——属性值里可能自带逗号（如 group-title="A,B"）。
     * 标准格式是 `<属性列表>,<标题>`，所以先找最后一个 `",` 作为分界；
     * 没有属性引号时退回第一个逗号。
     */
    private fun extractTitle(line: String): String {
        val afterAttrs = line.lastIndexOf("\",")
        return if (afterAttrs >= 0) {
            line.substring(afterAttrs + 2).trim()
        } else {
            line.substringAfter(',', "").trim()
        }
    }

    private class MutableChannel(
        val id: String,
        val name: String,
        val group: String,
        val logo: String?,
        val builtin: Boolean
    ) {
        val urls = mutableListOf<String>()
    }
}
