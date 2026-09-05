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
        private const val CHANNEL_ID = "remote_assist"
        private const val NOTIFICATION_ID = 8890
        private const val FRAME_INTERVAL_MS = 100L
        private const val JPEG_QUALITY = 60
        // 0.5x 分辨率：帧体积与编码耗时减半，降低端到端延迟（不影响帧率上限）
        private const val CAPTURE_SCALE = 0.5f

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 公网隧道房间号（家属浏览器经中继访问用） */
        @Volatile
        var tunnelRoom: String = "MT${(100000..999999).random()}"
            internal set

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

    // WebSocket 控制服务（LAN）与公网隧道
    private var wsServer: NanoWsdServer? = null
    private val wsHandler = RemoteAssistWsHandler(this)
    private var tunnelOkHttp: OkHttpClient? = null
    private var tunnelWs: okhttp3.WebSocket? = null
    private val tunnelConnecting = AtomicBoolean(false)

    /** UPnP 映射后的公网访问地址（若成功） */
    @Volatile
    var publicUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // 看门狗：本 ROM 虚拟显示偶发停摆，停滞 700ms 即重建（恢复越快操作延迟越低）
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (running.get() && mediaProjection != null) {
                val stall = System.currentTimeMillis() - lastFrameAt
                if (stall > 700) {
                    Logger.w(TAG, "采集停滞 ${stall}ms，重建虚拟显示")
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
        runCatching { wsServer?.stop() }
        wsServer = null
        runCatching { tunnelWs?.close(1000, "stop") }
        tunnelWs = null
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
            // 公网隧道（主动出站连接 VPS 中继）
            startTunnel()
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

    private fun startTunnel() {
        if (tunnelConnecting.get() || tunnelWs != null) return
        tunnelConnecting.set(true)
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            tunnelOkHttp = client
            val req = Request.Builder()
                .url("ws://$VPS_HOST:$VPS_PORT/tunnel?room=$tunnelRoom")
                .build()
            tunnelWs = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    tunnelConnecting.set(false)
                    Logger.d(TAG, "公网隧道已连接，房间 $tunnelRoom")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Logger.d(TAG, "隧道收到: ${text.take(80)}")
                    wsHandler.handle(object : RemoteAssistWsHandler.WsSink {
                        override fun sendText(t: String) {
                            Logger.d(TAG, "隧道回文本: ${t.take(80)}")
                            runCatching { webSocket.send(t) }
                                .onFailure { Logger.e(TAG, "隧道发文本失败: ${it.message}") }
                        }

                        override fun sendBinary(bytes: ByteArray) {
                            Logger.d(TAG, "隧道回二进制: ${bytes.size}B")
                            runCatching { webSocket.send(okio.ByteString.of(*bytes)) }
                                .onFailure { Logger.e(TAG, "隧道发二进制失败: ${it.message}") }
                        }
                    }, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    tunnelWs = null
                    tunnelConnecting.set(false)
                    scheduleTunnelReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.w("$TAG 隧道断开: ${t.message}")
                    tunnelWs = null
                    tunnelConnecting.set(false)
                    scheduleTunnelReconnect()
                }
            })
        } catch (e: Exception) {
            tunnelConnecting.set(false)
            Logger.e(TAG, "隧道启动失败: ${e.message}")
            scheduleTunnelReconnect()
        }
    }

    private fun okHttpSink(ws: WebSocket) = object : RemoteAssistWsHandler.WsSink {
        override fun sendText(text: String) {
            runCatching { ws.send(text) }
        }

        override fun sendBinary(bytes: ByteArray) {
            runCatching { ws.send(okio.ByteString.of(*bytes)) }
        }
    }

    private fun scheduleTunnelReconnect() {
        mainHandler.postDelayed({ startTunnel() }, 5000)
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
