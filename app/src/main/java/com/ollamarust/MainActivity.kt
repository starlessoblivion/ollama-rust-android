package com.ollamarust

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var currentEventSource: EventSource? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if setup is complete - use new onboarding
        if (!OllamaApp.instance.isSetupComplete()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Start Ollama service automatically
        startOllamaService()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.databaseEnabled = true
            settings.setSupportZoom(false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject saved preferences
                    injectSavedPreferences()
                }
            }

            webChromeClient = WebChromeClient()

            addJavascriptInterface(OllamaInterface(), "Android")
        }

        setContentView(webView)

        // Make fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun injectSavedPreferences() {
        val prefs = OllamaApp.instance.prefs
        val model = prefs.getString(OllamaApp.KEY_SELECTED_MODEL, "") ?: ""
        val theme = prefs.getString(OllamaApp.KEY_THEME, "dark") ?: "dark"
        val braveKey = prefs.getString(OllamaApp.KEY_BRAVE_API_KEY, "") ?: ""
        val searchEnabled = prefs.getBoolean(OllamaApp.KEY_WEB_SEARCH_ENABLED, false)

        webView.evaluateJavascript("""
            (function() {
                if (typeof window.loadPreferences === 'function') {
                    window.loadPreferences({
                        model: '$model',
                        theme: '$theme',
                        braveApiKey: '$braveKey',
                        webSearchEnabled: $searchEnabled
                    });
                }
            })();
        """.trimIndent(), null)
    }

    inner class OllamaInterface {

        @JavascriptInterface
        fun sendMessage(model: String, prompt: String) {
            lifecycleScope.launch {
                streamResponse(model, prompt)
            }
        }

        @JavascriptInterface
        fun cancelStream() {
            currentEventSource?.cancel()
            currentEventSource = null
        }

        @JavascriptInterface
        fun getModels() {
            lifecycleScope.launch {
                fetchModels()
            }
        }

        @JavascriptInterface
        fun pullModel(modelName: String) {
            lifecycleScope.launch {
                pullModelFromOllama(modelName)
            }
        }

        @JavascriptInterface
        fun deleteModel(modelName: String) {
            lifecycleScope.launch {
                deleteModelFromOllama(modelName)
            }
        }

        @JavascriptInterface
        fun savePreference(key: String, value: String) {
            OllamaApp.instance.prefs.edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun saveBoolPreference(key: String, value: Boolean) {
            OllamaApp.instance.prefs.edit().putBoolean(key, value).apply()
        }

        @JavascriptInterface
        fun checkOllamaStatus(): Boolean {
            return try {
                val request = Request.Builder()
                    .url("${OllamaApp.instance.getOllamaHost()}/api/tags")
                    .build()
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun startOllama() {
            startOllamaService()
        }

        @JavascriptInterface
        fun stopOllama() {
            stopOllamaService()
        }

        @JavascriptInterface
        fun isOllamaInstalled(): Boolean {
            return OllamaApp.instance.ollamaInstaller.isInstalled()
        }

        @JavascriptInterface
        fun isUsingRemoteServer(): Boolean {
            return OllamaApp.instance.isUsingRemoteServer()
        }

        @JavascriptInterface
        fun testBraveApi(apiKey: String) {
            lifecycleScope.launch {
                testBraveSearch(apiKey)
            }
        }

        @JavascriptInterface
        fun braveSearch(query: String, apiKey: String) {
            lifecycleScope.launch {
                performBraveSearch(query, apiKey)
            }
        }

        @JavascriptInterface
        fun openSettings() {
            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
        }
    }

    private suspend fun streamResponse(model: String, prompt: String) {
        withContext(Dispatchers.IO) {
            try {
                val ollamaHost = OllamaApp.instance.getOllamaHost()
                val requestBody = gson.toJson(mapOf(
                    "model" to model,
                    "prompt" to prompt,
                    "stream" to true
                )).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$ollamaHost/api/generate")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "window.onStreamError('Failed to connect to Ollama')",
                            null
                        )
                    }
                    return@withContext
                }

                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotBlank()) {
                            try {
                                val json = gson.fromJson(line, Map::class.java)
                                val text = json["response"] as? String ?: ""
                                val done = json["done"] as? Boolean ?: false

                                withContext(Dispatchers.Main) {
                                    val escapedText = text
                                        .replace("\\", "\\\\")
                                        .replace("'", "\\'")
                                        .replace("\n", "\\n")
                                        .replace("\r", "\\r")
                                    webView.evaluateJavascript(
                                        "window.onStreamChunk('$escapedText', $done)",
                                        null
                                    )
                                }

                                if (done) break
                            } catch (e: Exception) {
                                // Skip malformed JSON lines
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.onStreamError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    private suspend fun fetchModels() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${OllamaApp.instance.getOllamaHost()}/api/tags")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "{}"
                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript(
                                "window.onModelsLoaded('${body.replace("'", "\\'")}')",
                                null
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.onModelsError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    private suspend fun pullModelFromOllama(modelName: String) {
        withContext(Dispatchers.IO) {
            try {
                val requestBody = gson.toJson(mapOf(
                    "name" to modelName,
                    "stream" to true
                )).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("${OllamaApp.instance.getOllamaHost()}/api/pull")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()

                response.body?.source()?.let { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript(
                                    "window.onPullProgress('${line.replace("'", "\\'")}')",
                                    null
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.onPullError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    private suspend fun deleteModelFromOllama(modelName: String) {
        withContext(Dispatchers.IO) {
            try {
                val requestBody = gson.toJson(mapOf(
                    "name" to modelName
                )).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("${OllamaApp.instance.getOllamaHost()}/api/delete")
                    .delete(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            webView.evaluateJavascript("window.onModelDeleted()", null)
                        } else {
                            webView.evaluateJavascript(
                                "window.onDeleteError('Failed to delete model')",
                                null
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.onDeleteError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    private suspend fun testBraveSearch(apiKey: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.search.brave.com/res/v1/web/search?q=test")
                    .header("X-Subscription-Token", apiKey)
                    .build()

                client.newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "window.onBraveTestResult(${response.isSuccessful})",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("window.onBraveTestResult(false)", null)
                }
            }
        }
    }

    private suspend fun performBraveSearch(query: String, apiKey: String) {
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=5")
                    .header("X-Subscription-Token", apiKey)
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "window.onBraveSearchResult('${body.replace("'", "\\'").replace("\n", "")}')",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "window.onBraveSearchError('${e.message?.replace("'", "\\'")}')",
                        null
                    )
                }
            }
        }
    }

    private fun startOllamaService() {
        val intent = Intent(this, OllamaService::class.java).apply {
            action = OllamaService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOllamaService() {
        val intent = Intent(this, OllamaService::class.java).apply {
            action = OllamaService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        currentEventSource?.cancel()
        super.onDestroy()
    }
}
