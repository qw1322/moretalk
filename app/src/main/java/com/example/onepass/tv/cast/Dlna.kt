package com.example.onepass.tv.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLNA / UPnP AV 投屏（零依赖手写实现）。
 *
 * 为什么自己写而不引第三方库：
 *   投屏这件事拆开只有两步 —— 「**在局域网里找到电视**」（SSDP 发现）和
 *   「**让电视去播这个地址**」（AVTransport 两个 SOAP 请求）。
 *   引 Cling/jUPnP 会带进一大堆用不到的能力和 Java 8 脱糖坑，收益不成正比；
 *   而这两步一共不到 300 行，还完全可控。
 *
 * 协议链路（控制端 = 手机，被控端 = 电视/投屏盒）：
 *   1. 组播发 M-SEARCH（SSDP），电视单播回一个 `LOCATION: http://<ip>:<port>/desc.xml`
 *   2. GET 那个 desc.xml，从里头挑出 AVTransport 服务的 `controlURL`
 *   3. POST 两个 SOAP 请求到 controlURL：
 *        SetAVTransportURI（把直播地址塞给电视）→ Play（开播）
 *
 * ⚠️ 已知限制（务必如实告知用户，别让人以为是坏了）：
 *   · 电视必须**和手机连同一个 Wi-Fi**，且电视本身支持 DLNA 投屏；
 *   · 部分电视的 DLNA 只认 mp4，**不认 HLS(m3u8) 直播** —— 这时会提示投屏失败；
 *   · 有些直播源按 IP 授权（只放行手机所在运营商/内网），电视的 IP 拿不到流，
 *     表现是「投过去电视在转圈」，属源侧限制，App 无能为力。
 */
object Dlna {

    private const val TAG = "Dlna"

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** 单个电视的 desc.xml 拉取超时 */
    private const val HTTP_TIMEOUT_MS = 3_000

    private val M_SEARCH: ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $MEDIA_RENDERER\r\n" +
            "USER-AGENT: MoreTalk/1.9.5 UPnP/1.0\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    // ------------------------------------------------------------------ 发现

    /**
     * 在局域网里搜索投屏设备（阻塞式，请放子线程）。
     *
     * @param timeoutMs 搜索总时长（建议 ≥3000ms，电视响应通常 1~2 秒内回来）
     * @param onDevice  每发现一台回调一次（**可能在任意线程**，UI 层自行切主线程）
     * @param onFinished 搜索结束回调（同样可能是任意线程），无论如何都会调用一次
     */
    fun discover(
        context: Context,
        timeoutMs: Long,
        onDevice: (DlnaDevice) -> Unit,
        onFinished: () -> Unit
    ) {
        Thread {
            val lock = acquireMulticastLock(context)
            var socket: DatagramSocket? = null
            val seen = HashSet<String>()
            try {
                socket = DatagramSocket().apply {
                    soTimeout = 1_500
                    broadcast = true
                }
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val probe = DatagramPacket(M_SEARCH, M_SEARCH.size, group, SSDP_PORT)
                // 组播 UDP 会丢包，连发三次显著提高发现率
                repeat(3) {
                    runCatching { socket.send(probe) }
                    Thread.sleep(120)
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        continue // 超时/中断都继续轮询，直到 deadline
                    }
                    val response = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val location = headerValue(response, "LOCATION") ?: continue
                    if (!seen.add(location)) continue

                    val device = runCatching { resolveDevice(location) }
                        .onFailure { Log.w(TAG, "解析描述失败 $location: ${it.javaClass.simpleName}") }
                        .getOrNull() ?: continue
                    onDevice(device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSDP 搜索失败: ${e.message}")
            } finally {
                runCatching { socket?.close() }
                lock?.release()
                onFinished()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 拉取设备描述 XML，取出友好名和 AVTransport 的 controlURL。
     * controlURL 可能是相对路径，要相对 LOCATION 解析成绝对地址。
     */
    private fun resolveDevice(location: String): DlnaDevice? {
        val xml = httpGet(location) ?: return null
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var friendlyName: String? = null
        var avtControl: String? = null
        var inService = false
        var serviceIsAvt = false
        var serviceControl: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser)) {
                    // 只在还没取到时写入，避免被嵌套子设备的 friendlyName 覆盖
                    "friendlyname" -> if (friendlyName == null) friendlyName = readText(parser)
                    "service" -> {
                        inService = true
                        serviceIsAvt = false
                        serviceControl = null
                    }
                    "servicetype" -> if (inService) serviceIsAvt = readText(parser).trim() == AVT
                    "controlurl" -> if (inService) serviceControl = readText(parser).trim()
                }
                XmlPullParser.END_TAG -> if (localName(parser) == "service") {
                    // </service> 时才落定，这样 serviceType 在前在后都不怕
                    if (serviceIsAvt && avtControl == null) avtControl = serviceControl
                    inService = false
                }
            }
            event = parser.next()
        }

        val control = avtControl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("该设备没有 AVTransport 服务")
        // 相对路径 → 绝对地址（URL 的第二个参数做相对解析）
        val absolute = runCatching { URL(URL(location), control).toString() }.getOrDefault(control)
        val name = friendlyName?.trim()?.takeIf { it.isNotEmpty() } ?: "投屏设备"
        return DlnaDevice(friendlyName = name, controlUrl = absolute)
    }

