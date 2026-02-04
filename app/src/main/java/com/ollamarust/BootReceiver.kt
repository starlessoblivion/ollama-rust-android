package com.ollamarust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if user wants auto-start
            val prefs = context.getSharedPreferences(OllamaApp.PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_ollama", false)

            if (autoStart && OllamaApp.instance.isTermuxSetupComplete()) {
                val serviceIntent = Intent(context, OllamaService::class.java).apply {
                    action = OllamaService.ACTION_START
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
