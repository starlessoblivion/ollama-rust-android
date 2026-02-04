package com.ollamarust

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Manages Termux bootstrap environment for running native Android binaries.
 * This approach uses Termux's pre-compiled packages that work on Android
 * without needing proot or glibc.
 */
class TermuxBootstrap(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBootstrap"

        // Termux bootstrap URLs
        private const val BOOTSTRAP_BASE = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.01-r1%2Bapt.android-7"
        private const val BOOTSTRAP_AARCH64 = "$BOOTSTRAP_BASE/bootstrap-aarch64.zip"
        private const val BOOTSTRAP_ARM = "$BOOTSTRAP_BASE/bootstrap-arm.zip"
        private const val BOOTSTRAP_X86_64 = "$BOOTSTRAP_BASE/bootstrap-x86_64.zip"

        // Ollama package info
        private const val OLLAMA_PACKAGE = "ollama"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // Termux prefix directory (like /data/data/com.termux/files/usr)
    val prefixDir: File
        get() = File(context.filesDir, "usr")

    val binDir: File
        get() = File(prefixDir, "bin")

    val libDir: File
        get() = File(prefixDir, "lib")

    val tmpDir: File
        get() = File(context.filesDir, "tmp")

    val homeDir: File
        get() = File(context.filesDir, "home")

    private val ollamaBinary: File
        get() = File(binDir, "ollama")

    var lastError: String = ""
        private set

    var lastOutput: String = ""
        private set

    fun isSetup(): Boolean {
        return binDir.exists() && File(binDir, "sh").exists()
    }

    fun isOllamaInstalled(): Boolean {
        return ollamaBinary.exists()
    }

    private fun getArchitecture(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            else -> "aarch64"
        }
    }

    private fun getBootstrapUrl(): String {
        return when (getArchitecture()) {
            "aarch64" -> BOOTSTRAP_AARCH64
            "arm" -> BOOTSTRAP_ARM
            "x86_64" -> BOOTSTRAP_X86_64
            else -> BOOTSTRAP_AARCH64
        }
    }

    suspend fun setup(onProgress: (Int, String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Create directories
                prefixDir.mkdirs()
                tmpDir.mkdirs()
                homeDir.mkdirs()

                if (!isSetup()) {
                    onProgress(0, "Downloading Termux environment...")

                    val bootstrapZip = File(context.cacheDir, "bootstrap.zip")
                    downloadFile(getBootstrapUrl(), bootstrapZip) { downloaded, total ->
                        val percent = if (total > 0) ((downloaded * 40) / total).toInt() else 0
                        val mb = downloaded / (1024 * 1024)
                        onProgress(percent, "Downloading environment... ${mb}MB")
                    }

                    onProgress(40, "Extracting environment...")
                    extractBootstrap(bootstrapZip, prefixDir)
                    bootstrapZip.delete()

                    onProgress(50, "Setting up symlinks...")
                    setupSymlinks()

                    onProgress(55, "Setting permissions...")
                    setExecutablePermissions(binDir)
                    setExecutablePermissions(libDir)
                }

                onProgress(60, "Environment ready")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed: ${e.message}", e)
                lastError = e.message ?: "Setup failed"
                false
            }
        }
    }

    suspend fun installOllama(onProgress: (Int, String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isOllamaInstalled()) {
                    onProgress(100, "Ollama already installed")
                    return@withContext true
                }

                onProgress(0, "Updating package list...")
                val (updateCode, updateOut) = exec("pkg", "update", "-y")
                Log.d(TAG, "pkg update: $updateCode - $updateOut")

                if (updateCode != 0) {
                    // Try without update
                    Log.w(TAG, "Update failed, trying direct install")
                }

                onProgress(30, "Installing Ollama...")
                val (installCode, installOut) = exec("pkg", "install", "-y", OLLAMA_PACKAGE)
                Log.d(TAG, "pkg install: $installCode - $installOut")

                if (installCode != 0) {
                    lastError = "Failed to install Ollama: $installOut"
                    return@withContext false
                }

                onProgress(100, "Ollama installed")
                isOllamaInstalled()
            } catch (e: Exception) {
                Log.e(TAG, "Install failed: ${e.message}", e)
                lastError = e.message ?: "Install failed"
                false
            }
        }
    }

    fun startOllamaServe(): Process? {
        lastError = ""
        return try {
            if (!ollamaBinary.exists()) {
                lastError = "Ollama not installed"
                return null
            }

            val env = buildEnvironment()
            val command = arrayOf(ollamaBinary.absolutePath, "serve")

            Log.d(TAG, "Starting ollama serve")
            Log.d(TAG, "Command: ${command.joinToString(" ")}")
            Log.d(TAG, "Environment: ${env.joinToString(", ")}")

            val process = Runtime.getRuntime().exec(command, env, homeDir)

            // Capture output
            val outputBuilder = StringBuilder()
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.e(TAG, "Ollama stderr: $line")
                        synchronized(outputBuilder) {
                            if (outputBuilder.length < 500) outputBuilder.append(line).append("\n")
                        }
                        lastOutput = outputBuilder.toString()
                    }
                } catch (_: Exception) {}
            }.start()

            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "Ollama stdout: $line")
                    }
                } catch (_: Exception) {}
            }.start()

            process
        } catch (e: Exception) {
            lastError = e.message ?: "Failed to start"
            Log.e(TAG, "Failed to start ollama: ${e.message}", e)
            null
        }
    }

    suspend fun exec(vararg command: String): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            try {
                val shell = File(binDir, "sh")
                val fullCommand = arrayOf(
                    shell.absolutePath,
                    "-c",
                    command.joinToString(" ")
                )

                val env = buildEnvironment()

                Log.d(TAG, "Exec: ${command.joinToString(" ")}")

                val process = Runtime.getRuntime().exec(fullCommand, env, homeDir)
                val output = process.inputStream.bufferedReader().readText() +
                        process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                Log.d(TAG, "Exit: $exitCode, Output: ${output.take(200)}")
                Pair(exitCode, output)
            } catch (e: Exception) {
                Log.e(TAG, "Exec failed: ${e.message}", e)
                Pair(-1, e.message ?: "Unknown error")
            }
        }
    }

    private fun buildEnvironment(): Array<String> {
        val prefix = prefixDir.absolutePath
        return arrayOf(
            "HOME=${homeDir.absolutePath}",
            "PREFIX=$prefix",
            "TMPDIR=${tmpDir.absolutePath}",
            "PATH=$prefix/bin",
            "LD_LIBRARY_PATH=$prefix/lib",
            "LANG=en_US.UTF-8",
            "OLLAMA_HOST=127.0.0.1:11434",
            "OLLAMA_MODELS=${homeDir.absolutePath}/.ollama/models",
            "TERMUX_PREFIX=$prefix",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system"
        )
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Download failed: ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty response")
            val contentLength = body.contentLength()

            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                body.source().use { source ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var read: Int

                    while (source.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                        onProgress(totalRead, contentLength)
                    }
                }
            }
        }
    }

    private fun extractBootstrap(zipFile: File, outputDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zipIn.copyTo(output)
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private fun setupSymlinks() {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found")
            return
        }

        symlinksFile.forEachLine { line ->
            val parts = line.split("←")
            if (parts.size == 2) {
                val linkPath = File(prefixDir, parts[0].trim())
                val targetPath = parts[1].trim()

                try {
                    linkPath.parentFile?.mkdirs()
                    // On Android, we can't create true symlinks without root
                    // Instead, copy the target file or create a shell wrapper
                    val targetFile = File(prefixDir, targetPath)
                    if (targetFile.exists() && !linkPath.exists()) {
                        targetFile.copyTo(linkPath, overwrite = false)
                    }
                } catch (e: Exception) {
                    // Ignore symlink errors
                }
            }
        }
    }

    private fun setExecutablePermissions(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                setExecutablePermissions(file)
            } else {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }
}
