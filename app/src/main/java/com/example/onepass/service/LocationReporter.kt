package com.example.onepass.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 定位上报（v1.9.1）：周期获取手机位置并上报到自托管 Traccar 服务器，
 * 子女在 Traccar 网页实时查看老人位置与历史轨迹。
 *
 * 协议：Traccar 的 osmand HTTP 上报 —— GET /?id=设备ID&lat=..&lon=..&timestamp=..
 * 服务器地址与设备 ID 在设置页可配；默认指向本项目 Traccar（腾讯地图中文版）。
 */
class LocationReporter(private val context: Context) {

    companion object {
        private const val TAG = "LocationReporter"
        private const val PREFS = "location_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SERVER = "server"
        private const val KEY_DEVICE_ID = "device_id"
        private const val DEFAULT_SERVER = "http://39.105.137.174:5055"
        private const val DEFAULT_DEVICE_ID = "MT963361"
        /** 上报周期：60 秒一次（电池友好，实时性足够） */
        private const val REPORT_INTERVAL_MS = 60_000L
        /** 最后已知位置超过此时间则触发一次单次定位刷新 */
        private const val STALE_MS = 10 * 60 * 1000L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun server(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER)!!

        fun deviceId(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID)!!

        fun save(context: Context, enabled: Boolean, server: String, deviceId: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_SERVER, server.trim().ifBlank { DEFAULT_SERVER })
                .putString(KEY_DEVICE_ID, deviceId.trim().ifBlank { DEFAULT_DEVICE_ID })
                .apply()
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false

    fun start() {
        if (running) return
        if (!isEnabled(context)) return
        running = true
        thread = HandlerThread("LocationReporter").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post(reportRunnable)
        Log.d(TAG, "定位上报已启动，目标 ${server(context)} id=${deviceId(context)}")
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                reportOnce()
            } catch (e: Exception) {
                Log.w(TAG, "上报异常: ${e.message}")
            }
            handler?.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private fun reportOnce() {
        val loc = getLocation() ?: run {
            Log.d(TAG, "暂无定位，跳过本轮")
            return
        }
        val url = buildString {
            append(server(context))
            append("/?id=").append(deviceId(context))
            append("&lat=").append(loc.latitude)
            append("&lon=").append(loc.longitude)
            append("&timestamp=").append(System.currentTimeMillis())
            if (loc.hasAccuracy()) append("&accuracy=").append(loc.accuracy)
            if (loc.hasAltitude()) append("&altitude=").append(loc.altitude)
            if (loc.hasSpeed()) append("&speed=").append(loc.speed)
            if (loc.hasBearing()) append("&bearing=").append(loc.bearing)
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "已上报 ${loc.latitude},${loc.longitude} -> ${resp.code}")
                } else {
                    Log.w(TAG, "上报失败 HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "上报网络异常: ${e.message}")
        }
    }

    /** 先取最后已知位置；超过 10 分钟则触发一次单次定位刷新（等待最多 5 秒） */
    private fun getLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val last = providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < STALE_MS) return last

        // 位置太旧或没有：请求一次网络定位（室内 WiFi/基站可用，1~5 秒）
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                fresh = l
                latch.countDown()
            }

            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            latch.await(5, TimeUnit.SECONDS)
            lm.removeUpdates(listener)
        } catch (e: Exception) {
            Log.w(TAG, "单次定位异常: ${e.message}")
        }
        return fresh ?: last
    }
}
