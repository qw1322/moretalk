package com.example.onepass.tv.data

import com.example.onepass.tv.model.TvChannel

/**
 * 内置种子频道 —— 离线兜底。
 *
 * 这些地址来自 2026-09-10 的实测（河北移动家宽直连），是「拉不到远程清单 / 断网 / 远程源全部失效」
 * 时保证老人**至少还能看到 CCTV-8** 的最后一道防线。
 *
 * ⚠️ 直播源会失效，所以这里只是兜底，真正的清单靠 [TvRepository] 定期拉取远程源。
 * 修改这些地址前请先实测（脚本见项目里的 `电视剧源调研/`）。
 *
 * 维护提醒（戏曲源，2026-09-11 实测 · 凌晨 02:10）：
 *   · 真能看的戏曲台只剩「梨园」和「CCTV-11」两个，**梨园排在前面**：
 *     梨园 24 小时都有真节目；CCTV-11 深夜（实测 02:10）几个镜像都在播
 *     **央视自己的测试卡**（彩条 + 测试图），属于频道自身播出安排，不是 App 的问题。
 *     所以让梨园当默认，老人点进「戏曲节目」先看到的就是真戏。
 *   · CCTV-11 的镜像**只保留 H.264 的**：`112.27.5.218` 虽是同一路台，但视频是 **MPEG-2**，
 *     Android 不带 MPEG-2 解码器 → 表现是**黑屏**（其实是「连得上但解不出」，不是连不上），已剔除。
 *     剩下 4 条全是 h264；其中 `112.30.73.119` 音轨是 AAC，声画最稳，放第一位。
 *   · 失效 / 占位源一定要删（陕西秦腔 = 测试卡、移动戏曲 1041 = 5 个主机全 400），别留着占位。
 *
 * 教训：判断一个直播源能不能用，**只看播放列表 200、甚至只看切片能下都还不够** ——
 *   **还要看编码**（MPEG-2 在 Android 上直接黑屏）**和内容**（可能是测试卡 / 风景图）。
 *   本次三个坑全踩了一遍。本地核验脚本：`_tools/grab_c11.sh`（抓切片）+ ffmpeg 抽帧看图。
 */
object BuiltinChannelSeed {

