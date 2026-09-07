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
import android.graphics.ImageFormat
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.onepass.R
import com.example.onepass.utils.Logger
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import okhttp3.ConnectionPool
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 远程协助（P1/P2 局域网版）：
 * - MediaProjection 录屏 → MJPEG 流（http://<手机IP>:8890/stream.mjpeg）
 * - 家属浏览器打开 http://<手机IP>:8890 实时观看
 * - 点击画面 → 无障碍手势注入实现远程控制（/tap?x=&y=）
 *
 * 公网通道（v2.1 · 延迟优化版）：
 * - 采集线程只负责拿帧：默认 RGBA 软件编码（硬件 JPEG 探测默认关闭——realme 上会
 *   native 崩溃，见 ENABLE_HW_JPEG_PROBE 说明），编码在独立线程做，不阻塞采集。
 * - 帧上传改事件驱动：新帧一到立即 POST（不低于 50ms 保底），失败退避 100ms 重试。
 * - 指令通道独立 50ms 高频轮询，与帧上传互不拖累（点击→执行快 3 倍）。
 * - 网络自适应：延迟高自动降 5fps/JPEG40，延迟低升 15fps/JPEG80。
 * - HTTP keep-alive：手机与中继共用一条 TCP 连接连续请求，省去每请求握手/告别
 *   各 1 个跨境 RTT（OkHttp 对 HTTP/1.1 默认保持连接，中继端需 protocol_version="HTTP/1.1"）。
 * - v1.8.2 修复：看门狗阈值放宽（8s），杜绝真机（slow 出帧 ~1fps）上 1.5s 停滞即重建
 *   造成的重建风暴——重建会打断采集，是家属端 3~4s 延迟的主因。
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
        private const val CHANNEL_ID = "remote_assist"
        private const val NOTIFICATION_ID = 8890
        // 0.5x 分辨率：帧体积与编码耗时减半，降低端到端延迟（不影响帧率上限）
        private const val CAPTURE_SCALE = 0.5f

        // ===== 采集模式开关（v2.1 / v1.8.1 修复）=====
        /**
         * 是否启用 ImageFormat.JPEG 硬件直出探测。
         *
         * ⚠️ v1.8.0 曾默认启用：在 realme GT Neo5 SE（骁龙+ColorOS）上，
         * ImageReader.newInstance(JPEG) 创建不报错，但接到 MediaProjection 虚拟显示后
         * 底层 SurfaceFlinger 的硬件 JPEG 路径 native 崩溃（SIGSEGV，Java 层无法捕获）
         * → 启动远程协助数秒内整个进程"卡退"。
         *
         * 现默认 false：一律走 RGBA 软件编码（编码线程分离、事件驱动上传/独立指令轮询/
         * 自适应等其余优化不受影响）。探测代码保留，未来在验证过支持的机型上可置 true。
         */
        private const val ENABLE_HW_JPEG_PROBE = false
        /** 局域网 MJPEG 流帧间隔（与公网自适应无关，保持原节奏） */
        private const val LAN_MJPEG_INTERVAL_MS = 100L

        // ===== 公网通道参数（v2.1）=====
        /** 指令通道独立轮询周期：只传几个字节，代价极小，操作手感直接受益 */
        private const val CMD_POLL_INTERVAL_MS = 50L
        /** 帧上传最小间隔（保底节流）：新帧再急也至少等 50ms，防 60fps 采集打爆链路 */
        private const val FRAME_MIN_UPLOAD_INTERVAL_MS = 50L
        /** 上传失败退避：失败后至少等这么久再试 */
        private const val UPLOAD_FAIL_BACKOFF_MS = 100L
        /** 无新帧时上传循环的轻度轮询周期（很小，反正也不占流量） */
        private const val UPLOAD_LOOP_POLL_MS = 20L
        /** 编码线程无任务时的等待超时 */
        private const val ENCODE_WAIT_TIMEOUT_MS = 100L
        /** 上传线程等新帧的最大等待（有新帧时 notify 立即唤醒，不为零） */
        private const val FRAME_WAIT_TIMEOUT_MS = 200L
        /**
         * 保底上传间隔（v1.8.4）：虚拟显示在屏幕完全静止时不出帧（真机实测），
         * 若"有新帧才上传"，家属端画面会冻结数秒。每此间隔无新帧也把当前帧重新上传，
         * 刷新中继时间戳 → 家属端画面持续更新（内容相同但时间戳新鲜，X-Age 保持 <1s）。
         */
        private const val PACE_UPLOAD_INTERVAL_MS = 800L

        // ===== 网络自适应（v2.1）=====
        private const val ADAPT_EVAL_INTERVAL_MS = 2000L
        /** 连续 N 次评估达标才升档（滞回，防抖动来回跳） */
        private const val ADAPT_UP_NEEDED = 2
        /** 平滑 RTT 超过该值视为"延迟高"（跨境链路） */
        private const val RTT_BAD_MS = 400.0
        /** 平滑 RTT 低于该值视为"延迟低" */
        private const val RTT_GOOD_MS = 160.0
        /** 5 秒窗口内失败次数达到该值视为"网络差" */
        private const val FAIL_BAD_COUNT = 3

        // ===== 看门狗（v1.8.6 修复：周期性重建取快照）=====
        /**
         * 真机（realme GT Neo5 SE / ColorOS）关键发现：ImageReader 是被动消费者，
         * 虚拟显示投递几帧后就永久停摆（SF 不再向 ImageReader 投帧，屏幕怎么变都无效），
         * 但**每次重建虚拟显示都会拿到一张"当前屏幕快照"**。因此这里采用"周期重建"策略：
         * 无新采集帧约 1.2s 就重建一次虚拟显示 → 持续拿到新快照上传，家属端画面得以更新。
         * （v1.8.4 曾用 8s 阈值 + 以"上传活性"抑制重建，结果画面内容永久冻结——放弃该策略。）
         */
        private const val WATCHDOG_STALL_MS = 1200L
        /** 重建冷却：给重建后出帧留时间，避免同一虚拟显示实例未出帧就再次重建 */
        private const val WATCHDOG_REBUILD_COOLDOWN_MS = 1600L

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

    /** 采集模式：HW_JPEG = 硬件 JPEG 直出（无编码开销）；RGBA = 软件编码线程 */
    internal enum class CaptureMode { HW_JPEG, RGBA }

    /** 网络自适应档位：帧率 / JPEG 质量 */
    internal enum class Tier(val fps: Int, val quality: Int) {
        SLOW(5, 40),
        MID(10, 60),
        FAST(15, 80)
    }

    private var mediaProjection: MediaProjection? = null
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
    /** 自上次重建以来是否收到过有效帧（看门狗据此区分"硬件JPEG不支持"与"真停滞"） */
    @Volatile
    private var imageSawSinceRebuild = false
    /** 连续无帧停滞计数：硬件JPEG模式连续 2 次重建仍无帧 → 永久回退 RGBA */
    private var noImageStallCount = 0

    // ===== 帧发布/消费协调（v2.1）=====
    private val frameLock = Object()
    /** 帧代数：每次发布新 JPEG 自增；上传线程按代数判断"有新帧"（比引用比较可靠） */
    private val frameGen = AtomicLong(0)
    /** 已成功上传到中继的帧代数 */
    private val uploadedGen = AtomicLong(0)
    /** 待编码的 RGBA 帧（单槽 latest-wins：新帧到来丢弃未编码旧帧，保证只看最新） */
    private var pendingRgba: Image? = null
    /** nullable=首次运行还在探测；HW_JPEG / RGBA 由首帧实际格式或异常定型 */
    @Volatile
    private var captureMode: CaptureMode? = null

    // ===== 编码线程（v2.1：编码与采集分离）=====
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var encodeCount = 0L

    // ===== 公网上传线程（v2.1：事件驱动，新帧立即传；v1.8.4：无新帧也保底刷新）=====
    private var uploadThread: HandlerThread? = null
    private var uploadHandler: Handler? = null
    private var lastUploadAt = 0L
    private var nextUploadAllowedAt = 0L
    /** 最近一次上传成功时刻（v1.8.4：看门狗据此判断"通道还活着"，静止时不再误重建） */
    @Volatile
    private var lastUploadSuccessAt = 0L

    // ===== 网络自适应状态 =====
    @Volatile
    internal var tier = Tier.MID
    @Volatile
    internal var ewmaRttMs = 0.0
    private var failWindowCount = 0
    private var failWindowStart = 0L
    private var lastAdaptAt = 0L
    private var upStreak = 0

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

    /** UPnP 映射后的公网访问地址（若成功） */
    @Volatile
    var publicUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // 看门狗：v1.8.6 起为"周期重建取快照"策略。
    // 真机（realme/ColorOS）关键发现：ImageReader 是被动消费者，虚拟显示投递几帧后
    // 永久停摆（SF 不再投帧），但每次重建虚拟显示都会拿到一张"当前屏幕快照"。
    // 因此无新采集帧约 1.2s 即重建，持续取新快照上传，家属端画面得以更新。
    private val watchdogRunnable = object : Runnable {
        private var lastRebuildAt = 0L
        private var rebuildCount = 0
        override fun run() {
            if (running.get() && mediaProjection != null) {
                val now = System.currentTimeMillis()
                // v1.8.6：重建判定只基于"采集帧"（lastFrameAt）——保底上传的时间戳活性
                // 不抑制重建；无新采集帧即周期性重建虚拟显示以获取新屏幕快照。
                val stall = now - lastFrameAt
                if (stall > WATCHDOG_STALL_MS && now - lastRebuildAt > WATCHDOG_REBUILD_COOLDOWN_MS) {
                    if (!imageSawSinceRebuild) {
                        // 长时间一帧未出：若还停留在"JPEG直出"探测/模式，判定该设备不支持，永久降级软件编码
                        noImageStallCount++
                        if ((captureMode == null || captureMode == CaptureMode.HW_JPEG) && noImageStallCount >= 2) {
                            Logger.w(TAG, "ImageFormat.JPEG 直出疑似不支持（连续重建仍无帧），锁定 RGBA 软件编码")
                            captureMode = CaptureMode.RGBA
                        }
                    } else {
                        noImageStallCount = 0
                    }
                    rebuildCount++
                    if (rebuildCount <= 3 || rebuildCount % 10 == 0) {
                        Logger.w(TAG, "采集停滞 ${stall}ms，重建虚拟显示 #$rebuildCount（mode=${captureMode ?: "probe"}）")
                    }
                    lastRebuildAt = now
                    runCatching { createCapture(captureWidth, captureHeight, captureDensity) }
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
        pollRunning.set(false)
        mainHandler.removeCallbacks(watchdogRunnable)
        // 唤醒可能在等待中的编码/上传线程，让它们尽快退出
        synchronized(frameLock) {
            frameLock.notifyAll()
        }
        pollHandler?.removeCallbacksAndMessages(null)
        pollHandler = null
        uploadHandler?.removeCallbacksAndMessages(null)
        uploadHandler = null
        encodeHandler?.removeCallbacksAndMessages(null)
        encodeHandler = null
        runCatching { wsServer?.stop() }
        wsServer = null
        // v1.8.5：pacer 已停用（代码保留供参考，见 startFramePacer）
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
        encodeThread?.quitSafely()
        encodeThread = null
        uploadThread?.quitSafely()
        uploadThread = null
        pollThread?.quitSafely()
        pollThread = null
        synchronized(frameLock) {
            runCatching { pendingRgba?.close() }
            pendingRgba = null
            latestJpeg = null
        }
        frameGen.set(0)
        uploadedGen.set(0)
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
            // 独立编码线程只建一次（采集可被看门狗反复重建，编码线程不能重建）
            encodeThread = HandlerThread("RemoteAssistEncode").apply { start() }
            encodeHandler = Handler(encodeThread!!.looper)
            encodeHandler?.post(::encodeLoop)
            createCapture(width, height, metrics.densityDpi)

            server = RemoteAssistServer(PORT, this)
            runCatching { server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onFailure { e ->
                    Logger.e(TAG, "HTTP 服务启动失败: ${e.message}")
                    stopSelf()
                }
            Logger.d(TAG, "HTTP 服务已启动 :$PORT")
            // WebSocket 控制服务（LAN 页面 + 控制）
            startWsServer()
            // 公网通道 v2.1：事件驱动帧上传 + 独立 50ms 指令轮询（HTTP keep-alive 长连接）
            startTunnel()
            // v1.8.5：不再启动 pacer 悬浮窗——实测无法驱动虚拟显示且可能有干扰，保底上传已解决家属端刷新
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
     * 默认 RGBA_8888 软件编码（v1.8.1：硬件 JPEG 探测默认关闭——realme GT Neo5 SE
     * 上会 native 崩溃，见 ENABLE_HW_JPEG_PROBE 说明；探测代码保留待验证机型启用）。
     */
    private fun createCapture(width: Int, height: Int, densityDpi: Int) {
        runCatching { imageReader?.close() }
        // 仅当全局开关开启且尚未定型时才尝试硬件 JPEG；默认走 RGBA
        val wantHw = ENABLE_HW_JPEG_PROBE && captureMode == null
        val format = if (wantHw) ImageFormat.JPEG else PixelFormat.RGBA_8888
        val reader = try {
            ImageReader.newInstance(width, height, format, 2)
        } catch (e: Exception) {
            if (wantHw) {
                Logger.w(TAG, "ImageFormat.JPEG 创建失败（${e.message}），回退 RGBA 软件编码")
                captureMode = CaptureMode.RGBA
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            } else throw e
        }
        imageReader = reader
        // ⚠️ v1.8.5：不要对 ImageReader.surface 调用 setFrameRate——真机（realme/ColorOS）
        // 实测会与虚拟显示投帧机制冲突，导致启动后出几帧就彻底停摆（屏幕怎么变都无新帧）。
        captureThread?.quitSafely()
        captureThread = HandlerThread("RemoteAssistCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        imageSawSinceRebuild = false

        // 采集回调只做两件事：节流 + 拿帧。编码一律不在此线程做。
        //   - HW_JPEG 模式：直接把 JPEG 字节发布给上传线程（无编码）
        //   - RGBA 模式：交给编码线程做 latest-wins 软件编码（新帧自动丢旧帧）
        reader.setOnImageAvailableListener({ r ->
            imageArriveCount++
            if (imageArriveCount <= 5) {
                Logger.d(TAG, "图像到达 #$imageArriveCount")
            }
            if (!running.get()) return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < adaptiveIntervalMs()) return@setOnImageAvailableListener
            lastFrameAt = now
            imageSawSinceRebuild = true
            val image = r.acquireLatestImage()
            if (image == null) {
                if (imageArriveCount <= 5) Logger.w(TAG, "acquireLatestImage 返回 null")
                return@setOnImageAvailableListener
            }
            runCatching {
                // 首个帧按实际格式定型：设备若忽略 JPEG 请求返回 RGBA，直接锁定软件编码
                if (captureMode == null) {
                    captureMode =
                        if (image.format == ImageFormat.JPEG) CaptureMode.HW_JPEG else CaptureMode.RGBA
                    Logger.d(TAG, "采集模式定档：${captureMode}")
                }
                when (captureMode) {
                    CaptureMode.HW_JPEG -> {
                        if (image.format == ImageFormat.JPEG) {
                            try {
                                val jpeg = extractJpeg(image)
                                if (jpeg.isNotEmpty()) publishFrame(jpeg)
                            } finally {
                                image.close()
                            }
                        } else {
                            // 设备实际返回非 JPEG：本会话永久回退软件编码（防御性兜底）
                            captureMode = CaptureMode.RGBA
                            Logger.w(TAG, "JPEG 模式实得 format=${image.format}，回退 RGBA 编码")
                            enqueueRgba(image)
                        }
                    }

                    CaptureMode.RGBA -> enqueueRgba(image)
                    null -> image.close() // 理论上不可达
                }
            }.onFailure { e ->
                Logger.w("$TAG 帧处理失败: ${e.message}")
                runCatching { image.close() }
            }
        }, captureHandler)

        runCatching { virtualDisplay?.release() }
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteAssistDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        // 注意：不要在这里初始化 lastFrameAt！首帧可能与节流时间戳撞车被丢弃
        Logger.d(TAG, "虚拟显示已重建 ${width}x$height（mode=${captureMode ?: "probe"}）")
    }

    /** 自适应帧间隔：1000/当前档位帧率（5~15fps → 200ms~66ms） */
    private fun adaptiveIntervalMs(): Long = (1000L / tier.fps).coerceAtLeast(40L)

    /** 发布一帧 JPEG：写入 latestJpeg 并推进代数，唤醒等待中的上传线程 */
    private fun publishFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpeg
            frameGen.incrementAndGet()
            frameLock.notifyAll()
        }
    }

    /** 把 RGBA 帧交给编码线程（单槽 latest-wins：新帧到来丢弃未编码旧帧） */
    private fun enqueueRgba(image: Image) {
        synchronized(frameLock) {
            runCatching { pendingRgba?.close() }
            pendingRgba = image
            frameLock.notifyAll()
        }
    }

    /** 硬件 JPEG 直出：Image 只有一个 plane，Buffer 里就是完整 JPEG 字节 */
    private fun extractJpeg(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    /** 编码线程主循环：最新帧一到就编码为 JPEG（软件路径），空闲时轻量等待 */
    private fun encodeLoop() {
        if (!running.get()) return
        var image: Image? = null
        synchronized(frameLock) {
            while (running.get() && pendingRgba == null) {
                try {
                    frameLock.wait(ENCODE_WAIT_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            image = pendingRgba
            pendingRgba = null
        }
        if (image != null) {
            val img = image
            val jpeg = runCatching { imageToJpeg(img, tier.quality) }.getOrNull()
            runCatching { img.close() }
            if (jpeg != null && jpeg.isNotEmpty()) publishFrame(jpeg)
        }
        encodeHandler?.post(::encodeLoop)
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        val t0 = SystemClock.elapsedRealtime()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        val baos = ByteArrayOutputStream()
        val ok = cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bitmap.recycle()
        cropped.recycle()
        val t1 = SystemClock.elapsedRealtime()
        val n = ++encodeCount
        // 日志收敛：前 5 次 + 每 300 帧记录一次，避免每帧打日志拖慢链路
        if (n <= 5 || n % 300 == 0L) {
            Logger.d(TAG, "编码完成 ok=$ok size=${baos.size()} 耗时${t1 - t0}ms q=$quality")
        }
        if (!ok) {
            Logger.w(TAG, "JPEG 压缩失败（返回 false）")
            return null
        }
        return baos.toByteArray()
    }

    // ==================== WebSocket 控制（LAN + 公网隧道） ====================

    private fun startWsServer() {
        wsServer = NanoWsdServer(this, wsHandler)
        runCatching { wsServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            .onFailure { e -> Logger.e(TAG, "WS 服务启动失败: ${e.message}") }
        Logger.d(TAG, "WS 控制服务已启动 :$WS_PORT，房间 $tunnelRoom")
    }

    // ==================== 公网通道 v2.1（事件驱动上传 + 独立高频指令轮询） ====================
    //
    // 与原 v2（固定 150ms 一轮：上传帧+拉指令）相比：
    //  - 帧上传：新帧一到立即 POST，仅保留 50ms 保底 → 平均上传延迟由 ~75ms 降到 ~15ms；
    //  - 指令轮询：独立线程 50ms 高频 GET，点击→执行不再被大帧上传拖累；
    //  - 失败退避：上传失败等 100ms 再试，网络抖动不空转；
    //  - keep-alive：OkHttp 对 HTTP/1.1 默认保持连接（BridgeInterceptor 自动携带
    //    Connection: keep-alive），配合中继 protocol_version="HTTP/1.1"，同一 TCP 连接
    //    连续承载全部请求，每请求省去握手+告别各 1 个跨境 RTT（~140ms）。

    private fun startTunnel() {
        if (pollRunning.get()) return
        pollRunning.set(true)
        try {
            ensureTunnelClient()
            pollThread?.quitSafely()
            pollThread = HandlerThread("RemoteAssistCtl").apply { start() }
            pollHandler = Handler(pollThread!!.looper)
            uploadThread?.quitSafely()
            uploadThread = HandlerThread("RemoteAssistUp").apply { start() }
            uploadHandler = Handler(uploadThread!!.looper)
            pollHandler?.post(::commandLoop)
            uploadHandler?.post(::uploadLoop)
            Logger.d(TAG, "公网通道已启动（事件驱动上传 + ${CMD_POLL_INTERVAL_MS}ms 指令轮询），房间 $tunnelRoom")
        } catch (e: Exception) {
            pollRunning.set(false)
            Logger.e(TAG, "公网通道启动失败: ${e.message}")
        }
    }

    /** 共享 OkHttpClient：连接池复用（keep-alive）。两个通道各自顺序 execute，池自动保持 2 条长连接 */
    private fun ensureTunnelClient(): OkHttpClient {
        tunnelOkHttp?.let { return it }
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(4, 60, TimeUnit.SECONDS))
            .build().also { tunnelOkHttp = it }
    }

    /** 指令通道：独立 50ms 高频轮询，只传几个字节，与帧上传互不影响 */
    private fun commandLoop() {
        if (!pollRunning.get() || !running.get()) return
        pollCommands()
        pollHandler?.postDelayed(::commandLoop, CMD_POLL_INTERVAL_MS)
    }

    /** 帧上传线程：有新帧立即传，50ms 保底节流，失败退避 100ms；无新帧每 800ms 保底刷新一次 */
    private fun uploadLoop() {
        if (!running.get()) return
        var gen = 0L
        var jpeg: ByteArray? = null
        synchronized(frameLock) {
            // 等新帧（notifyAll 立即唤醒）；每 200ms 醒来一次，累计达保底间隔后即使无新帧
            // 也退出 → 上传当前帧刷新中继时间戳，防止虚拟显示静止不出帧时家属端画面冻结
            var waited = 0L
            while (running.get() && frameGen.get() == uploadedGen.get() && waited < PACE_UPLOAD_INTERVAL_MS) {
                try {
                    frameLock.wait(FRAME_WAIT_TIMEOUT_MS)
                    waited += FRAME_WAIT_TIMEOUT_MS
                } catch (_: InterruptedException) {
                    break
                }
            }
            gen = frameGen.get()
            jpeg = latestJpeg
        }
        if (!running.get()) return
        if (jpeg == null) {
            scheduleUpload(UPLOAD_LOOP_POLL_MS)
            return
        }
        val now = System.currentTimeMillis()
        val waitMs = maxOf(
            FRAME_MIN_UPLOAD_INTERVAL_MS - (now - lastUploadAt),
            nextUploadAllowedAt - now,
            0L
        )
        if (waitMs > 0) {
            scheduleUpload(waitMs)
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val ok = uploadFrame(jpeg)
        val rtt = SystemClock.elapsedRealtime() - t0
        val t1 = System.currentTimeMillis()
        if (ok) {
            // 保底刷新时 gen==uploadedGen，set 无副作用；期间若来了新帧，下轮立即上传
            uploadedGen.set(gen)
            lastUploadAt = t1
            lastUploadSuccessAt = t1
            ewmaRttMs = if (ewmaRttMs <= 0.0) rtt.toDouble() else ewmaRttMs * 0.7 + rtt * 0.3
            resetFailWindow(t1)
            // 首次上传成功后顺带上报屏幕物理分辨率（等下不阻塞首帧，网络已确认可用）
            if (!metaUploaded && screenWidth > 0 && screenHeight > 0 && uploadMeta()) {
                metaUploaded = true
            }
        } else {
            nextUploadAllowedAt = t1 + UPLOAD_FAIL_BACKOFF_MS
            bumpFailWindow(t1)
        }
        maybeAdapt(t1)
        scheduleUpload(if (ok) UPLOAD_LOOP_POLL_MS else UPLOAD_FAIL_BACKOFF_MS)
    }

    private fun scheduleUpload(ms: Long) {
        uploadHandler?.postDelayed(::uploadLoop, ms)
    }

    /** 上报真实屏幕物理分辨率，供家属网页做点击/滑动坐标换算；返回是否成功（失败下次再报） */
    private fun uploadMeta(): Boolean {
        if (screenWidth <= 0) return false
        return runCatching {
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
                resp.isSuccessful
            } ?: false
        }.getOrDefault(false)
    }

    /** 上传最新帧；成功返回 true。keep-alive 连接被 NAT 重置时 OkHttp 会在新连接上自动重试一次 */
    private fun uploadFrame(jpeg: ByteArray): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$VPS_HOST:$VPS_PORT/frame?room=$tunnelRoom")
                .post(okhttp3.RequestBody.create(null, jpeg))
                .build()
            tunnelOkHttp?.newCall(req)?.execute()?.use { resp ->
                if (!resp.isSuccessful) Logger.w("$TAG 上传帧失败 HTTP ${resp.code}")
                resp.isSuccessful
            } ?: false
        } catch (e: Exception) {
            // 网络抖动属正常，静默跳过，退避后重试
            if (System.currentTimeMillis() % 30_000 < 300) {
                Logger.w("$TAG 上传帧异常: ${e.message}")
            }
            false
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

    // ==================== 网络自适应（延迟高→低画质，延迟低→高画质） ====================

    private fun resetFailWindow(now: Long) {
        if (now - failWindowStart > 5000) {
            failWindowCount = 0
            failWindowStart = now
        }
    }

    private fun bumpFailWindow(now: Long) {
        if (now - failWindowStart > 5000) {
            failWindowCount = 0
            failWindowStart = now
        }
        failWindowCount++
    }

    /**
     * 每 2s 评估一次链路质量：
     *  - RTT 平滑值 > 400ms 或 5s 内失败 ≥3 次 → 立即降一档（降至 5fps/JPEG40）；
     *  - RTT < 160ms 且无失败，连续 2 次评估达标 → 升一档（最高 15fps/JPEG80）。
     * 升降只走相邻档位，带滞回防抖动。
     */
    private fun maybeAdapt(now: Long) {
        if (now - lastAdaptAt < ADAPT_EVAL_INTERVAL_MS) return
        lastAdaptAt = now
        resetFailWindow(now)
        val bad = ewmaRttMs > RTT_BAD_MS || failWindowCount >= FAIL_BAD_COUNT
        val excellent = ewmaRttMs < RTT_GOOD_MS && failWindowCount == 0
        if (bad) {
            upStreak = 0
            if (tier.ordinal > 0) {
                val prev = tier
                tier = Tier.entries[tier.ordinal - 1]
                Logger.d(TAG, "网络自适应 ↓ ${prev.name}→${tier.name}（${tier.fps}fps/JPEG${tier.quality}）rtt=${ewmaRttMs.toInt()}ms 失败=$failWindowCount")
            }
        } else if (excellent && tier.ordinal < Tier.entries.size - 1) {
            upStreak++
            if (upStreak >= ADAPT_UP_NEEDED) {
                upStreak = 0
                val prev = tier
                tier = Tier.entries[tier.ordinal + 1]
                Logger.d(TAG, "网络自适应 ↑ ${prev.name}→${tier.name}（${tier.fps}fps/JPEG${tier.quality}）rtt=${ewmaRttMs.toInt()}ms")
            }
        } else {
            upStreak = 0
        }
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
                    """{"running":true,"screen":"${service.screenWidth}x${service.screenHeight}","publicUrl":${if (service.publicUrl != null) "\"${service.publicUrl}\"" else "null"},"fps":${service.tier.fps},"quality":${service.tier.quality},"rtt":${service.ewmaRttMs.toInt()}}"""
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
                        Thread.sleep(LAN_MJPEG_INTERVAL_MS)
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