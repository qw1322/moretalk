package com.example.onepass.tv.player

import android.view.ViewGroup

/**
 * 播放状态。UI 只认这五种，不关心底层是 Media3 还是别的引擎。
 */
sealed class TvPlaybackState {
    /** 还没开始播 */
    object Idle : TvPlaybackState()

    /** 正在缓冲（老人看到的是「正在打开…」） */
    object Buffering : TvPlaybackState()

    /** 出画面了 */
    object Playing : TvPlaybackState()

    /** 流结束了（直播源一般不会走到这，走到了就当作要换台） */
    object Ended : TvPlaybackState()

    /** 播放失败，[reason] 用于日志，不直接展示给老人 */
    class Failed(val reason: String) : TvPlaybackState()

    /**
     * 画面能出、但**音频安卓解不了**（实测是 MP2 / MP1）。
     *
     * 为什么要单独报这一种：安卓平台**不带 MP2 解码器**（MPEG-1 Layer II），
     * 而部分 IPTV 源（实测 CCTV-3 的某条源就是）音轨用 MP2 —— 表现是
     * 「连得上、有画面、**一点声音都没有**」，比黑屏更隐蔽：
     * 播放器不报错，老人只会以为电视坏了。
     * 有了这个状态，上层就能像处理「源失效」一样自动换源/换台。
     */
    class NoAudio(val mime: String?) : TvPlaybackState()
}

/**
 * 播放引擎抽象。
 *
 * 为什么要有这层接口：老人模式的诉求是「界面可改、按钮可改、行为可改」。
 * 把播放器换掉（Media3 ↔ ijkplayer ↔ 自研）不应该牵动一行 UI 代码。
 * 当前唯一实现是 [Media3TvPlaybackEngine]。
 */
interface TvPlaybackEngine {

    /** 把播放画面挂到容器里（由引擎自己创建 PlayerView 并 addView） */
    fun attach(container: ViewGroup)

    /** 状态回调，**在主线程**触发 */
    fun setStateListener(listener: (TvPlaybackState) -> Unit)

    /** 起播一个地址（会顶掉当前播放内容） */
    fun play(url: String)

    fun pause()
    fun resume()
    fun stop()
    fun release()

    /**
     * 卡死恢复：不换源、不换台，直接跳回**直播最新位置**重新缓冲。
     *
     * 为什么需要它：直播源卡住有两种 —— 一种是源真废了（走换源/换台），
     * 另一种只是「网络抖了一下、缓冲被抽干、播放器还停在旧位置等数据」。
     * 后者如果也按换台处理，老人会看到画面莫名其妙跳走；
     * 跳到直播边缘往往一秒就恢复，体感是「卡了一下自己好了」。
     */
    fun seekToLiveEdge()

    fun isPlaying(): Boolean

    /** 是否已经出过第一帧画面。用于「卡在缓冲不报错」的看门狗判断 */
    fun hasRenderedVideo(): Boolean

    /** 0f ~ 1f，控制播放音量（独立于系统媒体音量，老人场景会用到） */
    fun setVolume(volume: Float)
}
