package com.example.onepass.service

import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

/**
 * 极简 UPnP IGD 客户端：SSDP 发现路由器 → WANIPConnection 服务
 * GetExternalIPAddress / AddPortMapping，实现 NAT 端口映射（外网直连）。
 */
object UpnpManager {

    data class MappingResult(
        val publicIp: String?,
        val externalPort: Int?,
        val message: String
    ) {
        val publicUrl: String?
            get() = if (publicIp != null && externalPort != null) "http://$publicIp:$externalPort" else null
    }

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val WAN_IP_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1"
    private const val WAN_PPP_SERVICE = "urn:schemas-upnp-org:service:WANPPPConnection:1"
    private const val DISCOVER_TIMEOUT_MS = 5000L

    /**
     * 在后台线程执行端口映射，结果通过回调返回。
     */
    fun mapPort(
        internalPort: Int,
        internalIp: String,
        externalPort: Int = internalPort,
        onResult: (MappingResult) -> Unit
    ) {
        thread(isDaemon = true) {
            val result = runCatching {
                val device = discoverDevice()
                if (device == null) {
                    MappingResult(null, null, "未发现支持 UPnP 的路由器（可能未开启 UPnP）")
                } else {
                    val extIp = getExternalIp(device.controlUrl, device.serviceType)
                    val ok = addPortMapping(
                        device.controlUrl, device.serviceType,
                        externalPort, internalPort, internalIp
                    )
                    MappingResult(
                        extIp?.ifBlank { null },
                        if (ok) externalPort else null,
                        when {
                            ok -> "端口映射成功（公网 IP: $extIp）"
                            extIp == null -> "路由器不支持 WANIPConnection"
                            else -> "端口映射失败（公网 IP: $extIp）"
                        }
                    )
                }
            }.getOrElse { e ->
                MappingResult(null, null, "UPnP 异常: ${e.message}")
            }
            onResult(result)
        }
    }

    private data class IgdDevice(val controlUrl: String, val serviceType: String)

    /**
     * SSDP 发现 IGD，并读取设备描述找到 WANIPConnection 的 controlURL。
     */
    private fun discoverDevice(): IgdDevice? {
        val socket = DatagramSocket()
        socket.soTimeout = 4000
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
            ).toByteArray()
        socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
        val buf = ByteArray(4096)
        val end = System.currentTimeMillis() + DISCOVER_TIMEOUT_MS
        while (System.currentTimeMillis() < end) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                break
            }
            val text = String(packet.data, 0, packet.length)
            val loc = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trimEnd('\r')
                ?: continue
            val pair = parseDescription(loc)
            if (pair != null) return pair
        }
        return null
    }

    /**
     * 读取 IGD 设备描述 XML，定位 WANIPConnection/WANPPPConnection 服务的 controlURL。
     */
    private fun parseDescription(location: String): IgdDevice? {
        val base = URL(location)
        val conn = base.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode !in 200..299) return null
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(conn.inputStream, "UTF-8")
        var serviceType: String? = null
        var controlPath: String? = null
        var event: Int
        while (parser.next().also { event = it } != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "serviceType" -> {
                        val t = parser.nextText()
                        if (t == WAN_IP_SERVICE || t == WAN_PPP_SERVICE) serviceType = t
                    }
                    "controlURL" -> {
                        if (serviceType != null && controlPath == null) controlPath = parser.nextText()
                    }
                }
            }
        }
        conn.inputStream.close()
        val st = serviceType ?: return null
        val path = controlPath ?: return null
        val controlUrl = if (path.startsWith("http")) path else {
            URL(base.protocol, base.host, base.port, path).toString()
        }
        return IgdDevice(controlUrl, st)
    }

    private fun getExternalIp(controlUrl: String, serviceType: String): String? {
        val resp = soapCall(controlUrl, serviceType, "GetExternalIPAddress", "") ?: return null
        return Regex("<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>").find(resp)?.groupValues?.get(1)
    }

    private fun addPortMapping(
        controlUrl: String,
        serviceType: String,
        externalPort: Int,
        internalPort: Int,
        internalIp: String
    ): Boolean {
        val args = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$externalPort</NewExternalPort>")
            append("<NewProtocol>TCP</NewProtocol>")
            append("<NewInternalPort>$internalPort</NewInternalPort>")
            append("<NewInternalClient>$internalIp</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>MoreTalk Remote Assist</NewPortMappingDescription>")
            append("<NewLeaseDuration>0</NewLeaseDuration>")
        }
        val resp = soapCall(controlUrl, serviceType, "AddPortMapping", args) ?: return false
        // 成功响应包含 <s:Envelope> 或 AddPortMappingResponse（注意前缀 s:）
        return resp.contains("AddPortMappingResponse") || resp.contains("Envelope", ignoreCase = true)
    }

    private fun soapCall(controlUrl: String, serviceType: String, action: String, argsXml: String): String? {
        return try {
            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
            val body = (
                "<?xml version=\"1.0\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body><u:$action xmlns:u=\"$serviceType\">$argsXml</u:$action></s:Body></s:Envelope>"
                ).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
    }
}
