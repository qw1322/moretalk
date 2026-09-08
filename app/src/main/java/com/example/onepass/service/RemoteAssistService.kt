package com.example.onepass.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.onepass.R
import com.example.onepass.utils.Logger
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 远程协助（P1/P2 局域网版）：
 * - MediaProjection 录屏 → MJPEG 流（http://<手机IP>:8890/stream.mjpeg）
 * - 家属浏览器打开 http://<手机IP>:8890 实时观看
 * - 点击画面 → 无障碍手势注入实现远程控制（/tap?x=&y=）
 */
class RemoteAssistService : Service() {

    companion object {
        private const val TAG = "RemoteAssist"
        const val ACTION_START = "com.example.onepass.action.REMOTE_ASSIST_START"
        const val ACTION_STOP = "com.example.onepass.action.REMOTE_ASSIST_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PORT = 8890
        const val WS_PORT = 8891
        // 公网中继（家属 VPS）：手机隧道主动出站连接，绕过 CGNAT
        const val VPS_HOST = "216.23.93.14"
        const val VPS_PORT = 8899
        /** H.264 硬编码实验通道开关（MediaCodec → /h264 → 家属页 WebCodecs 解码），默认开启 */
        const val KEY_H264 = "remote_assist_h264"
        /** 主配置（与抖音安心刷等共用） */
        private const val PREF_MAIN = "OnePassPrefs"
        private const val CHANNEL_ID = "remote_assist"
        private const val NOTIFICATION_ID = 8890
        private const val FRAME_INTERVAL_MS = 100L
        private const val JPEG_QUALITY = 60
        // 0.5x 分辨率：帧体积与编码耗时减半，降低端到端延迟（不影响帧率上限）
        private const val CAPTURE_SCALE = 0.5f

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 公网隧道房间号（家属浏览器经中继访问用）；持久化为固定值，家属可用固定网址访问 */
        @Volatile
        var tunnelRoom: String = "MT2024"
            internal set

        private const val PREFS_NAME = "remote_assist_prefs"
        private const val KEY_ROOM = "tunnel_room"

        /**
         * 加载固定房间号：首次启动生成并持久化，之后每次启动返回同一值。
         * 这样家属可长期用固定网址 http://VPS_HOST:VPS_PORT/?room=XXX 访问，无需每次查号。
         */
        fun loadOrCreateRoom(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var room = prefs.getString(KEY_ROOM, null)
            if (room.isNullOrBlank()) {
                room = "MT${(100000..999999).random()}"
                prefs.edit().putString(KEY_ROOM, room).apply()
            }
            tunnelRoom = room
        }

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, RemoteAssistService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteAssistService::class.java))
        }

        /** 获取本机局域网 IPv4 地址 */
        fun getLocalIpAddress(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.filter { it.isUp && !it.isLoopback }
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var h264Streamer: H264Streamer? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var server: RemoteAssistServer? = null
    private val running = AtomicBoolean(false)
    private var screenWakeLock: android.os.PowerManager.WakeLock? = null

    @Volatile
    internal var latestJpeg: ByteArray? = null

    internal var screenWidth = 0
    internal var screenHeight = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    @Volatile
    internal var lastFrameAt = 0L
    private var imageArriveCount = 0L

    // WebSocket 控制服务（LAN）与公网「上传+轮询」客户端
    private var wsServer: NanoWsdServer? = null
    private val wsHandler = RemoteAssistWsHandler(this)
    private var tunnelOkHttp: OkHttpClient? = null
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private val pollRunning = AtomicBoolean(false)
    /** 是否已上报屏幕物理分辨率（家属网页据此换算点击坐标） */
    @Volatile
    private var metaUploaded = false
    /** 上次上报的帧（避免每轮重复 POST 相同帧，仅新帧才上传） */
    @Volatile
    private var lastUploadedJpeg: ByteArray? = null

    /** UPnP 映射后的公网访问地址（若成功） */
    @Volatile
    var publicUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** H.264 重建限流：60s 内最多尝试 3 次，仍无输出则放弃 H.264 回退 JPEG（实验功能不拖垮远程协助） */
    private var h264RebuildCount = 0
    private var h264RebuildWindowStart = 0L

    // 看门狗：采集偶发停摆时重建。停滞阈值 1500ms + 重建冷却 1500ms：兼容 realme
    // （重建后约1s出帧）与一加/ColorOS（虚拟显示创建后出首帧可能较慢，若冷却太短会陷入
    // "重建→未出帧→再重建"的风暴，永远无帧）。H.264 模式按编码输出时间戳检测并重建编码链路。
    private val watchdogRunnable = object : Runnable {
        private var lastRebuildAt = 0L
        override fun run() {
            if (running.get() && mediaProjection != null) {
                val now = System.currentTimeMillis()
                val streamer = h264Streamer
                val stall = if (streamer != null) now - streamer.lastOutputAt else now - lastFrameAt
                if (stall > 1500 && now - lastRebuildAt > 1500) {
                    lastRebuildAt = now
                    if (streamer != null) {
                        // H.264 链路限流重建：多次重建仍无输出 → 判定该设备 H.264 不可用，回退 JPEG 保底
                        if (now - h264RebuildWindowStart > 60_000) {
                            h264RebuildWindowStart = now
                            h264RebuildCount = 0
                        }
                        h264RebuildCount++
                        if (h264RebuildCount > 3) {
                            Logger.w(TAG, "H.264 链路多次重建失败（${h264RebuildCount} 次），回退 JPEG 帧流")
                            switchToJpeg()
                        } else {
                            Logger.w(TAG, "采集停滞 ${stall}ms，重建 H.264 编码链路（第 $h264RebuildCount 次）")
                            runCatching { streamer.rebuild() }
                        }
                    } else {
                        Logger.w(TAG, "采集停滞 ${stall}ms，重建虚拟显示")
                        runCatching { createCapture(captureWidth, captureHeight, captureDensity) }
                    }
                }
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 加载固定房间号（首次启动生成并持久化到本地，之后每次启动不变，
        // 家属可用固定网址 http://VPS:8899/?room=XXX 长期访问，无需每次查房间号）
        loadOrCreateRoom(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            // 防堆积：服务已在运行时的重复启动请求直接忽略，避免重复 setupProjection。
            // （用户多次点击"远程协助"瓦片/设置按钮时，多次 startForegroundService 会反复进入此分支。）
            if (running.get() && isRunning) {
                Logger.d(TAG, "远程协助已在运行，忽略重复启动请求")
                return START_NOT_STICKY
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                Logger.d(TAG, "前台服务已启动")
                // 远程协助期间保持屏幕常亮，避免屏幕休眠/锁定导致镜像采集中断
                runCatching {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    screenWakeLock = powerManager.newWakeLock(
                        android.os.PowerManager.FULL_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "RemoteAssist:screen"
                    ).apply { acquire() }
                }
                // 注意：RESULT_OK 的值为 -1，不能用 -1 作哨兵
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                Logger.d(TAG, "resultCode=$resultCode resultData=${resultData != null}")
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    setupProjection(resultCode, resultData)
                    // 注意：投影授权数据只能消费一次，系统复活时 token 已失效，不可用 START_STICKY
                    return START_NOT_STICKY
                }
                Logger.e(TAG, "缺少录屏授权数据")
                stopSelf()
                return START_NOT_STICKY
            } catch (e: Exception) {
                Logger.e(TAG, "启动异常: ${e.message}", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
        pollRunning.set(false)
        pollHandler?.removeCallbacksAndMessages(null)
        runCatching { h264Streamer?.stop() }
        h264Streamer = null
        runCatching { wsServer?.stop() }
        wsServer = null
        pollThread?.quitSafely()
        pollThread = null
        runCatching { tunnelOkHttp?.dispatcher?.executorService?.shutdown() }
        tunnelOkHttp = null
        runCatching { screenWakeLock?.let { if (it.isHeld) it.release() } }
        screenWakeLock = null
        runCatching { server?.stop() }
        server = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        latestJpeg = null
        super.onDestroy()
    }

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        // 防重复初始化：任何二次 setupProjection 都直接返回，避免叠加采集/端口/隧道
        if (running.get()) {
            Logger.w(TAG, "setupProjection 已被调用，忽略重复初始化")
            return
        }
        try {
            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            Logger.d(TAG, "屏幕 ${screenWidth}x$screenHeight density=${metrics.densityDpi}")

            val width = (screenWidth * CAPTURE_SCALE).toInt().coerceAtLeast(320)
            val height = (screenHeight * CAPTURE_SCALE).toInt()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Logger.e(TAG, "MediaProjection 获取失败")
                stopSelf()
                return
            }
            mediaProjection = projection
            Logger.d(TAG, "MediaProjection 已获取")
            // 监听系统对投影会话的干预（onStop = 系统强制停止投影）
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Logger.w(TAG, "系统调用 MediaProjection.onStop —— 投影被系统停止")
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    Logger.d(TAG, "投影内容尺寸变化 ${width}x$height")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    Logger.d(TAG, "投影内容可见性变化 isVisible=$isVisible")
                }
            }, mainHandler)
            captureWidth = width
            captureHeight = height
            captureDensity = metrics.densityDpi

            // 必须先置 running 再建采集：首帧可能在 running=false 时到达而被丢弃
            running.set(true)
            isRunning = true
            ensureTunnelOkHttp()
            val useH264 = getSharedPreferences(PREF_MAIN, MODE_PRIVATE).getBoolean(KEY_H264, true)
            if (useH264) {
                // H.264 硬编码实验通道：虚拟显示 Surface 直连 MediaCodec（零拷贝），
                // 输出分片由 H264Streamer 合批上传 VPS /h264，家属浏览器 WebCodecs 解码。
                val s = H264Streamer(
                    projection, width, height, metrics.densityDpi,
                    tunnelOkHttp!!, tunnelRoom, VPS_HOST, VPS_PORT
                )
                h264Streamer = s
                s.start()
                if (!s.isAlive) {
                    // 设备不支持/编解码器异常：立即回退 JPEG，不把远程协助拖进重建死循环
                    Logger.w(TAG, "H.264 编码器不可用，回退 JPEG 帧流")
                    h264Streamer = null
                    createCapture(width, height, metrics.densityDpi)
                }
            } else {
                createCapture(width, height, metrics.densityDpi)
            }

            server = RemoteAssistServer(PORT, this)
            runCatching { server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onFailure { e ->
                    Logger.e(TAG, "HTTP 服务启动失败: ${e.message}")
                    stopSelf()
                }
            Logger.d(TAG, "HTTP 服务已启动 :$PORT")
            // WebSocket 控制服务（LAN 页面 + 控制）
            startWsServer()
            // 公网通道：手机上传帧到 VPS + 轮询拉取家属指令（短连接，规避 CGNAT 长连接被重置）
            startPollUpload()
            // v1.9.2 测试：默认直启 H.264 硬编码流（跳过 mode 协商，验证采集帧率）
            mainHandler.post {
                if (running.get() && h264Streamer == null) switchToH264()
            }
            // 尝试 UPnP 端口映射（NAT 外网直连）
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                UpnpManager.mapPort(PORT, localIp) { result ->
                    Logger.d(TAG, "UPnP: ${result.message}，公网地址: ${result.publicUrl ?: "无"}")
                    publicUrl = result.publicUrl
                }
            }
            mainHandler.postDelayed(watchdogRunnable, 1000)
        } catch (e: Exception) {
            Logger.e(TAG, "setupProjection 异常: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * 创建/重建采集链路（ImageReader + 虚拟显示）。可反复调用以自愈采集停滞。
     */
    private fun createCapture(width: Int, height: Int, densityDpi: Int) {
        runCatching { imageReader?.close() }
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        captureThread?.quitSafely()
        captureThread = HandlerThread("RemoteAssistCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader?.setOnImageAvailableListener({ reader ->
            imageArriveCount++
            if (imageArriveCount <= 5) {
                Logger.d(TAG, "图像到达 #$imageArriveCount")
            }
            if (!running.get()) return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
            lastFrameAt = now
            val image = reader.acquireLatestImage()
            if (image == null) {
                if (imageArriveCount <= 5) Logger.w(TAG, "acquireLatestImage 返回 null")
                return@setOnImageAvailableListener
            }
            runCatching {
                val jpeg = imageToJpeg(image, JPEG_QUALITY)
                if (jpeg != null && jpeg.isNotEmpty()) {
                    latestJpeg = jpeg
                    if (imageArriveCount <= 5) Logger.d(TAG, "JPEG 帧 ${jpeg.size}B")
                } else {
                    if (imageArriveCount <= 5) Logger.w(TAG, "JPEG 为空，原始尺寸 ${image.width}x${image.height}")
                }
            }.onFailure { e -> Logger.w("$TAG 帧处理失败: ${e.message}") }
            image.close()
        }, captureHandler)

        runCatching { virtualDisplay?.release() }
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteAssistDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        // 注意：不要在这里初始化 lastFrameAt！首帧可能与节流时间戳撞车被丢弃
        Logger.d(TAG, "虚拟显示已重建 ${width}x$height")
    }

    // ==================== WebSocket 控制（LAN + 公网隧道） ====================

    private fun startWsServer() {
        wsServer = NanoWsdServer(this, wsHandler)
        runCatching { wsServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            .onFailure { e -> Logger.e(TAG, "WS 服务启动失败: ${e.message}") }
        Logger.d(TAG, "WS 控制服务已启动 :$WS_PORT，房间 $tunnelRoom")
    }

    /**
     * 公网通道 v2：「手机上传帧 + 轮询指令」（HTTP 短连接）。
     * 每轮循环：若有新帧则 POST /frame 上传；GET /cmd 拉取家属指令执行。
     * 短连接单次请求/响应，天然规避 CGNAT 长连接被运营商/路由器周期性重置的问题。
     */
    private fun ensureTunnelOkHttp() {
        if (tunnelOkHttp == null) {
            tunnelOkHttp = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * 公网通道 v2：「手机上传帧 + 轮询指令」（HTTP 短连接）。
     * 每轮循环：若有新帧则 POST /frame 上传；GET /cmd 拉取家属指令执行。
     * 短连接单次请求/响应，天然规避 CGNAT 长连接被运营商/路由器周期性重置的问题。
     * H.264 模式下帧上传由 H264Streamer 走 /h264 合批，此处仅 meta + 指令轮询。
     */
    private fun startPollUpload() {
        if (pollRunning.get()) return
        pollRunning.set(true)
        try {
            ensureTunnelOkHttp()
            pollThread?.quitSafely()
            pollThread = HandlerThread("RemoteAssistPoll").apply { start() }
            pollHandler = Handler(pollThread!!.looper)
            pollHandler?.post(::pollLoop)
            Logger.d(TAG, "公网上传+轮询已启动，房间 $tunnelRoom")
        } catch (e: Exception) {
            pollRunning.set(false)
            Logger.e(TAG, "上传轮询启动失败: ${e.message}")
        }
    }

    /** 每轮：上传新帧 + 拉取并执行家属指令，然后调度下一轮（约 150ms） */
    private fun pollLoop() {
        if (!pollRunning.get() || !running.get()) return
        try {
            // 0) 首次上报屏幕物理分辨率（家属网页据此把画面坐标换算成真实像素）
            if (!metaUploaded && screenWidth > 0 && screenHeight > 0) {
                uploadMeta()
                metaUploaded = true
            }
            // 1) 上传最新帧（仅当有新帧；H.264 模式由 H264Streamer 独立上传 /h264）
            val jpeg = latestJpeg
            if (h264Streamer == null && jpeg != null && jpeg !== lastUploadedJpeg) {
                uploadFrame(jpeg)
                lastUploadedJpeg = jpeg
            }
            // 2) 拉取指令
            pollCommands()
        } catch (e: Exception) {
            Logger.w("$TAG 轮询异常: ${e.message}")
        }
        pollHandler?.postDelayed(::pollLoop, 150)
    }

    /**
     * 家属页能力协商：在 H.264 硬编码流与 JPEG 帧流之间实时切换。
     * 由 wsHandler 的 "mode" 指令触发（页面按 WebCodecs 可用性上报），主线程串行执行避免并发建链。
     * 防抖：同方向重复指令忽略；异方向切换最小间隔 3s（多标签页/页面重复加载的指令不会再互相打架）。
     */
    @Volatile
    private var lastModeSwitchAt = 0L

    fun switchMode(h264: Boolean) {
        mainHandler.post {
            if (!running.get()) return@post
            if (h264 && h264Streamer != null) return@post          // 已是 H.264，忽略重复指令
            if (!h264 && h264Streamer == null) return@post         // 已是 JPEG，忽略重复指令
            val now = System.currentTimeMillis()
            if (now - lastModeSwitchAt < 3000) {
                Logger.d(TAG, "模式切换防抖中，忽略 mode=${if (h264) "h264" else "mjpeg"} 指令")
                return@post
            }
            lastModeSwitchAt = now
            if (h264) switchToH264() else switchToJpeg()
        }
    }

    /** H.264 模式（MediaCodec Surface 直连虚拟显示，硬编码） */
    private fun switchToH264() {
        if (h264Streamer != null) return
        Logger.d(TAG, "家属页支持 WebCodecs，切换 H.264 硬编码流")
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        ensureTunnelOkHttp()
        val s = H264Streamer(
            mediaProjection!!, captureWidth, captureHeight, captureDensity,
            tunnelOkHttp!!, tunnelRoom, VPS_HOST, VPS_PORT
        )
        h264Streamer = s
        s.start()
        if (!s.isAlive) {
            // 编码器起不来（设备兼容问题）：清字段回退 JPEG，避免"已启动"假象卡死后续 mode 指令
            Logger.w(TAG, "H.264 编码器不可用，回退 JPEG 帧流")
            h264Streamer = null
            createCapture(captureWidth, captureHeight, captureDensity)
            return
        }
        h264RebuildWindowStart = System.currentTimeMillis()
        h264RebuildCount = 0
    }

    /** JPEG 模式（ImageReader + 软编码，兼容兜底） */
    private fun switchToJpeg() {
        if (h264Streamer == null) {
            // 看门狗回退路径：H.264 未启动时也要确保 JPEG 采集存在
            if (imageReader == null) createCapture(captureWidth, captureHeight, captureDensity)
            return
        }
        Logger.d(TAG, "切换 JPEG 帧流")
        runCatching { h264Streamer?.stop() }
        h264Streamer = null
        createCapture(captureWidth, captureHeight, captureDensity)
    }

    /** 上报真实屏幕物理分辨率，供家属网页做点击/滑动坐标换算 */
    private fun uploadMeta() {
        runCatching {
            val body = org.json.JSONObject()
                .put("w", screenWidth)
                .put("h", screenHeight)
                .toString()
            val req = Request.Builder()
                .url("http://$VPS_HOST:$VPS_PORT/meta?room=$tunnelRoom")
                .post(okhttp3.RequestBody.create(null, body))
                .build()
            tunnelOkHttp?.newCall(req)?.execute()?.use { resp ->
                if (resp.isSuccessful) {
                    Logger.d(TAG, "已上报分辨率 ${screenWidth}x$screenHeight")
                } else {
                    Logger.w("$TAG 上报分辨率失败 HTTP ${resp.code}")
                }
            }
        }.onFailure { e ->
            Logger.w("$TAG 上报分辨率异常: ${e.message}")
        }
    }

    private fun uploadFrame(jpeg: ByteArray) {
        runCatching {
            val req = Request.Builder()
                .url("http://$VPS_HOST:$VPS_PORT/frame?room=$tunnelRoom")
                .post(okhttp3.RequestBody.create(null, jpeg))
                .build()
            tunnelOkHttp?.newCall(req)?.execute()?.use { resp ->
                if (!resp.isSuccessful) Logger.w("$TAG 上传帧失败 HTTP ${resp.code}")
            }
        }.onFailure { e ->
            // 网络抖动属正常，静默跳过，下一轮再试
            if (System.currentTimeMillis() % 30_000 < 300) {
                Logger.w("$TAG 上传帧异常: ${e.message}")
            }
        }
    }

    /** 拉取家属指令并执行（tap/swipe 等），复用 wsHandler 的指令逻辑 */
    private fun pollCommands() {
        runCatching {
            val req = Request.Builder()
                .url("http://$VPS_HOST:$VPS_PORT/cmd?room=$tunnelRoom")
                .get()
                .build()
            tunnelOkHttp?.newCall(req)?.execute()?.use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                if (body.isBlank() || body == "[]") return
                Logger.d(TAG, "拉取到指令: ${body.take(120)}")
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val cmd = arr.optString(i)
                    if (cmd.isNotBlank()) {
                        // 丢弃 sink：指令执行结果不回传（回传对 HTTP 轮询无意义）
                        wsHandler.handle(object : RemoteAssistWsHandler.WsSink {
                            override fun sendText(text: String) {}
                            override fun sendBinary(bytes: ByteArray) {}
                        }, cmd)
                    }
                }
            }
        }.onFailure { e ->
            if (System.currentTimeMillis() % 30_000 < 300) {
                Logger.w("$TAG 拉指令异常: ${e.message}")
            }
        }
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        val t0 = System.currentTimeMillis()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        Logger.d(TAG, "imageToJpeg 开始 ${image.width}x${image.height} bufferRemaining=${buffer.remaining()}")
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        val baos = ByteArrayOutputStream()
        val ok = cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val t1 = System.currentTimeMillis()
        Logger.d(TAG, "imageToJpeg 完成 ok=$ok size=${baos.size()} 耗时${t1 - t0}ms")
        bitmap.recycle()
        cropped.recycle()
        if (!ok) {
            Logger.w(TAG, "JPEG 压缩失败（返回 false）")
            return null
        }
        return baos.toByteArray()
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程协助",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ip = getLocalIpAddress() ?: "获取IP失败"
        val text = "局域网 http://$ip:$PORT · 外网房间 $tunnelRoom"
        val stopIntent = Intent(this, RemoteAssistService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)
            .addAction(0, "停止", stopPending)
            .build()
    }

    // ==================== HTTP 服务 ====================

    private class RemoteAssistServer(
        port: Int,
        private val service: RemoteAssistService
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                uri == "/" || uri == "/index.html" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html; charset=utf-8",
                    buildControlPage()
                )

                uri == "/stream.mjpeg" -> mjpegResponse()

                uri == "/frame" -> {
                    // 单帧：返回最新 JPEG；X-Staleness 头 = 帧龄（页面用来显示实时延迟）
                    val jpeg = service.latestJpeg
                    if (jpeg != null) {
                        val response = newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            java.io.ByteArrayInputStream(jpeg),
                            jpeg.size.toLong()
                        )
                        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                        response.addHeader(
                            "X-Staleness",
                            (System.currentTimeMillis() - service.lastFrameAt).toString()
                        )
                        response
                    } else {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "no frame yet"
                        )
                    }
                }

                uri.startsWith("/tap") -> handleTap(session.parms)

                uri.startsWith("/swipe") -> handleSwipe(session.parms)

                uri == "/status" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"running":true,"screen":"${service.screenWidth}x${service.screenHeight}","publicUrl":${if (service.publicUrl != null) "\"${service.publicUrl}\"" else "null"}}"""
                )

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not Found"
                )
            }
        }

        private fun buildControlPage(): String {
            val w = service.screenWidth
            val h = service.screenHeight
            return """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>远程协助 - MoreTalk</title>
              <style>
                body { margin:0; background:#111; color:#fff; font-family:sans-serif; }
                #bar { padding:10px; text-align:center; font-size:18px; background:#222; }
                img { width:100%; height:auto; display:block;
                      touch-action:none; user-select:none; -webkit-user-select:none; }
                /* 全屏时画面适配视口高度，页面不再滚动，拖拽只用于滑动 */
                body.fs img { height:100vh; width:100%; object-fit:contain; background:#000; }
                #fsBtn { position:fixed; top:12px; right:12px; z-index:99;
                         padding:8px 16px; border:none; border-radius:8px;
                         background:rgba(0,0,0,.65); color:#fff; font-size:16px; cursor:pointer; }
                #hint { position:fixed; bottom:12px; left:50%; transform:translateX(-50%);
                        background:rgba(0,0,0,.75); padding:8px 16px; border-radius:8px;
                        font-size:14px; pointer-events:none; max-width:92vw; text-align:center; }
              </style>
            </head>
            <body>
              <div id="bar">MoreTalk 远程协助 · 请保持手机本页与控制页打开，退出会停止服务</div>
              <img id="stream" alt="画面加载中...">
              <div id="hint">点一下 = 点击；按住拖动 = 滑动</div>
              <button id="fsBtn">全屏</button>
              <script>
                var fsBtn = document.getElementById('fsBtn');
                function toggleFS() {
                  if (document.fullscreenElement) {
                    document.exitFullscreen();
                  } else {
                    document.documentElement.requestFullscreen();
                  }
                }
                fsBtn.addEventListener('click', toggleFS);
                document.addEventListener('fullscreenchange', function() {
                  var fs = !!document.fullscreenElement;
                  document.body.classList.toggle('fs', fs);
                  fsBtn.textContent = fs ? '退出全屏' : '全屏';
                });
              </script>
              <script>
                var SW = $w, SH = $h;
                var img = document.getElementById('stream');
                var hint = document.getElementById('hint');
                var downX = 0, downY = 0, dragging = false, active = false;
                var lastUrl = null;

                // fetch-blob 链式拉帧：每帧加载完立即请求下一帧，无堆积；并实时显示帧龄
                function poll() {
                  fetch('/frame?t=' + Date.now(), {cache: 'no-store'}).then(function(resp) {
                    var staleness = resp.headers.get('X-Staleness');
                    if (staleness !== null) {
                      hint.textContent = '画面延迟约 ' + staleness + 'ms（点=点击，拖=滑动）';
                    }
                    return resp.blob();
                  }).then(function(blob) {
                    if (lastUrl) { URL.revokeObjectURL(lastUrl); }
                    lastUrl = URL.createObjectURL(blob);
                    img.src = lastUrl;
                    poll();
                  }).catch(function() {
                    setTimeout(poll, 300);
                  });
                }
                poll();

                function toScreen(clientX, clientY) {
                  var rect = img.getBoundingClientRect();
                  return {
                    x: Math.round((clientX - rect.left) * SW / rect.width),
                    y: Math.round((clientY - rect.top) * SH / rect.height)
                  };
                }

                function beginDrag(cx, cy) {
                  downX = cx; downY = cy; dragging = false; active = true;
                }
                function moveDrag(cx, cy) {
                  if (!active) return;
                  if (Math.abs(cx - downX) + Math.abs(cy - downY) > 12) dragging = true;
                }
                function endDrag(cx, cy) {
                  if (!active) { active = false; return; }
                  active = false;
                  var s = toScreen(downX, downY);
                  var e = toScreen(cx, cy);
                  if (dragging) {
                    fetch('/swipe?x1=' + s.x + '&y1=' + s.y + '&x2=' + e.x + '&y2=' + e.y);
                    hint.textContent = '已滑动 (' + s.x + ',' + s.y + ') → (' + e.x + ',' + e.y + ')';
                  } else {
                    hint.textContent = '正在点击 (' + s.x + ', ' + s.y + ')…';
                    fetch('/tap?x=' + s.x + '&y=' + s.y).then(function(r) { return r.text(); })
                      .then(function(t) {
                        if (t === 'ok') { hint.textContent = '已点击 (' + s.x + ', ' + s.y + ')'; }
                        else { hint.textContent = '点击失败：请确认手机已开启无障碍服务'; }
                      });
                  }
                }

                // 鼠标
                img.addEventListener('mousedown', function(e) { beginDrag(e.clientX, e.clientY); });
                img.addEventListener('mousemove', function(e) { moveDrag(e.clientX, e.clientY); });
                img.addEventListener('mouseup', function(e) { endDrag(e.clientX, e.clientY); });
                img.addEventListener('mouseleave', function(e) { if (active) endDrag(e.clientX, e.clientY); });

                // 触摸（移动端浏览器）
                img.addEventListener('touchstart', function(e) { var t = e.touches[0]; beginDrag(t.clientX, t.clientY); }, {passive:true});
                img.addEventListener('touchmove', function(e) { var t = e.touches[0]; moveDrag(t.clientX, t.clientY); }, {passive:false});
                img.addEventListener('touchend', function(e) { var t = e.changedTouches[0]; endDrag(t.clientX, t.clientY); });
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        private fun mjpegResponse(): Response {
            // NanoHTTPD 标准流式方案：写入管道，newChunkedResponse 负责完整 HTTP 头 + chunked 编码。
            // （覆写 send() 会丢掉 HTTP 状态行和响应头，导致浏览器无法解析 → 黑屏）
            val pipeIn = java.io.PipedInputStream(64 * 1024)
            val pipeOut = java.io.PipedOutputStream(pipeIn)
            Thread {
                try {
                    val boundary = "--frame\r\nContent-Type: image/jpeg\r\n".toByteArray()
                    while (true) {
                        val jpeg = service.latestJpeg
                        if (jpeg != null) {
                            val lengthHeader = "Content-Length: ${jpeg.size}\r\n\r\n".toByteArray()
                            pipeOut.write(boundary)
                            pipeOut.write(lengthHeader)
                            pipeOut.write(jpeg)
                            pipeOut.write("\r\n".toByteArray())
                            pipeOut.flush()
                        }
                        Thread.sleep(FRAME_INTERVAL_MS)
                    }
                } catch (_: Exception) {
                    // 客户端断开或管道关闭，结束写线程
                }
            }.apply { isDaemon = true }.start()
            return newChunkedResponse(
                Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame",
                pipeIn
            )
        }

        private fun handleTap(parms: Map<String, String>): Response {
            val x = parms["x"]?.toFloatOrNull()
            val y = parms["y"]?.toFloatOrNull()
            if (x == null || y == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "need x,y")
            }
            val injected = SelectToSpeakService.performTap(x, y)
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                if (injected) "ok" else "accessibility service not ready"
            )
        }

        private fun handleSwipe(parms: Map<String, String>): Response {
            val x1 = parms["x1"]?.toFloatOrNull()
            val y1 = parms["y1"]?.toFloatOrNull()
            val x2 = parms["x2"]?.toFloatOrNull()
            val y2 = parms["y2"]?.toFloatOrNull()
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "need x1,y1,x2,y2")
            }
            val duration = parms["duration"]?.toLongOrNull()?.coerceIn(50L, 2000L) ?: 250L
            val injected = SelectToSpeakService.performSwipe(x1, y1, x2, y2, duration)
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                if (injected) "ok" else "accessibility service not ready"
            )
        }
    }

    /**
     * LAN 控制服务：控制页 + /ws 指令端点（NanoWSD）
     */
    private class NanoWsdServer(
        private val service: RemoteAssistService,
        private val handler: RemoteAssistWsHandler
    ) : NanoWSD(WS_PORT) {

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html")) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html; charset=utf-8",
                    buildControlPage()
                )
            }
            return super.serve(session)
        }

        override fun openWebSocket(handshake: IHTTPSession): WebSocket {
            return object : WebSocket(handshake) {
                override fun onOpen() {}

                override fun onClose(code: WebSocketFrame.CloseCode, reason: String, byRemote: Boolean) {}

                override fun onMessage(frame: WebSocketFrame) {
                    // 文本帧：浏览器指令（getTextPayload 对二进制帧可能抛异常，容错）
                    val text = runCatching { frame.textPayload }.getOrNull()
                    if (!text.isNullOrEmpty()) {
                        handler.handle(object : RemoteAssistWsHandler.WsSink {
                            override fun sendText(text: String) {
                                runCatching { send(text) }
                            }

                            override fun sendBinary(bytes: ByteArray) {
                                runCatching { send(bytes) }
                            }
                        }, text)
                    }
                }

                override fun onPong(pong: WebSocketFrame) {}

                override fun onException(e: IOException) {}
            }
        }

        private fun buildControlPage(): String {
            val room = RemoteAssistService.tunnelRoom
            val wanUrl = "http://${VPS_HOST}:${VPS_PORT}/?room=$room"
            return """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>远程协助 - MoreTalk</title><style>
body{margin:0;background:#111;color:#fff;font-family:sans-serif}
#bar{padding:10px;text-align:center;font-size:18px;background:#222}
img{width:100%;height:auto;display:block;touch-action:none;user-select:none;-webkit-user-select:none}
body.fs img{height:100vh;width:100%;object-fit:contain;background:#000}
#fsBtn{position:fixed;top:12px;right:12px;z-index:99;padding:8px 16px;border:none;border-radius:8px;background:rgba(0,0,0,.65);color:#fff;font-size:16px;cursor:pointer}
#hint{position:fixed;bottom:12px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.75);padding:8px 16px;border-radius:8px;font-size:14px;pointer-events:none;max-width:92vw;text-align:center}
</style></head><body>
<div id="bar">MoreTalk 远程协助 · 房间 $room · 外网访问：$wanUrl</div>
<img id="stream" alt="画面加载中...">
<div id="hint">正在连接…</div><button id="fsBtn">全屏</button>
<script>
var ROOM = '$room';
var WSURL = (location.protocol==='https:'?'wss://':'ws://') + location.host + '/ws?room=' + ROOM;
var img = document.getElementById('stream');
var hint = document.getElementById('hint');
var SW=0, SH=0, downX=0, downY=0, dragging=false, active=false, lastUrl=null;

function connect() {
  var ws = new WebSocket(WSURL);
  ws.binaryType = 'arraybuffer';
  ws.onopen = function(){ hint.textContent='已连接，等待画面…'; ws.send(JSON.stringify({op:'status'})); };
  ws.onclose = function(){ hint.textContent='连接断开，3秒后重连…'; setTimeout(connect, 3000); };
  ws.onerror = function(){ ws.close(); };
  ws.onmessage = function(ev){
    if (typeof ev.data === 'string') {
      var m = JSON.parse(ev.data);
      if (m.op === 'status') { SW=m.w; SH=m.h; hint.textContent='已连接 · '+SW+'x'+SH+'（点=点击，拖=滑动）'; reqFrame(ws); }
    } else {
      var dv = new DataView(ev.data);
      var age = dv.getUint32(0);
      var blob = new Blob([ev.data.slice(4)]);
      if (lastUrl) URL.revokeObjectURL(lastUrl);
      lastUrl = URL.createObjectURL(blob);
      img.src = lastUrl;
      hint.textContent = '画面延迟约 ' + age + 'ms（点=点击，拖=滑动）';
      reqFrame(ws);
    }
  };
  window._ws = ws;
}
function reqFrame(ws){ if (ws.readyState===1) ws.send(JSON.stringify({op:'frame'})); }
function send(obj){ var ws=window._ws; if(ws && ws.readyState===1) ws.send(JSON.stringify(obj)); }
function toScreen(cx,cy){ var r=img.getBoundingClientRect(); return {x:Math.round((cx-r.left)*SW/r.width), y:Math.round((cy-r.top)*SH/r.height)}; }
function beginDrag(cx,cy){ downX=cx; downY=cy; dragging=false; active=true; }
function moveDrag(cx,cy){ if(!active)return; if(Math.abs(cx-downX)+Math.abs(cy-downY)>12) dragging=true; }
function endDrag(cx,cy){
  if(!active){active=false;return;} active=false;
  var s=toScreen(downX,downY), e=toScreen(cx,cy);
  if(dragging){ send({op:'swipe',x1:s.x,y1:s.y,x2:e.x,y2:e.y}); hint.textContent='已滑动 ('+s.x+','+s.y+') → ('+e.x+','+e.y+')'; }
  else { send({op:'tap',x:s.x,y:s.y}); hint.textContent='已点击 ('+s.x+', '+s.y+')'; }
}
img.addEventListener('mousedown',function(e){beginDrag(e.clientX,e.clientY);});
img.addEventListener('mousemove',function(e){moveDrag(e.clientX,e.clientY);});
img.addEventListener('mouseup',function(e){endDrag(e.clientX,e.clientY);});
img.addEventListener('mouseleave',function(e){if(active)endDrag(e.clientX,e.clientY);});
img.addEventListener('touchstart',function(e){var t=e.touches[0];beginDrag(t.clientX,t.clientY);},{passive:true});
img.addEventListener('touchmove',function(e){var t=e.touches[0];moveDrag(t.clientX,t.clientY);},{passive:false});
img.addEventListener('touchend',function(e){var t=e.changedTouches[0];endDrag(t.clientX,t.clientY);});
document.getElementById('fsBtn').addEventListener('click',function(){ if(document.fullscreenElement){document.exitFullscreen();} else {document.documentElement.requestFullscreen();} });
document.addEventListener('fullscreenchange',function(){var fs=!!document.fullscreenElement;document.body.classList.toggle('fs',fs);document.getElementById('fsBtn').textContent=fs?'退出全屏':'全屏';});
connect();
</script></body></html>"""
        }
    }
}
