package com.ollamarust

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(SetupInterface(), "Android")
        }

        setContentView(webView)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        webView.loadUrl("file:///android_asset/setup.html")
    }

    inner class SetupInterface {

        @JavascriptInterface
        fun checkTermuxInstalled(): Boolean {
            return TermuxHelper.isTermuxInstalled(this@SetupActivity)
        }

        @JavascriptInterface
        fun installTermux() {
            // Open F-Droid or GitHub for Termux
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://f-droid.org/packages/com.termux/")
            }
            startActivity(intent)
        }

        @JavascriptInterface
        fun openTermux() {
            TermuxHelper.openTermux(this@SetupActivity)
        }

        @JavascriptInterface
        fun runTermuxSetup() {
            TermuxHelper.runSetupCommands(this@SetupActivity)
        }

        @JavascriptInterface
        fun checkOllamaInstalled() {
            lifecycleScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url("${OllamaApp.instance.getOllamaHost()}/api/tags")
                            .build()
                        client.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) {
                        false
                    }
                }
                webView.evaluateJavascript("window.onOllamaCheck($installed)", null)
            }
        }

        @JavascriptInterface
        fun startOllama() {
            TermuxHelper.startOllama(this@SetupActivity)
            // Check after a delay
            lifecycleScope.launch {
                delay(3000)
                checkOllamaInstalled()
            }
        }

        @JavascriptInterface
        fun completeSetup() {
            OllamaApp.instance.setTermuxSetupComplete(true)
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
            finish()
        }

        @JavascriptInterface
        fun skipSetup() {
            // Allow skipping for users who want to use remote Ollama
            OllamaApp.instance.setTermuxSetupComplete(true)
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
            finish()
        }

        @JavascriptInterface
        fun setOllamaHost(host: String) {
            OllamaApp.instance.setOllamaHost(host)
        }

        @JavascriptInterface
        fun getOllamaHost(): String {
            return OllamaApp.instance.getOllamaHost()
        }
    }
}
