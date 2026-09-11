package com.example.onepass.tv.player

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Media3（ExoPlayer）实现。
 *
 * 选它的原因（对比系统播放器）：
 *   - 原生支持 HLS(m3u8)，且自带 FLV 解封装 —— 港剧轮播那批 FLV 流也能播；
 *   - 完全在 App 内，UI 可自绘：大按钮、语音提示、看门狗、换源重试都能做；
 *   - 错误回调细（[PlaybackException.errorCodeName]），失败换源有依据可循。
 *
 * 界面控制条**关闭**（useController = false），所有交互由 [com.example.onepass.tv.ui.TvPlayerActivity]
 * 自己的大按钮接管 —— 这正是「内置播放器方便改造」的落点。
 *
 * ===== 真机踩坑记录（务必保留）=====
 *
 * 【坑 1】港剧 FLV 源跨协议重定向
 *   `live.metshop.top/huya/xxx` 返回 302，且从 https 跳到 http（跨协议）。
 *   Media3 默认的 DefaultHttpDataSource 出于安全**拒绝**跨协议跳转，
 *   抛 `HttpDataSource$InvalidResponseCodeException: Response code: 302`，
 *   表现为「这个台怎么都打不开」。
 *   → 解法：buildPlayer() 里 setAllowCrossProtocolRedirects(true)。
 *
 * 【坑 2】画面经常卡住
 *   直播源抖动时，ExoPlayer 默认策略是「等缓冲攒够 5 秒才续播」，老人看到的就是
 *   一卡就卡半天。而且默认不做追帧，卡一次延迟就永久累积，最后越落越远、彻底卡死。
 *   → 解法：① 自定义 LoadControl（卡顿后 2 秒就续播，起播门槛降到 1 秒）；
 *           ② 直播配置里开播放速度微调（0.97~1.03x），让播放器自己追直播进度；
 *           ③ seekToLiveEdge() 给上层做「卡死→跳回最新位置」的兜底。
 */
class Media3TvPlaybackEngine(private val context: Context) : TvPlaybackEngine {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var container: ViewGroup? = null
    private var stateListener: ((TvPlaybackState) -> Unit)? = null
    private var renderedVideo = false

