package com.ollamarust

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

object TermuxHelper {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openTermux(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Termux not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open Termux: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun runSetupCommands(context: Context) {
        // This runs the initial setup commands in Termux
        val setupScript = """
            pkg update -y && pkg upgrade -y
            pkg install -y curl wget git cmake golang
            curl -fsSL https://ollama.com/install.sh | sh
            echo "Setup complete! You can now run 'ollama serve' to start the server."
        """.trimIndent()

        runCommandInTermux(context, setupScript, "Ollama Setup")
    }

    fun startOllama(context: Context) {
        runCommandInTermux(context, "ollama serve", "Ollama Server")
    }

    fun stopOllama(context: Context) {
        runCommandInTermux(context, "pkill -f 'ollama serve'", "Stop Ollama")
    }

    private fun runCommandInTermux(context: Context, command: String, label: String) {
        if (!isTermuxInstalled(context)) {
            Toast.makeText(context, "Termux is not installed", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Method 1: Try using RUN_COMMAND intent (requires Termux:Tasker or permission setup)
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }

            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Method 2: Fallback to opening Termux with clipboard
                openTermuxWithCommand(context, command)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to run command: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTermuxWithCommand(context: Context, command: String) {
        try {
            // Copy command to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("command", command)
            clipboard.setPrimaryClip(clip)

            // Open Termux
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent != null) {
                context.startActivity(intent)
                Toast.makeText(context, "Command copied! Paste and run in Termux", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to open Termux", Toast.LENGTH_SHORT).show()
        }
    }

    fun openTermuxFdroid(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
