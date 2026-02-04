package com.ollamarust

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Download progress info with speed tracking
 */
data class DownloadProgress(
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentSpeedBps: Long,
    val averageSpeedBps: Long
) {
    fun formatSpeed(bps: Long): String {
        return when {
            bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000.0)
            bps >= 1_000 -> String.format("%.0f KB/s", bps / 1_000.0)
            else -> "$bps B/s"
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.0f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    val currentSpeedFormatted: String get() = formatSpeed(currentSpeedBps)
    val averageSpeedFormatted: String get() = formatSpeed(averageSpeedBps)
    val downloadedFormatted: String get() = formatSize(downloadedBytes)
    val totalFormatted: String get() = formatSize(totalBytes)
}

/**
 * Handles proot setup and execution for running Linux binaries on Android.
 * This allows running glibc-based binaries (like ollama) without Termux.
 */
class ProotExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ProotExecutor"

        // Proot binaries from proot-portable-android-binaries
        private const val PROOT_BASE_URL = "https://skirsten.github.io/proot-portable-android-binaries"

        // Alpine Linux minimal rootfs with glibc compatibility
        private const val ALPINE_ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
        private const val ALPINE_ROOTFS_URL_ARM = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/armv7/alpine-minirootfs-3.19.1-armv7.tar.gz"
        private const val ALPINE_ROOTFS_URL_X86_64 = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-3.19.1-x86_64.tar.gz"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val baseDir: File
        get() = File(context.filesDir, "proot")

    // Use bundled proot from native libs (has exec permission)
    private val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    private val rootfsDir: File
        get() = File(baseDir, "rootfs")

    private val tmpDir: File
        get() = File(context.cacheDir, "proot-tmp")

    private val ollamaBinary: File
        get() = File(rootfsDir, "usr/local/bin/ollama")

    fun isSetup(): Boolean {
        return prootBinary.exists() && prootBinary.canExecute() &&
               rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()
    }

    fun isOllamaInstalled(): Boolean {
        return ollamaBinary.exists()
    }

    fun getOllamaPath(): String {
        return "/usr/local/bin/ollama"
    }

    private fun getArchitecture(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armv7"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            Build.SUPPORTED_ABIS.contains("x86") -> "x86"
            else -> "aarch64"
        }
    }

    private fun getProotUrl(): String {
        return "$PROOT_BASE_URL/${getArchitecture()}/proot"
    }

    private fun getRootfsUrl(): String {
        return when (getArchitecture()) {
            "aarch64" -> ALPINE_ROOTFS_URL
            "armv7" -> ALPINE_ROOTFS_URL_ARM
            "x86_64" -> ALPINE_ROOTFS_URL_X86_64
            else -> ALPINE_ROOTFS_URL
        }
    }

    suspend fun setup(onProgress: (Int, String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Create directories
                baseDir.mkdirs()
                tmpDir.mkdirs()
                rootfsDir.mkdirs()

                // Proot is now bundled in the APK as a native library
                // Verify it exists
                if (!prootBinary.exists()) {
                    Log.e(TAG, "Bundled proot not found at: ${prootBinary.absolutePath}")
                    return@withContext false
                }
                Log.d(TAG, "Using bundled proot: ${prootBinary.absolutePath}, size: ${prootBinary.length()}")

                // Download and extract rootfs if needed
                if (!File(rootfsDir, "bin/sh").exists()) {
                    onProgress(20, "Downloading Linux environment...")
                    val rootfsTar = File(baseDir, "rootfs.tar.gz")
                    downloadFile(getRootfsUrl(), rootfsTar) { progress ->
                        val speedInfo = if (progress.currentSpeedBps > 0) {
                            " (${progress.currentSpeedFormatted})"
                        } else ""
                        val sizeInfo = "${progress.downloadedFormatted} / ${progress.totalFormatted}"
                        onProgress(20 + (progress.percent * 0.3).toInt(), "Downloading Linux environment...\n$sizeInfo$speedInfo")
                    }

                    onProgress(50, "Extracting Linux environment...")
                    extractTarGz(rootfsTar, rootfsDir)
                    rootfsTar.delete()

                    // Setup DNS
                    onProgress(55, "Configuring network...")
                    setupDns()

                    // Install glibc compatibility
                    onProgress(60, "Installing compatibility layer...")
                    installGlibcCompat(onProgress)
                }

                onProgress(100, "Environment ready")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                false
            }
        }
    }

    private fun setupDns() {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText("""
            nameserver 8.8.8.8
            nameserver 8.8.4.4
        """.trimIndent())
    }

    private suspend fun installGlibcCompat(onProgress: (Int, String) -> Unit) {
        // Run apk to install gcompat (glibc compatibility layer for Alpine)
        exec("apk", "update")
        onProgress(70, "Installing glibc compatibility...")
        exec("apk", "add", "gcompat", "libstdc++", "curl")
    }

    suspend fun installOllama(onProgress: (Int, String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onProgress(0, "Installing Ollama...")

                // Create directory for ollama
                val binDir = File(rootfsDir, "usr/local/bin")
                binDir.mkdirs()

                // Download ollama binary
                val arch = when (getArchitecture()) {
                    "aarch64" -> "arm64"
                    "armv7" -> "arm"
                    "x86_64" -> "amd64"
                    else -> "arm64"
                }
                val ollamaUrl = "https://github.com/ollama/ollama/releases/download/v0.5.3/ollama-linux-$arch.tgz"

                onProgress(10, "Downloading Ollama...")
                val ollamaTar = File(baseDir, "ollama.tgz")
                downloadFile(ollamaUrl, ollamaTar) { progress ->
                    val speedInfo = if (progress.currentSpeedBps > 0) {
                        " (${progress.currentSpeedFormatted})"
                    } else ""
                    val sizeInfo = "${progress.downloadedFormatted} / ${progress.totalFormatted}"
                    onProgress(10 + (progress.percent * 0.7).toInt(), "Downloading Ollama...\n$sizeInfo$speedInfo")
                }

                onProgress(80, "Extracting Ollama...")
                // Extract ollama binary from tgz
                extractOllamaFromTar(ollamaTar, binDir)
                ollamaTar.delete()

                // Make executable
                ollamaBinary.setExecutable(true, false)

                onProgress(100, "Ollama installed")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install Ollama", e)
                false
            }
        }
    }

    private fun extractOllamaFromTar(tgzFile: File, outputDir: File) {
        GZIPInputStream(tgzFile.inputStream()).use { gzipIn ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (entry.name == "bin/ollama" || entry.name.endsWith("/ollama")) {
                        val outFile = File(outputDir, "ollama")
                        FileOutputStream(outFile).use { output ->
                            tarIn.copyTo(output)
                        }
                        outFile.setExecutable(true, false)
                        break
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    suspend fun exec(vararg command: String): Pair<Int, String> {
        return withContext(Dispatchers.IO) {
            try {
                val prootCommand = mutableListOf(
                    prootBinary.absolutePath,
                    "-0",  // Fake root
                    "-r", rootfsDir.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-w", "/root",
                    "/bin/sh", "-c", command.joinToString(" ")
                )

                val env = mapOf(
                    "PROOT_TMP_DIR" to tmpDir.absolutePath,
                    "HOME" to "/root",
                    "PATH" to "/usr/local/bin:/usr/bin:/bin",
                    "LANG" to "C.UTF-8"
                )

                val processBuilder = ProcessBuilder(prootCommand)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                processBuilder.environment().clear()
                processBuilder.environment().putAll(env)
                processBuilder.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath

                val process = processBuilder.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                Log.d(TAG, "Command: ${command.joinToString(" ")}, Exit: $exitCode")
                Pair(exitCode, output)
            } catch (e: Exception) {
                Log.e(TAG, "Exec failed: ${command.joinToString(" ")}", e)
                Pair(-1, e.message ?: "Unknown error")
            }
        }
    }

    // Error codes: E1=proot missing, E2=ollama missing, E3=rootfs missing, E4=exec failed, E5=unknown
    var lastError: String = ""
        private set
    var errorCode: String = ""
        private set

    fun testProot(): String {
        return try {
            if (!prootBinary.exists()) {
                return "Proot not found at ${prootBinary.absolutePath}"
            }
            if (!prootBinary.canExecute()) {
                prootBinary.setExecutable(true, false)
            }

            val process = ProcessBuilder(prootBinary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            "Exit: $exitCode, Output: $output"
        } catch (e: Exception) {
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun startOllamaServe(): Process? {
        lastError = ""
        errorCode = ""
        return try {
            // Verify files exist first
            if (!prootBinary.exists()) {
                errorCode = "E1"
                lastError = "Proot not found"
                Log.e(TAG, "Proot binary not found at: ${prootBinary.absolutePath}")
                return null
            }
            if (!prootBinary.canExecute()) {
                Log.e(TAG, "Proot binary not executable, setting permission")
                prootBinary.setExecutable(true, false)
            }
            if (!ollamaBinary.exists()) {
                errorCode = "E2"
                lastError = "Ollama not found"
                Log.e(TAG, "Ollama binary not found at: ${ollamaBinary.absolutePath}")
                return null
            }
            if (!File(rootfsDir, "bin/sh").exists()) {
                errorCode = "E3"
                lastError = "Rootfs not found"
                Log.e(TAG, "Rootfs bin/sh not found")
                return null
            }

            // Ensure tmp dir exists
            tmpDir.mkdirs()

            val prootCommand = listOf(
                prootBinary.absolutePath,
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "/usr/local/bin/ollama", "serve"
            )

            val env = arrayOf(
                "PROOT_TMP_DIR=${tmpDir.absolutePath}",
                "HOME=/root",
                "PATH=/usr/local/bin:/usr/bin:/bin",
                "OLLAMA_HOST=127.0.0.1:11434",
                "LANG=C.UTF-8"
            )

            Log.d(TAG, "Starting ollama serve via proot")
            Log.d(TAG, "Command: ${prootCommand.joinToString(" ")}")
            Log.d(TAG, "Proot exists: ${prootBinary.exists()}, executable: ${prootBinary.canExecute()}")
            Log.d(TAG, "Ollama exists: ${ollamaBinary.exists()}")
            Log.d(TAG, "Rootfs exists: ${rootfsDir.exists()}, bin/sh: ${File(rootfsDir, "bin/sh").exists()}")

            val process = Runtime.getRuntime().exec(prootCommand.toTypedArray(), env, rootfsDir)

            // Read any immediate errors
            Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        Log.e(TAG, "Proot stderr: $line")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }.start()

            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.d(TAG, "Proot stdout: $line")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }.start()

            process
        } catch (e: Exception) {
            errorCode = "E4"
            lastError = e.message?.take(30) ?: "Exec failed"
            Log.e(TAG, "Failed to start ollama: ${e.message}", e)
            null
        }
    }

    private suspend fun downloadFile(url: String, destination: File, onProgress: (DownloadProgress) -> Unit) {
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

                    // Speed tracking
                    val startTime = System.currentTimeMillis()
                    var lastUpdateTime = startTime
                    var lastUpdateBytes = 0L
                    var currentSpeedBps = 0L

                    while (source.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read

                        val now = System.currentTimeMillis()
                        val timeSinceLastUpdate = now - lastUpdateTime

                        // Update speed every 500ms
                        if (timeSinceLastUpdate >= 500) {
                            val bytesSinceLastUpdate = totalRead - lastUpdateBytes
                            currentSpeedBps = (bytesSinceLastUpdate * 1000) / timeSinceLastUpdate
                            lastUpdateTime = now
                            lastUpdateBytes = totalRead
                        }

                        // Calculate average speed
                        val totalTime = now - startTime
                        val averageSpeedBps = if (totalTime > 0) (totalRead * 1000) / totalTime else 0L

                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt()
                            onProgress(DownloadProgress(
                                percent = percent,
                                downloadedBytes = totalRead,
                                totalBytes = contentLength,
                                currentSpeedBps = currentSpeedBps,
                                averageSpeedBps = averageSpeedBps
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun extractTarGz(tgzFile: File, outputDir: File) {
        GZIPInputStream(tgzFile.inputStream()).use { gzipIn ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    val outFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            tarIn.copyTo(output)
                        }
                        // Preserve executable permissions
                        if (entry.mode and 0b001001001 != 0) {
                            outFile.setExecutable(true, false)
                        }
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }
}
