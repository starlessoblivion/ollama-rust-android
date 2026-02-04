package com.ollamarust

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class OllamaRunner(private val context: Context) {

    companion object {
        private const val TAG = "OllamaRunner"
        private const val OLLAMA_PORT = 11434
    }

    private val installer = OllamaInstaller(context)
    private var ollamaProcess: Process? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val isRunning: Boolean
        get() = checkServerRunning()

    fun checkServerRunning(): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://127.0.0.1:$OLLAMA_PORT/api/tags")
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun start(): Boolean {
        if (isRunning) {
            Log.d(TAG, "Ollama is already running")
            return true
        }

        if (!installer.isInstalled()) {
            Log.e(TAG, "Ollama is not installed")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val ollamaPath = installer.getOllamaPath()
                val env = installer.getEnvironment()

                // Build environment array
                val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()

                // Start ollama serve
                val processBuilder = ProcessBuilder(ollamaPath, "serve")
                    .directory(File(context.filesDir, "ollama"))
                    .redirectErrorStream(true)

                // Set environment
                processBuilder.environment().clear()
                processBuilder.environment().putAll(env)

                Log.d(TAG, "Starting Ollama: $ollamaPath serve")
                ollamaProcess = processBuilder.start()

                // Read output in background thread
                Thread {
                    try {
                        BufferedReader(InputStreamReader(ollamaProcess!!.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                Log.d(TAG, "Ollama: $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading Ollama output", e)
                    }
                }.start()

                // Wait for server to start
                var attempts = 0
                while (attempts < 30) {
                    delay(1000)
                    if (checkServerRunning()) {
                        Log.d(TAG, "Ollama server started successfully")
                        return@withContext true
                    }
                    attempts++
                }

                Log.e(TAG, "Ollama server failed to start within timeout")
                false

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Ollama", e)
                false
            }
        }
    }

    fun stop() {
        ollamaProcess?.let { process ->
            try {
                process.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    process.destroyForcibly()
                }
                Log.d(TAG, "Ollama process stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Ollama", e)
            }
        }
        ollamaProcess = null
    }

    suspend fun restart(): Boolean {
        stop()
        delay(1000)
        return start()
    }

    fun getStatus(): String {
        return when {
            isRunning -> "Running"
            installer.isInstalled() -> "Stopped"
            else -> "Not installed"
        }
    }
}
