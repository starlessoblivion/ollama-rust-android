package com.ollamarust

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    }
                    return false
                }
            }
            webChromeClient = WebChromeClient()

            addJavascriptInterface(SettingsInterface(), "Android")
        }

        setContentView(webView)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = 0xFF1e3a5f.toInt()

        webView.loadUrl("file:///android_asset/settings.html")
    }

    inner class SettingsInterface {

        @JavascriptInterface
        fun goBack() {
            finish()
        }

        @JavascriptInterface
        fun getTheme(): String {
            return OllamaApp.instance.prefs.getString(OllamaApp.KEY_THEME, "dark") ?: "dark"
        }

        @JavascriptInterface
        fun setTheme(theme: String) {
            OllamaApp.instance.prefs.edit().putString(OllamaApp.KEY_THEME, theme).apply()
        }

        @JavascriptInterface
        fun getBraveApiKey(): String {
            return OllamaApp.instance.prefs.getString(OllamaApp.KEY_BRAVE_API_KEY, "") ?: ""
        }

        @JavascriptInterface
        fun setBraveApiKey(key: String) {
            OllamaApp.instance.prefs.edit().putString(OllamaApp.KEY_BRAVE_API_KEY, key).apply()
        }

        @JavascriptInterface
        fun testBraveApi(apiKey: String) {
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val request = Request.Builder()
                            .url("https://api.search.brave.com/res/v1/web/search?q=test")
                            .header("X-Subscription-Token", apiKey)
                            .build()
                        client.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) {
                        false
                    }
                }
                webView.evaluateJavascript("window.onBraveTestResult($success)", null)
            }
        }

        @JavascriptInterface
        fun resetApp() {
            // Stop ollama service
            val stopIntent = Intent(this@SettingsActivity, OllamaService::class.java).apply {
                action = OllamaService.ACTION_STOP
            }
            startService(stopIntent)

            // Clear all preferences
            OllamaApp.instance.prefs.edit().clear().apply()

            // Delete proot data
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prootDir = File(filesDir, "proot")
                    prootDir.deleteRecursively()
                    val cacheDir = File(cacheDir, "proot-tmp")
                    cacheDir.deleteRecursively()
                } catch (e: Exception) {
                    // Ignore
                }

                withContext(Dispatchers.Main) {
                    // Restart to onboarding
                    val intent = Intent(this@SettingsActivity, OnboardingActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    override fun onBackPressed() {
        finish()
    }
}
