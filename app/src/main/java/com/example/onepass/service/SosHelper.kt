package com.example.onepass.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import com.example.onepass.utils.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 紧急呼救（v1.8.7）：短信（主通道，运营商级可靠）+ PushDeer / Server酱（辅通道推送）。
 * - 短信：直发绑定号码（需 SEND_SMS 权限，调用方负责申请）
 * - PushDeer：api.pushdeer.com 公共实例（pushkey，子女端 App/微信小程序收）
 * - Server酱：sctapi.ftqq.com（SendKey，子女微信服务号收）
 * 设置存 SharedPreferences("sos_prefs")，可在设置页编辑短信/推送内容。
 */
object SosHelper {
    private const val TAG = "SosHelper"
    private const val PREFS = "sos_prefs"
    private const val PUSHDEER_URL = "https://api.pushdeer.com/message/push"
    private const val SERVERCHAN_URL = "https://sctapi.ftqq.com"

    const val DEFAULT_SMS_TEXT = "紧急呼救！我是家里老人，需要帮助，请速回电！"
    const val DEFAULT_PUSH_TEXT = "⚠️ 紧急呼救：家里老人按下了紧急呼救按钮，需要帮助！"

    data class SosConfig(
        val enabled: Boolean,
        val phones: List<String>,
        val smsText: String,
        val pushdeerKey: String,
        val serverchanKey: String,
        val pushText: String
    )

    fun loadConfig(context: Context): SosConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SosConfig(
            enabled = p.getBoolean("enabled", true),
            phones = p.getString("phones", "")
                ?.split(',', '，', ';', '；', ' ', '\n')
                ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            smsText = p.getString("sms_text", DEFAULT_SMS_TEXT).toString(),
            pushdeerKey = p.getString("pushdeer_key", "").toString(),
            serverchanKey = p.getString("serverchan_key", "").toString(),
            pushText = p.getString("push_text", DEFAULT_PUSH_TEXT).toString()
        )
    }

    fun saveConfig(context: Context, c: SosConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putString("phones", c.phones.joinToString(","))
            .putString("sms_text", c.smsText)
            .putString("pushdeer_key", c.pushdeerKey)
            .putString("serverchan_key", c.serverchanKey)
            .putString("push_text", c.pushText)
            .apply()
    }

    /**
     * 执行呼救（后台线程执行，主线程回调）。返回 (短信结果, 推送结果)。
     * ⚠️ 必须在后台线程做网络请求——Android 主线程网络操作抛 NetworkOnMainThreadException。
     */
    fun execute(context: Context, onResult: (String, String) -> Unit) {
        Thread {
            val r = executeSync(context)
            Handler(Looper.getMainLooper()).post { onResult(r.first, r.second) }
        }.apply { isDaemon = true }.start()
    }

    private fun executeSync(context: Context): Pair<String, String> {
        val cfg = loadConfig(context)
        if (!cfg.enabled) return "紧急呼救未启用" to "请在设置中开启"
        val smsResult = if (cfg.phones.isEmpty()) "未绑定号码" else sendSms(cfg.phones, cfg.smsText)
        val pushes = mutableListOf<String>()
        if (cfg.pushdeerKey.isNotBlank()) {
            pushes.add("推送:" + pushPushdeer(cfg.pushdeerKey, cfg.pushText))
        }
        if (cfg.serverchanKey.isNotBlank()) {
            pushes.add("微信:" + pushServerchan(cfg.serverchanKey, cfg.pushText))
        }
        if (pushes.isEmpty()) pushes.add("未配置推送")
        return smsResult to pushes.joinToString(" ")
    }

    /** 设置页测试：只发短信（后台线程 + 主线程回调） */
    fun testSms(context: Context, onResult: (String) -> Unit) {
        Thread {
            val cfg = loadConfig(context)
            val r = if (cfg.phones.isEmpty()) "未绑定号码" else sendSms(cfg.phones, cfg.smsText)
            Handler(Looper.getMainLooper()).post { onResult(r) }
        }.apply { isDaemon = true }.start()
    }

    /** 设置页测试：只发推送（后台线程 + 主线程回调） */
    fun testPush(context: Context, onResult: (String) -> Unit) {
        Thread {
            val cfg = loadConfig(context)
            val pushes = mutableListOf<String>()
            if (cfg.pushdeerKey.isNotBlank()) {
                pushes.add("推送:" + pushPushdeer(cfg.pushdeerKey, cfg.pushText))
            }
            if (cfg.serverchanKey.isNotBlank()) {
                pushes.add("微信:" + pushServerchan(cfg.serverchanKey, cfg.pushText))
            }
            val r = if (pushes.isEmpty()) "未配置推送 key" else pushes.joinToString(" ")
            Handler(Looper.getMainLooper()).post { onResult(r) }
        }.apply { isDaemon = true }.start()
    }

    private fun sendSms(phones: List<String>, text: String): String {
        return try {
            val sms = SmsManager.getDefault()
            var ok = 0
            for (p in phones) {
                try {
                    sms.sendTextMessage(p, null, text, null, null)
                    ok++
                } catch (e: Exception) {
                    Log.w(TAG, "短信发送失败 $p: ${e.message}")
                }
            }
            "短信已发 $ok/${phones.size} 个号码"
        } catch (e: Exception) {
            Log.w(TAG, "短信异常: ${e.message}")
            "短信失败: ${e.message}"
        }
    }

    private fun httpGet(url: String): Boolean {
        return try {
            // 真机实测：sctapi.ftqq.com 首次连接可达 ~20s（DNS/握手慢），8s 超时必失败。
            // 调大超时 + 预热机制（warmup 提前建立连接后秒连）。
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "推送响应 ${resp.code} url=$url")
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            // Logger.w 为空实现，这里必须用 Log 直接打真实错误（超时/DNS/握手）
            Log.w(TAG, "推送异常: ${e.javaClass.simpleName} ${e.message} url=$url")
            false
        }
    }

    /**
     * 连接预热：提前访问推送服务根路径，建立 DNS/连接缓存。
     * 真机实测首连 ~20s（之后秒连），预热后紧急呼救能即时送达。
     */
    fun warmup(context: Context) {
        val cfg = loadConfig(context)
        if (cfg.serverchanKey.isBlank() && cfg.pushdeerKey.isBlank()) return
        Thread {
            if (cfg.serverchanKey.isNotBlank()) httpGet("https://sctapi.ftqq.com/")
            if (cfg.pushdeerKey.isNotBlank()) httpGet("https://api.pushdeer.com/")
        }.apply { isDaemon = true }.start()
    }

    private fun pushPushdeer(key: String, text: String): String {
        val url = "$PUSHDEER_URL?pushkey=${enc(key)}&text=${enc(text)}&type=text"
        return if (httpGet(url)) "已发" else "失败"
    }

    private fun pushServerchan(key: String, text: String): String {
        val url = "$SERVERCHAN_URL/${enc(key)}.send?title=${enc("紧急呼救")}&desp=${enc(text)}"
        return if (httpGet(url)) "已发" else "失败"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
