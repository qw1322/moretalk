package com.example.onepass.service

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import com.example.onepass.utils.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 硬编码实验流（MediaCodec + WebCodecs 通道）：
 * - MediaProjection 虚拟显示 Surface 直连 MediaCodec 编码器输入，零拷贝硬件编码（不经过 ImageReader/软编码）；
 * - 输出为 Annex-B NAL（无 B 帧，2s 一个关键帧）；关键帧分片前拼 SPS/PPS，家属端据此建解码器；
 * - 分片合批（~80ms）POST VPS /h264，规避 HTTP/1.0 短连接每请求的握手开销；
 * - 失败退避 100ms 重试整批（关键帧每 2s 重来，重复 delta 由家属端解码器自愈）。
 */
class H264Streamer(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val okHttp: OkHttpClient,
    private val room: String,
    private val vpsHost: String,
    private val vpsPort: Int
) {

    companion object {
        private const val TAG = "H264Streamer"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        /** 编码目标码率：1.2Mbps @ 30fps 0.5x 分辨率（后续可接 RTT 自适应） */
        private const val BIT_RATE = 1_200_000
        private const val FRAME_RATE = 30
        /** 关键帧间隔（秒）：家属端丢帧自愈上限 = 该间隔 */
        private const val I_FRAME_INTERVAL = 2
        /** 合批窗口：攒 80ms 编码输出一次 POST */
        private const val BATCH_MS = 80L
        /** 上传失败退避 */
        private const val RETRY_BACKOFF_MS = 100L
        /** 上传分片队列上限（丢最早保延迟） */
        private const val MAX_PENDING = 128
    }

    private class Packet(val key: Boolean, val data: ByteArray)

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTexture: SurfaceTexture? = null
    /** 编码器初始配置帧（SPS+PPS，Annex-B），拼到每个关键帧分片前，家属端解析 description */
    private var configNals: ByteArray? = null

    private val pending = ArrayList<Packet>()
    /** 编码输出最近时间戳（看门狗停滞检测用） */
    @Volatile
    var lastOutputAt = 0L
        private set

    private var outputThread: Thread? = null
    private var uploadThread: Thread? = null

    /** 编码器是否真正在运行（start 失败/已停止时为 false，供上层做失败回滚） */
    val isAlive: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            // MediaCodec Surface 输入要求尺寸 16 对齐（部分平台/骁龙编解码器对非对齐尺寸拒绝建 surface），
            // 先对齐再建编码器与虚拟显示；画面按 CSS 缩放显示，分辨率不齐无感知。
            val encW = (width + 15) / 16 * 16
            val encH = (height + 15) / 16 * 16
            val format = MediaFormat.createVideoFormat(MIME, encW, encH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                if (Build.VERSION.SDK_INT >= 26) {
                    // 无 B 帧：解码简单、丢帧容错强（WebCodecs 直线解码）
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val c = MediaCodec.createEncoderByType(MIME)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // ⚠️ 顺序铁律：createInputSurface 必须在 start() 之前！
            // 之前写在 start() 之后，必然抛 IllegalStateException → H.264 链路从未真正启动过
            val surface = c.createInputSurface()
            c.start()
            codec = c
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "RemoteAssistH264",
                encW,
                encH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
            lastOutputAt = System.currentTimeMillis()
            outputThread = Thread({ outputLoop() }, "H264Output").apply { start() }
            uploadThread = Thread({ uploadLoop() }, "H264Upload").apply { start() }
            Logger.d(TAG, "H.264 编码已启动 ${encW}x$encH")
        } catch (e: Exception) {
            Logger.e(TAG, "H.264 启动失败: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        running.set(false)
        try {
            outputThread?.join(500)
        } catch (_: InterruptedException) {
        }
        try {
            uploadThread?.join(500)
        } catch (_: InterruptedException) {
        }
        outputThread = null
        uploadThread = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configNals = null
        synchronized(pending) { pending.clear() }
        Logger.d(TAG, "H.264 编码已停止")
    }

    /** 看门狗用：编码停滞时整体重建（虚拟显示 + 编码器） */
    fun rebuild() {
        Logger.w(TAG, "编码停滞，重建 H.264 链路")
        stop()
        start()
    }

    /** 请求下一个关键帧（码率/画面突变时用，当前仅预留） */
    fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    // ==================== 输出循环：编码器 → NAL 分片 ====================

    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val c = codec ?: return
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                Logger.w("$TAG 编码器已释放: ${e.message}")
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                idx >= 0 -> {
                    try {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            handleOutput(bytes, info.flags)
                        }
                        c.releaseOutputBuffer(idx, false)
                    } catch (e: Exception) {
                        Logger.w("$TAG 输出处理异常: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleOutput(bytes: ByteArray, flags: Int) {
        lastOutputAt = System.currentTimeMillis()
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // SPS/PPS 配置帧：缓存，之后每个关键帧前拼上（家属端据此构造 avcC description）
            configNals = toAnnexB(bytes)
            return
        }
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val annex = toAnnexB(bytes)
        val packet = if (isKey) {
            val cfg = configNals
            if (cfg != null) cfg + annex else annex
        } else {
            annex
        }
        enqueue(Packet(isKey, packet))
    }

    /**
     * 统一为 Annex-B（起始码分割）：多数 Surface 编码器输出已是 Annex-B；
     * 少数输出 avcC（4 字节长度前缀）时转换。家属端按起始码解析 NAL。
     */
    private fun toAnnexB(bytes: ByteArray): ByteArray {
        if (isAnnexB(bytes)) return bytes
        return runCatching {
            val out = ByteArrayOutputStream(bytes.size + 16)
            var i = 0
            while (i + 4 <= bytes.size) {
                val len = ((bytes[i].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i + 3].toInt() and 0xFF)
                if (len <= 0 || i + 4 + len > bytes.size) break
                out.write(0); out.write(0); out.write(0); out.write(1)
                out.write(bytes, i + 4, len)
                i += 4 + len
            }
            out.toByteArray()
        }.getOrElse {
            bytes
        }
    }

    private fun isAnnexB(bytes: ByteArray): Boolean {
        return bytes.size >= 4 && (
            (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) ||
                (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte())
            )
    }

    private fun enqueue(packet: Packet) {
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) pending.removeAt(0)
            pending.add(packet)
        }
    }

    // ==================== 上传循环：合批 POST /h264 ====================

    private fun uploadLoop() {
        while (running.get()) {
            Thread.sleep(BATCH_MS)
            val batch: List<Packet>
            synchronized(pending) {
                if (pending.isEmpty()) continue
                batch = ArrayList(pending)
                pending.clear()
            }
            if (!uploadBatch(batch)) {
                // 失败：整批退回头部，退避后重试（关键帧 2s 一轮，重复 delta 家属端自愈）
                synchronized(pending) { pending.addAll(0, batch) }
                Thread.sleep(RETRY_BACKOFF_MS)
            }
        }
    }

    private fun uploadBatch(batch: List<Packet>): Boolean {
        return runCatching {
            val body = packBatch(batch)
            val req = Request.Builder()
                .url("http://$vpsHost:$vpsPort/h264?room=$room")
                .post(okhttp3.RequestBody.create(null, body))
                .build()
            var ok = false
            okHttp.newCall(req).execute().use { resp ->
                ok = resp.isSuccessful
                if (!ok) Logger.w("$TAG 上传分片失败 HTTP ${resp.code}")
            }
            ok
        }.getOrElse { e ->
            if (System.currentTimeMillis() % 30_000 < 300) {
                Logger.w("$TAG 上传分片异常: ${e.message}")
            }
            false
        }
    }

    /** 打包：与中继协议一致的大端二进制包 [4B count][每条: 8B seq(0)][1B flags][4B len][data] */
    private fun packBatch(batch: List<Packet>): ByteArray {
        val out = ByteArrayOutputStream()
        writeBE32(out, batch.size)
        for (p in batch) {
            writeBE64(out, 0L)                 // seq 由服务端分配
            out.write(if (p.key) 1 else 0)     // flags & 1 = 关键帧分片
            writeBE32(out, p.data.size)
            out.write(p.data)
        }
        return out.toByteArray()
    }

    private fun writeBE32(out: OutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeBE64(out: OutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) out.write(((v ushr shift) and 0xFF).toInt())
    }
}