    /** 以 m3u 文本形式维护，方便直接把实测可用的清单粘进来 */
    private const val SEED_M3U: String = """
#EXTM3U
#EXTINF:-1 tvg-name="CCTV-8" group-title="央视·电视剧",CCTV-8 电视剧频道
http://124.165.251.82:85/tsfile/live/0008_1.m3u8?key=txiptv&playlive=1&authid=0
http://183.203.166.28:9003/hls/15/index.m3u8
#EXTINF:-1 tvg-name="CCTV-1" group-title="央视·电视剧",CCTV-1 综合
http://74.91.26.218:82/live/cctv1hd.m3u8
#EXTINF:-1 tvg-name="梨园" group-title="戏曲",梨园频道
https://dxtx.hntv.tv/live/lypd.m3u8?txSecret=10c771842a0be59e8b575d765173ec96&txTime=7B923E0A&wsSecret=41543190cdf375f1c73207222bb96542&wsTime=1769313549
#EXTINF:-1 tvg-name="CCTV11" group-title="戏曲",CCTV-11 戏曲频道
http://112.30.73.119:9901/tsfile/live/0011_1.m3u8?key=txiptv&playlive=1&authid=0
http://119.39.9.8:9901/tsfile/live/0011_1.m3u8?key=txiptv&playlive=1&authid=0
http://218.13.170.98:9901/tsfile/live/0011_1.m3u8?key=txiptv&playlive=1&authid=0
http://61.136.172.236:9901/tsfile/live/0011_1.m3u8?key=txiptv&playlive=1&authid=0
#EXTINF:-1 tvg-name="东阳影视生活" group-title="地方影视剧场",东阳影视生活
http://l.cztvcloud.com/channels/lantian/SXdongyang1/720p.m3u8
#EXTINF:-1 tvg-name="浙江教科影视" group-title="地方影视剧场",浙江教科影视
http://ali-vl.cztv.com/channels/lantian/channel004/360p.m3u8
#EXTINF:-1 tvg-name="山西影视" group-title="地方影视剧场",山西影视
http://183.203.166.28:9003/hls/28/index.m3u8
#EXTINF:-1 tvg-name="哈尔滨影视" group-title="地方影视剧场",哈尔滨影视
http://stream.hrbtv.net/yspd/sd/live.m3u8?zheild
#EXTINF:-1 tvg-name="四川科教" group-title="地方影视剧场",四川科教
http://182.150.115.21:8030/pcgacg/pcgacg_0.m3u8
#EXTINF:-1 tvg-name="南国都市" group-title="地方影视剧场",南国都市
https://tencentplay.gztv.com/live/nanguodushi.m3u8?txSecret=550af55c0ea34ce492748481415b6dfa&txTime=1903e7b17de
#EXTINF:-1 tvg-name="浙江卫视" group-title="卫视",浙江卫视
http://ali-xwl.cztv.com/live/channel011080Plxw.m3u8
#EXTINF:-1 tvg-name="东方卫视4K" group-title="卫视",东方卫视 4K
https://bp-resource-dfl.bestv.cn/148/3/video.m3u8
#EXTINF:-1 tvg-name="河南卫视" group-title="卫视",河南卫视
http://183.203.166.28:9003/hls/37/index.m3u8
#EXTINF:-1 tvg-name="倚天屠龙记" group-title="港剧轮播",倚天屠龙记
https://live.metshop.top/huya/23734246
#EXTINF:-1 tvg-name="射雕英雄传" group-title="港剧轮播",射雕英雄传
https://live.metshop.top/huya/23824164
#EXTINF:-1 tvg-name="笑傲江湖" group-title="港剧轮播",笑傲江湖
https://live.metshop.top/huya/23865142
#EXTINF:-1 tvg-name="创世纪" group-title="港剧轮播",创世纪
https://live.metshop.top/huya/29465852
#EXTINF:-1 tvg-name="大时代" group-title="港剧轮播",大时代
https://live.metshop.top/huya/23865161
#EXTINF:-1 tvg-name="妙手仁心" group-title="港剧轮播",妙手仁心
https://live.metshop.top/huya/29465853
#EXTINF:-1 tvg-name="鉴证实录" group-title="港剧轮播",鉴证实录
https://live.metshop.top/huya/23903183
#EXTINF:-1 tvg-name="陀枪师姐" group-title="港剧轮播",陀枪师姐
https://live.metshop.top/huya/29465857
#EXTINF:-1 tvg-name="法政先锋" group-title="港剧轮播",法政先锋
https://live.metshop.top/huya/23863804
#EXTINF:-1 tvg-name="洗冤录" group-title="港剧轮播",洗冤录
https://live.metshop.top/huya/29465851
#EXTINF:-1 tvg-name="刑事侦缉档案" group-title="港剧轮播",刑事侦缉档案
https://live.metshop.top/huya/29465856
#EXTINF:-1 tvg-name="寻秦记" group-title="港剧轮播",寻秦记
https://live.metshop.top/huya/29465848
#EXTINF:-1 tvg-name="大唐双龙传" group-title="港剧轮播",大唐双龙传
https://live.metshop.top/huya/29465850
#EXTINF:-1 tvg-name="封神榜" group-title="港剧轮播",封神榜
https://live.metshop.top/huya/29465849
#EXTINF:-1 tvg-name="金枝欲孽" group-title="港剧轮播",金枝欲孽
https://live.metshop.top/huya/29465847
#EXTINF:-1 tvg-name="楚汉骄雄" group-title="港剧轮播",楚汉骄雄
https://live.metshop.top/huya/29465854
#EXTINF:-1 tvg-name="西游记2" group-title="港剧轮播",西游记2
https://live.metshop.top/huya/29465859
#EXTINF:-1 tvg-name="活佛济公3" group-title="港剧轮播",活佛济公3
https://live.metshop.top/huya/23903123
#EXTINF:-1 tvg-name="无心法师" group-title="港剧轮播",无心法师
https://live.metshop.top/huya/30080233
#EXTINF:-1 tvg-name="野蛮亲家" group-title="港剧轮播",野蛮亲家
https://live.metshop.top/huya/23863796
"""

    /**
     * 默认开机频道：老人最熟、内容最正规的 CCTV-8。
     * 值必须与 [TvChannel.makeId] 的结果一致（= 台名小写去空格去连字符，所以是 "cctv8"）。
     */
    const val DEFAULT_CHANNEL_ID: String = "cctv8"

    private val cache: List<TvChannel> by lazy { M3uParser.parse(SEED_M3U, builtin = true) }

    fun channels(): List<TvChannel> = cache
}