    // ------------------------------------------------------------------ 投屏

    /**
     * 把 [url] 推给电视播放。**阻塞式**，请放子线程。
     *
     * @return true = 两个 SOAP 请求都成功（电视已开始拉流）
     */
    fun cast(device: DlnaDevice, url: String, title: String): Boolean {
        val metadata = didl(title, url)

        val setUri = soapEnvelope(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${escapeXml(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>"
        )
        if (!soapPost(device.controlUrl, "SetAVTransportURI", setUri)) {
            Log.w(TAG, "SetAVTransportURI 失败: ${device.friendlyName}")
            return false
        }

        val play = soapEnvelope(
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )
        val ok = soapPost(device.controlUrl, "Play", play)
        Log.d(TAG, "投屏 ${device.friendlyName}: ${if (ok) "已下发播放" else "Play 失败"}")
        return ok
    }

    /** 让电视停止播放（退出时礼貌性地收个尾，失败也不影响） */
    fun stop(device: DlnaDevice) {
        val body = soapEnvelope("Stop", "<InstanceID>0</InstanceID>")
        soapPostQuietly(device.controlUrl, "Stop", body)
    }

    // ------------------------------------------------------------------ SOAP

    private fun soapEnvelope(action: String, inner: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>" +
            "<u:$action xmlns:u=\"$AVT\">$inner</u:$action>" +
            "</s:Body></s:Envelope>"

    private fun soapPost(controlUrl: String, action: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$AVT#$action\"")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            (code in 200..299).also { if (!it) Log.w(TAG, "$action HTTP $code") }
        } catch (e: Exception) {
            Log.w(TAG, "$action 异常: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun soapPostQuietly(controlUrl: String, action: String, body: String) {
        runCatching { soapPost(controlUrl, action, body) }
    }

    // ------------------------------------------------------------------ 工具

    /** 拼一段 DIDL-Lite 元数据；协议类型按后缀猜，部分电视会校验 */
    private fun didl(title: String, url: String): String {
        val mime = when {
            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
            url.contains(".flv", ignoreCase = true) -> "video/x-flv"
            else -> "video/mp4"
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escapeXml(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${escapeXml(url)}</res>" +
            "</item></DIDL-Lite>"
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET 失败 $url: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 从 SSDP 响应头里取某个头（大小写不敏感），带原始的行尾处理 */
    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 取标签的「本地名」并转小写。
     *
     * 为什么不直接用 `parser.name`：UPnP 描述 XML 根节点带默认命名空间，
     * 但**有些厂商会给 service 里的字段加前缀**（如 `<upnp:serviceType>`），
     * 这时 `parser.name` 返回 "upnp:serviceType"，直接比较就会漏掉。
     * 统一剥掉前缀按本地名比较，兼容性最好。
     */
    private fun localName(parser: XmlPullParser): String =
        (parser.name ?: "").substringAfterLast(':').lowercase()

    /** 读到当前标签的 END_TAG 为止的文本（吞掉标签内的空白缩进） */
    private fun readText(parser: XmlPullParser): String {
        var depth = 1
        val sb = StringBuilder()
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> if (depth == 1) sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return sb.toString()
    }

    private fun escapeXml(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /**
     * 拿一个组播锁 —— 不加锁的话，很多厂商 ROM 会直接丢掉组播包，SSDP 一台都搜不到。
     * 没有 Wi-Fi 或拿不到锁时返回 null，不影响后续（有线网也能组播）。
     */
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        wifi.createMulticastLock("moretalk-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
}

/** 一台可投屏的设备 */
data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String
)
