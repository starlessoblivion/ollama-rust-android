package com.ollamarust

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

class OllamaApp : Application() {

    companion object {
        const val CHANNEL_ID = "ollama_service"
        const val PREFS_NAME = "ollama_prefs"
        const val KEY_TERMUX_SETUP = "termux_setup_complete"
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_USE_REMOTE_SERVER = "use_remote_server"
        const val KEY_SELECTED_MODEL = "selected_model"
        const val KEY_BRAVE_API_KEY = "brave_api_key"
        const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        const val KEY_THEME = "theme"
        const val KEY_OLLAMA_HOST = "ollama_host"
        const val KEY_AUTO_START = "auto_start_ollama"
        const val DEFAULT_OLLAMA_HOST = "http://127.0.0.1:11434"

        lateinit var instance: OllamaApp
            private set
    }

    val ollamaRunner: OllamaRunner by lazy { OllamaRunner(this) }
    val ollamaInstaller: OllamaInstaller by lazy { OllamaInstaller(this) }

    val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ollama Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ollama running in the background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun isTermuxSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_TERMUX_SETUP, false)
    }

    fun setTermuxSetupComplete(complete: Boolean) {
        prefs.edit().putBoolean(KEY_TERMUX_SETUP, complete).apply()
    }

    fun isSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun setSetupComplete(complete: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply()
    }

    fun isUsingRemoteServer(): Boolean {
        return prefs.getBoolean(KEY_USE_REMOTE_SERVER, false)
    }

    fun setUseRemoteServer(useRemote: Boolean) {
        prefs.edit().putBoolean(KEY_USE_REMOTE_SERVER, useRemote).apply()
    }

    fun getOllamaHost(): String {
        return if (isUsingRemoteServer()) {
            prefs.getString(KEY_OLLAMA_HOST, DEFAULT_OLLAMA_HOST) ?: DEFAULT_OLLAMA_HOST
        } else {
            DEFAULT_OLLAMA_HOST
        }
    }

    fun setOllamaHost(host: String) {
        prefs.edit().putString(KEY_OLLAMA_HOST, host).apply()
    }

    fun isAutoStartEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_START, true)
    }

    fun setAutoStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}
