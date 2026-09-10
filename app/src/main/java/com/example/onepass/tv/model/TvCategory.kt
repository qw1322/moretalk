package com.example.onepass.tv.model

/**
 * 看电视的**节目大分类**。
 *
 * 为什么要有分类：实测两个公开源合并后有一百多个频道，平铺给老人等于没法选。
 * 按「老人想找什么」拆成四类就够了，而且每一类的语义老人都能秒懂：
 *   - 央视频道：打开就有，最正规（央视 + 卫视）
 *   - 现代节目：时装剧、电影那类
 *   - 古装节目：武侠、古装剧
 *   - 戏曲节目：京剧越剧黄梅戏
 *
 * [id] 会写进 Intent / SharedPreferences，**不要改名**；[displayName] 是给老人看的，随便改。
 */
enum class TvCategory(val id: String, val displayName: String) {
    CCTV("cctv", "央视频道"),
    MODERN("modern", "现代节目"),
    COSTUME("costume", "古装节目"),
    OPERA("opera", "戏曲节目");

    companion object {
        /** 频道页/播放页拿到的都是字符串 id，这里做一次安全解析（脏数据回退央视） */
        fun fromId(id: String?): TvCategory =
            entries.firstOrNull { it.id == id } ?: CCTV

        /** 分类页的展示顺序 */
        val ordered: List<TvCategory> = listOf(CCTV, MODERN, COSTUME, OPERA)
    }
}
