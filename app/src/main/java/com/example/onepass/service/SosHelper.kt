package com.example.onepass.service

import android.content.Context
import android.content.Intent
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
        val smsResult = if (cfg.phones.isEmpty()) "未绑定号码" else sendSms(context, cfg.phones, cfg.smsText)
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
            val r = if (cfg.phones.isEmpty()) "未绑定号码" else sendSms(context, cfg.phones, cfg.smsText)
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

    private fun sendSms(context: Context, phones: List<String>, text: String): String {
        val results = mutableListOf<String>()
        try {
            val sms = SmsManager.getDefault()
            for (p in phones) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = "未知"
                // 路径1：带发送回调（能拿到真实结果；但 realme/ColorOS 对带 sentIntent
                // 的第三方短信有拦截——返回 RESULT_ERROR_GENERIC_FAILURE）
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        result = when (resultCode) {
                            android.app.Activity.RESULT_OK -> "成功"
                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "通用失败"
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "无服务"
                            SmsManager.RESULT_ERROR_NULL_PDU -> "空PDU"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "飞行模式"
                            else -> "错误码$resultCode"
                        }
                        latch.countDown()
                    }
                }
                try {
                    val filter = android.content.IntentFilter("com.example.onepass.SMS_SENT")
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        context.registerReceiver(receiver, filter)
                    }
                    val sentPI = android.app.PendingIntent.getBroadcast(
                        context, 0,
                        Intent("com.example.onepass.SMS_SENT"),
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    sms.sendTextMessage(p, null, text, sentPI, null)
                    latch.await(6, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    result = "异常:${e.message}"
                } finally {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                if (result == "通用失败") {
                    // 路径2：realme 拦截带 sentIntent 的发送 → 退回无回调直发
                    // （v1.9.0 时代验证可行：弹系统确认框，同意后正常送达）
                    try {
                        sms.sendTextMessage(p, null, text, null, null)
                        results.add("$p:已送系统确认")
                        Log.w(TAG, "路径1被ROM拦截，已退回无回调直发 $p")
                    } catch (e: Exception) {
                        results.add("$p:异常:${e.message}")
                    }
                } else {
                    results.add("$p:$result")
                }
            }
            return "短信 " + results.joinToString(" ")
        } catch (e: Exception) {
            Log.w(TAG, "短信异常: ${e.message}")
            return "短信失败: ${e.message}"
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