    override fun attach(container: ViewGroup) {
        this.container = container

        val view = PlayerView(context).apply {
            useController = false                      // 关掉自带控制条，交给老人专属大按钮
            setShutterBackgroundColor(Color.BLACK)     // 起播前是黑的，不要白闪
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(view)
        playerView = view

        val exo = buildPlayer()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> emit(TvPlaybackState.Buffering)
                    Player.STATE_READY -> emit(TvPlaybackState.Playing)
                    Player.STATE_ENDED -> emit(TvPlaybackState.Ended)
                    else -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                renderedVideo = true
            }

            /**
             * 音轨一旦确定就检查一次编码。
             *
             * 安卓不带 MP2/MP1 解码器，遇到这类源是「有画面、没声音」，而且**不报错** ——
             * 老人只会以为电视坏了。这里主动识别并上报，让 [com.example.onepass.tv.ui.TvPlayerActivity]
             * 按「这条源不能用」处理（换下一条源，全都不行就换台）。
             */
            override fun onTracksChanged(tracks: Tracks) {
                val mime = tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                    ?.takeIf { it.length > 0 }
                    ?.getTrackFormat(0)
                    ?.sampleMimeType
                if (mime == MimeTypes.AUDIO_MPEG_L2 || mime == MimeTypes.AUDIO_MPEG_L1) {
                    Log.w(TAG, "音频编码 $mime 安卓不支持（MP2/MP1），上报换源")
                    emit(TvPlaybackState.NoAudio(mime))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                emit(TvPlaybackState.Failed(error.errorCodeName))
            }
        })
        view.player = exo
        player = exo
    }

    /**
     * 构造 ExoPlayer：换上「能跟随跨协议跳转」的 HTTP 数据源 + 面向直播的缓冲策略。
     *
     * 直播源现状：很多台 http/https 混着跳，还有部分 CDN 要 UA；
     * 缓冲策略则决定了「卡不卡」的体感，所以这两个都得手动配，不能用默认值。
     */
    private fun buildPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)   // ← 坑 1：允许 https→http 的 302
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(18_000)
            .setUserAgent(USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 【坑 3】「有些台没声 / 只有画面」的根因
        //   部分央视频道用厂商私有或冷门编码，硬解器建不出来时，ExoPlayer 默认直接判失败，
        //   表现就是黑屏或只有画面没声音。打开解码器回退后，会改用软解继续播 ——
        //   这是「没声」里最值得修的一类。
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        // 【坑 4】「看着看着突然没声」
        //   ExoPlayer 默认不管音频焦点：被别的 App（来电、微信语音）抢走焦点后，
        //   回到前台可能一直处于静音状态。这里把音频属性和焦点管理都交回播放器自己处理。
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(buildLoadControl())
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /**
     * 自定义缓冲控制（面向「公共直播源 + 老人」这个场景的取值）。
     *
     * 参数含义与取舍：
     *   - minBufferMs = 20s：保留一定缓冲余量扛住网络抖动，太小会频繁卡；
     *     （实测公共源尖峰抖动常有 10s 级，15s 偏紧，抬到 20s 明显更稳）
     *   - maxBufferMs = 50s：上限，防止在网络很好时无限缓冲、切台变慢；
     *   - bufferForPlaybackMs = 1s：**起播门槛**，攒够 1 秒就出画面（默认 2.5s）；
     *   - bufferForPlaybackAfterRebufferMs = 2s：**卡顿恢复门槛**（默认 5s）。
     *     这一条是「卡住」体感的最大来源 —— 从 5 秒降到 2 秒，肉眼感受就是从
     *     「卡半天」变成「闪一下就回来了」，代价只是偶尔少几帧。
     */
    private fun buildLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,   // minBufferMs
                50_000,   // maxBufferMs
                1_000,    // bufferForPlaybackMs
                2_000     // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .build()

    /**
     * 把直播配置塞进 MediaItem：开启播放速度微调（卡住自动追帧用）。
     *
     * 关于「音画不同步」的说明（写在这里免得以后又当 bug 排查）：
     *   ExoPlayer 的音视频共用同一条时钟，理论上不会自己把声画走散。真机上偶发的
     *   「声画错位」几乎都来自**源本身**（切片时间戳跳变、重编码时音视频轨没对齐）。
     *   能做的两件事已经在别处做了：
     *     ① 追帧速度窗口收窄到 0.98~1.02，减少频繁变速带来的观感抖动；
     *     ② 上层看门狗一旦判定卡死就 seekToLiveEdge()，丢掉积压的旧缓冲，
     *        错位往往跟着一起被清掉。
     */
    private fun mediaItemOf(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    // 允许播放器把速度在 0.98~1.02 之间微调来自动追直播进度：
                    // 落后了就略加速、超前了就略减速，避免延迟越积越多最后卡死。
                    //
                    // 刻意**不设** targetOffsetMs：目标延迟交给 ExoPlayer 按切片长度自动算
                    // （默认约 3 个切片）。写死一个值有坑 —— 若源是 10 秒切片而延迟设 6 秒，
                    // 播放进度永远追不上切片边界，反而变成持续卡顿。
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()

    override fun setStateListener(listener: (TvPlaybackState) -> Unit) {
        stateListener = listener
    }

    override fun play(url: String) {
        val exo = player ?: return
        renderedVideo = false
        emit(TvPlaybackState.Buffering)
        exo.setMediaItem(mediaItemOf(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
    }

    override fun resume() {
        player?.playWhenReady = true
    }

    override fun stop() {
        player?.stop()
        renderedVideo = false
    }

    override fun seekToLiveEdge() {
        val exo = player ?: return
        // 直播流里 seekToDefaultPosition 就是「跳到最新的可播位置」
        exo.seekToDefaultPosition()
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun release() {
        playerView?.player = null
        player?.release()
        player = null
        playerView?.let { view -> container?.removeView(view) }
        playerView = null
        container = null
        stateListener = null
    }

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun hasRenderedVideo(): Boolean = renderedVideo

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    /** 引擎内部状态统一走这里，保证回调都在主线程 */
    private fun emit(state: TvPlaybackState) {
        stateListener?.invoke(state)
    }

    companion object {
        private const val TAG = "Media3Engine"

        /** 桌面端常见 UA，规避个别 CDN 的空 UA 拦截 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
