package com.ollamarust

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object TermuxHelper {

    private const val TAG = "TermuxHelper"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    // Check if Termux is installed
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // Check if ollama is installed in Termux by checking common paths
    fun isOllamaInstalledInTermux(): Boolean {
        val possiblePaths = listOf(
            "/data/data/com.termux/files/usr/bin/ollama",
            "/data/data/com.termux/files/home/go/bin/ollama",
            "/data/data/com.termux/files/home/.ollama/ollama"
        )
        return possiblePaths.any { java.io.File(it).exists() }
    }

    // Run a command in Termux background (no terminal window)
    fun runCommandInBackground(context: Context, command: String): Boolean {
        if (!isTermuxInstalled(context)) {
            Log.e(TAG, "Termux is not installed")
            return false
        }

        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "4") // 4 = TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_OPEN
            }

            context.startService(intent)
            Log.d(TAG, "Command sent to Termux: $command")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - Termux RUN_COMMAND permission not granted", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run command in Termux", e)
            false
        }
    }

    // Start ollama serve in background
    fun startOllamaServe(context: Context): Boolean {
        // First check common installation paths and use the right one
        val ollamaPath = findOllamaPath()

        val command = if (ollamaPath != null) {
            // Use the found path directly
            "$ollamaPath serve"
        } else {
            // Try with PATH - ollama might be in a standard location
            "ollama serve"
        }

        // Set environment and run
        val fullCommand = """
            export OLLAMA_HOST=127.0.0.1:11434
            export HOME=/data/data/com.termux/files/home
            cd ${'$'}HOME
            $command
        """.trimIndent().replace("\n", " && ")

        return runCommandInBackground(context, fullCommand)
    }

    // Stop ollama serve
    fun stopOllamaServe(context: Context): Boolean {
        return runCommandInBackground(context, "pkill -f 'ollama serve' || true")
    }

    // Install ollama in Termux
    fun installOllama(context: Context): Boolean {
        val installCommand = """
            pkg update -y &&
            pkg install -y golang git cmake &&
            go install github.com/ollama/ollama@latest &&
            echo 'export PATH=${'$'}PATH:${'$'}HOME/go/bin' >> ~/.bashrc
        """.trimIndent().replace("\n", " ")

        return runCommandInBackground(context, installCommand)
    }

    // Alternative: Install using curl script
    fun installOllamaWithCurl(context: Context): Boolean {
        val installCommand = """
            pkg update -y &&
            pkg install -y curl &&
            curl -fsSL https://ollama.com/install.sh | sh
        """.trimIndent().replace("\n", " ")

        return runCommandInBackground(context, installCommand)
    }

    // Find ollama binary path
    private fun findOllamaPath(): String? {
        val possiblePaths = listOf(
            "/data/data/com.termux/files/usr/bin/ollama",
            "/data/data/com.termux/files/home/go/bin/ollama",
            "/data/data/com.termux/files/home/.ollama/bin/ollama",
            "/data/data/com.termux/files/usr/local/bin/ollama"
        )
        return possiblePaths.firstOrNull { java.io.File(it).exists() }
    }

    // Open Termux app (for manual setup if needed)
    fun openTermux(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Termux", e)
            false
        }
    }

    // Get F-Droid link for Termux installation
    fun getTermuxFdroidUrl(): String {
        return "https://f-droid.org/packages/com.termux/"
    }
}
