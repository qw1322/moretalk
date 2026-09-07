package com.example.onepass.service

import com.example.onepass.utils.Logger
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * 远程协助 WebSocket 指令处理器（浏览器/家属端 → 手机）。
 * LAN（NanoWSD）与公网隧道（OkHttp）共用同一套逻辑。
 */
class RemoteAssistWsHandler(private val service: RemoteAssistService) {

    interface WsSink {
        fun sendText(text: String)
        fun sendBinary(bytes: ByteArray)
    }

    fun handle(sink: WsSink, message: String) {
        try {
            val obj = JSONObject(message)
            when (obj.optString("op")) {
                "status" -> {
                    val resp = JSONObject()
                        .put("op", "status")
                        .put("w", service.screenWidth)
                        .put("h", service.screenHeight)
                    sink.sendText(resp.toString())
                }

                "frame" -> {
                    val jpeg = service.latestJpeg
                    if (jpeg != null) {
                        val age = (System.currentTimeMillis() - service.lastFrameAt)
                            .toInt().coerceIn(0, 60000)
                        val buf = ByteBuffer.allocate(4 + jpeg.size)
                            .putInt(age)
                            .put(jpeg)
                            .array()
                        sink.sendBinary(buf)
                    }
                    // 无帧则不回（浏览器继续请求即可）
                }

                "tap" -> {
                    val x = obj.optDouble("x").toFloat()
                    val y = obj.optDouble("y").toFloat()
                    val ok = SelectToSpeakService.performTap(x, y)
                    sink.sendText(JSONObject().put("op", "result").put("ok", ok).toString())
                }

                "swipe" -> {
                    val x1 = obj.optDouble("x1").toFloat()
                    val y1 = obj.optDouble("y1").toFloat()
                    val x2 = obj.optDouble("x2").toFloat()
                    val y2 = obj.optDouble("y2").toFloat()
                    val d = obj.optLong("d", 250L).coerceIn(50L, 2000L)
                    val ok = SelectToSpeakService.performSwipe(x1, y1, x2, y2, d)
                    sink.sendText(JSONObject().put("op", "result").put("ok", ok).toString())
                }

                // 家属页能力协商：h264 = 浏览器支持 WebCodecs（手机切 H.264 硬编码流）；
                // mjpeg = 不支持（手机切 JPEG 帧流，兼容一切浏览器与老手机端）
                "mode" -> {
                    val h264 = obj.optString("mode") == "h264"
                    service.switchMode(h264)
                }
            }
        } catch (e: Exception) {
            Logger.w("RemoteAssistWs 解析失败: ${e.message}")
        }
    }
}
