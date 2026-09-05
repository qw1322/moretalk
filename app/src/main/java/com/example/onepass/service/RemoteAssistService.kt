package com.example.onepass.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.onepass.R
import com.example.onepass.utils.Logger
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PORT = 8890
        private const val CHANNEL_ID = "remote_assist"
        private const val NOTIFICATION_ID = 8890
        private const val FRAME_INTERVAL_MS = 150L
        private const val JPEG_QUALITY = 60
        private const val CAPTURE_SCALE = 0.4f

        @Volatile
        var isRunning: Boolean = false
            private set

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
    private var latestJpeg: ByteArray? = null

    private var screenWidth = 0
    private var screenHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_START) {
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
                // 远程协助期间保持屏幕常亮，避免屏幕休眠导致采集黑帧
                val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                screenWakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "RemoteAssist:screen"
                ).apply { acquire() }
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

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            captureThread = HandlerThread("RemoteAssistCapture").apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            var lastFrameAt = 0L
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!running.get()) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                runCatching {
                    val jpeg = imageToJpeg(image, JPEG_QUALITY)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        latestJpeg = jpeg
                    }
                }.onFailure { e -> Logger.w("$TAG 帧处理失败: ${e.message}") }
                image.close()
            }, captureHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "RemoteAssistDisplay",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            Logger.d(TAG, "虚拟显示已创建 ${width}x$height")

            running.set(true)
            isRunning = true
            server = RemoteAssistServer(PORT, this)
            runCatching { server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                .onFailure { e ->
                    Logger.e(TAG, "HTTP 服务启动失败: ${e.message}")
                    stopSelf()
                }
            Logger.d(TAG, "HTTP 服务已启动 :$PORT")
        } catch (e: Exception) {
            Logger.e(TAG, "setupProjection 异常: ${e.message}", e)
            stopSelf()
        }
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
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
        cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bitmap.recycle()
        cropped.recycle()
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
        val text = "远程协助运行中：http://$ip:$PORT"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("远程协助（测试）")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)
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
                    // 单帧：JS 轮询用，返回最新 JPEG 图片
                    val jpeg = service.latestJpeg
                    if (jpeg != null) {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "image/jpeg",
                            java.io.ByteArrayInputStream(jpeg),
                            jpeg.size.toLong()
                        )
                    } else {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "no frame yet"
                        )
                    }
                }

                uri.startsWith("/tap") -> handleTap(session.parms)

                uri == "/status" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"running":true,"screen":"${service.screenWidth}x${service.screenHeight}"}"""
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
                img { width:100%; height:auto; display:block; }
                #hint { position:fixed; bottom:12px; left:50%; transform:translateX(-50%);
                        background:rgba(0,0,0,.75); padding:8px 16px; border-radius:8px;
                        font-size:14px; pointer-events:none; max-width:92vw; text-align:center; }
              </style>
            </head>
            <body>
              <div id="bar">MoreTalk 远程协助（点击画面即可远程操作）</div>
              <img id="stream" alt="画面加载中...">
              <div id="hint">点一下 = 在老人手机上点一下</div>
              <script>
                var SW = $w, SH = $h;
                var img = document.getElementById('stream');
                var hint = document.getElementById('hint');

                // 单帧轮询，兼容所有浏览器
                function refresh() {
                  img.src = '/frame?t=' + Date.now();
                }
                setInterval(refresh, 250);
                refresh();

                img.addEventListener('click', function(e) {
                  var rect = img.getBoundingClientRect();
                  var x = Math.round((e.clientX - rect.left) * SW / rect.width);
                  var y = Math.round((e.clientY - rect.top) * SH / rect.height);
                  hint.textContent = '正在点击 (' + x + ', ' + y + ')…';
                  fetch('/tap?x=' + x + '&y=' + y).then(function(r) { return r.text(); })
                    .then(function(t) {
                      if (t === 'ok') { hint.textContent = '已点击 (' + x + ', ' + y + ')'; }
                      else { hint.textContent = '点击失败：请确认手机已开启无障碍服务'; }
                    });
                });
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
    }
}
