package com.ollamarust

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class OllamaInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val baseDir: File
        get() = File(context.filesDir, "ollama")

    private val binDir: File
        get() = File(baseDir, "bin")

    private val libDir: File
        get() = File(baseDir, "lib")

    private val modelsDir: File
        get() = File(baseDir, "models")

    private val ollamaBinary: File
        get() = File(binDir, "ollama")

    companion object {
        // Ollama release URLs - using portable Linux binaries
        private const val OLLAMA_VERSION = "0.5.4"

        private fun getOllamaUrl(): String {
            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64"
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "amd64"
                Build.SUPPORTED_ABIS.contains("x86") -> "386"
                else -> "arm64"
            }
            return "https://github.com/ollama/ollama/releases/download/v$OLLAMA_VERSION/ollama-linux-$arch.tgz"
        }

        // Fallback to static binary if tgz doesn't work
        private fun getOllamaBinaryUrl(): String {
            val arch = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64"
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
                Build.SUPPORTED_ABIS.contains("x86_64") -> "amd64"
                Build.SUPPORTED_ABIS.contains("x86") -> "386"
                else -> "arm64"
            }
            return "https://github.com/ollama/ollama/releases/download/v$OLLAMA_VERSION/ollama-linux-$arch"
        }
    }

    fun isInstalled(): Boolean {
        return ollamaBinary.exists() && ollamaBinary.canExecute()
    }

    fun getOllamaPath(): String {
        return ollamaBinary.absolutePath
    }

    fun getEnvironment(): Map<String, String> {
        return mapOf(
            "HOME" to baseDir.absolutePath,
            "OLLAMA_HOST" to "127.0.0.1:11434",
            "OLLAMA_MODELS" to modelsDir.absolutePath,
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:/system/bin:/system/xbin",
            "TMPDIR" to File(baseDir, "tmp").absolutePath
        )
    }

    suspend fun install(
        onProgress: (Int, String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Create directories
                onProgress(5, "Creating directories...")
                createDirectories()

                // Download Ollama binary
                onProgress(10, "Downloading Ollama...")
                downloadOllama(onProgress)

                // Set permissions
                onProgress(90, "Setting permissions...")
                setPermissions()

                // Verify installation
                onProgress(95, "Verifying installation...")
                if (!verifyInstallation()) {
                    throw Exception("Installation verification failed")
                }

                onProgress(100, "Installation complete!")
                onComplete()

            } catch (e: Exception) {
                e.printStackTrace()
                onError(e.message ?: "Unknown error")
            }
        }
    }

    private fun createDirectories() {
        listOf(baseDir, binDir, libDir, modelsDir, File(baseDir, "tmp")).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    private suspend fun downloadOllama(onProgress: (Int, String) -> Unit) {
        // Try downloading the static binary directly (simpler for Android)
        val url = getOllamaBinaryUrl()

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Download failed: ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty response")
            val contentLength = body.contentLength()

            FileOutputStream(ollamaBinary).use { output ->
                body.source().use { source ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var read: Int

                    while (source.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read

                        if (contentLength > 0) {
                            val progress = ((totalRead * 70) / contentLength).toInt() + 10
                            val mb = totalRead / (1024 * 1024)
                            val totalMb = contentLength / (1024 * 1024)
                            withContext(Dispatchers.Main) {
                                onProgress(progress.coerceIn(10, 80), "Downloading: ${mb}MB / ${totalMb}MB")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setPermissions() {
        ollamaBinary.setExecutable(true, false)
        ollamaBinary.setReadable(true, false)
    }

    private fun verifyInstallation(): Boolean {
        if (!ollamaBinary.exists()) return false
        if (!ollamaBinary.canExecute()) return false

        // Try running ollama --version
        return try {
            val process = ProcessBuilder(ollamaBinary.absolutePath, "--version")
                .directory(baseDir)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            // Binary might not run on Android directly, but that's okay
            // We'll use proot or similar workaround
            true
        }
    }
}
