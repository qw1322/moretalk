package com.example.onepass.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.onepass.R
import com.example.onepass.tv.data.BiliAccount

/**
 * B站登录页（WebView）。
 *
 * 为什么用 WebView 而不是让用户手抄 cookie：
 *   老人/家属只要输一次账号密码，App 自己从 [CookieManager] 里取 `SESSDATA` 存本地 —— 不用开 F12，
 *   也避免 cookie 在聊天工具里来回贴（那等于把账号凭证发出去）。
 *
 * 两个实现要点：
 *   1. **必须去掉 UA 里的 `; wv`** —— 否则 B站 识别为 WebView，登录流程可能被拒。
 *   2. 登录成功后 B站 会跳回 `www.bilibili.com`，此时 cookie 已落到 [CookieManager]，
 *      在 `onPageFinished` 里取即可（不依赖任何页面元素，页面改版也不影响）。
 *
 * 兜底：设置页另留了「手动粘贴 SESSDATA」入口 —— WebView 登录若被 B站 风控拦住，
 * 用户仍可从电脑浏览器复制粘贴。
 */
class BiliLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var captured = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bili_login)

        webView = findViewById(R.id.biliLoginWebView)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 去掉 WebView 标记，让 B站 走正常网页登录流程
            userAgentString = userAgentString.replace("; wv", "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                tryCapture()
            }
        }

        webView.loadUrl("https://passport.bilibili.com/login")
    }

    /** 每加载完一页就查一次 cookie；拿到 SESSDATA 即保存并返回 */
    private fun tryCapture() {
        if (captured) return
        val cookie = runCatching {
            CookieManager.getInstance().getCookie("https://www.bilibili.com")
        }.getOrNull() ?: return
        val sess = cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("SESSDATA=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
            ?: return

        captured = true
        BiliAccount.saveSessData(this, sess)
        Log.d("BiliLogin", "已保存 SESSDATA（长度 ${sess.length}）")
        Toast.makeText(this, "登录成功，已保存账号", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
