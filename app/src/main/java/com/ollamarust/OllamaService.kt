package com.ollamarust

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OllamaService : Service() {

    companion object {
        const val ACTION_START = "com.ollamarust.START"
        const val ACTION_STOP = "com.ollamarust.STOP"
        private const val NOTIFICATION_ID = 1

        var isServiceRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOllama()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                startOllama()
            }
        }
        return START_STICKY
    }

    private var ollamaProcess: Process? = null

    private fun startOllama() {
        serviceScope.launch {
            try {
                // Check if using remote server
                if (OllamaApp.instance.isUsingRemoteServer()) {
                    updateNotification("Using remote server")
                    return@launch
                }

                val proot = OllamaApp.instance.prootExecutor

                // Check if proot environment is ready
                if (!proot.isSetup()) {
                    updateNotification("Not set up - run setup first")
                    return@launch
                }

                if (!proot.isOllamaInstalled()) {
                    updateNotification("Ollama not installed")
                    return@launch
                }

                updateNotification("Starting Ollama...")

                // Start ollama via proot
                ollamaProcess = proot.startOllamaServe()

                if (ollamaProcess != null) {
                    // Wait for server to be ready
                    var attempts = 0
                    while (attempts < 60) {
                        kotlinx.coroutines.delay(1000)
                        updateNotification("Starting... (${attempts}s)")
                        if (OllamaApp.instance.ollamaRunner.checkServerRunning()) {
                            updateNotification("Running")
                            return@launch
                        }
                        // Check if process died
                        try {
                            val exitCode = ollamaProcess?.exitValue()
                            updateNotification("Process exited: $exitCode")
                            return@launch
                        } catch (e: IllegalThreadStateException) {
                            // Still running, continue
                        }
                        attempts++
                    }
                    updateNotification("Timeout waiting for server")
                } else {
                    // Test proot to get detailed error
                    val testResult = proot.testProot()
                    Log.e("OllamaService", "Proot test: $testResult")
                    val code = proot.errorCode.ifEmpty { "E5" }
                    updateNotification("Error $code")
                }
            } catch (e: Exception) {
                updateNotification("Error: ${e.message?.take(30)}")
            }
        }
    }

    private fun stopOllama() {
        ollamaProcess?.let { process ->
            try {
                process.destroy()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        ollamaProcess = null
        OllamaApp.instance.ollamaRunner.stop()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(status: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OllamaService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OllamaApp.CHANNEL_ID)
            .setContentTitle("Ollama")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isServiceRunning = false
        serviceScope.cancel()
        stopOllama()
        super.onDestroy()
    }
}
