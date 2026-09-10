package com.example.onepass.tv.model

/**
 * 一个电视频道。
 *
 * 设计要点：**一个频道可以挂多个源地址**（[urls]），播放失败时按顺序自动重试，
 * 这就是「双源冗余」——上一个台看不成就试备用地址，而不是直接给老人报错。
 *
 * @param id      稳定标识（分组+名称生成，用于排序、记住上次播放的台）
 * @param name    频道显示名（会给老人 TTS 播报，所以尽量用电视台本身的名字）
 * @param group   分组名（央视 / 卫视 / 地方影视剧场 / 港剧轮播 …）
 * @param logo    台标地址，可空
 * @param urls    源地址列表，按优先级排列，[TvChannel.urls] 第一个为主源
 * @param isBuiltin 是否来自 App 内置种子（远程清单拉取失败时的离线兜底）
 */
data class TvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val urls: List<String> = emptyList(),
    val isBuiltin: Boolean = false
) {
    /** 没有源地址的频道不参与播放（远程清单里偶尔会出现空条目） */
    val playable: Boolean get() = urls.isNotEmpty()

    companion object {
        /**
         * 频道身份**只由台名决定，不含分组**。
         *
         * 这是踩过坑才改的：一开始用「分组+台名」做 id，结果同一个 CCTV-8 在 A 源叫
         * 「央视频道|CCTV8」、在 B 源叫「央视|cctv8」，被当成两个频道，
         * 多源冗余完全失效（实测 671 个频道里近一半是这种重复）。
         * 改成只看台名后，同名频道的多个源会合并进同一个 [TvChannel.urls]。
         *
         * 还要去掉**连字符和空格**：内置种子写的是「CCTV-8」，远程源写的是「CCTV8」，
         * 不归一化的话同一个台会同时冒出两条（这个坑第二次踩了）。
         */
        fun makeId(name: String): String = name.trim()
            .lowercase()
            .replace(" ", "")
            .replace("\u3000", "")
            .replace("-", "")
    }
}
