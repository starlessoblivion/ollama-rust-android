package com.ollamarust

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class OnboardingActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var actionButton: Button
    private lateinit var skipButton: Button

    private var currentStep = 0
    private val installer by lazy { OllamaInstaller(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already set up
        if (OllamaApp.instance.isSetupComplete()) {
            startMainActivity()
            return
        }

        setupUI()
        showStep(0)
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 100, 48, 48)
            setBackgroundColor(0xFF1a1a2e.toInt())
        }

        // Logo
        val logo = TextView(this).apply {
            text = "🦙"
            textSize = 72f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 24)
        }
        layout.addView(logo)

        // Title
        titleText = TextView(this).apply {
            text = "Welcome to Ollama"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(titleText)

        // Subtitle
        subtitleText = TextView(this).apply {
            text = "Run AI models locally on your device"
            textSize = 16f
            setTextColor(0xAAFFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 48)
        }
        layout.addView(subtitleText)

        // Status container
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x20FFFFFF)
            setPadding(32, 32, 32, 32)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 32)
            layoutParams = params
        }

        statusText = TextView(this).apply {
            text = "Ready to install Ollama"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 16)
        }
        statusContainer.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                16
            )
            params.setMargins(0, 16, 0, 8)
            layoutParams = params
        }
        statusContainer.addView(progressBar)

        progressText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            visibility = View.GONE
        }
        statusContainer.addView(progressText)

        layout.addView(statusContainer)

        // Action button
        actionButton = Button(this).apply {
            text = "Install Ollama"
            textSize = 18f
            setBackgroundColor(0xFF4a9eff.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 24, 32, 24)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 16, 0, 16)
            layoutParams = params
            setOnClickListener { onActionButtonClick() }
        }
        layout.addView(actionButton)

        // Skip button
        skipButton = Button(this).apply {
            text = "Use Remote Server Instead"
            textSize = 14f
            setBackgroundColor(0x00000000)
            setTextColor(0x99FFFFFF.toInt())
            setOnClickListener { showRemoteServerOption() }
        }
        layout.addView(skipButton)

        setContentView(layout)

        // Make fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        window.statusBarColor = 0xFF1a1a2e.toInt()
    }

    private fun showStep(step: Int) {
        currentStep = step
        when (step) {
            0 -> {
                titleText.text = "Welcome to Ollama"
                subtitleText.text = "Run AI models locally on your device"
                statusText.text = "This will download and install Ollama (~50MB)\nNo external apps required!"
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.text = "Install Ollama"
                actionButton.isEnabled = true
                skipButton.visibility = View.VISIBLE
            }
            1 -> {
                // Request permissions if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                }
                startInstallation()
            }
            2 -> {
                titleText.text = "Installation Complete!"
                subtitleText.text = "Ollama is ready to use"
                statusText.text = "✓ Ollama installed successfully"
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.text = "Start Chatting"
                actionButton.isEnabled = true
                skipButton.visibility = View.GONE
            }
        }
    }

    private fun onActionButtonClick() {
        when (currentStep) {
            0 -> showStep(1)
            2 -> {
                OllamaApp.instance.setSetupComplete(true)
                startMainActivity()
            }
        }
    }

    private fun startInstallation() {
        titleText.text = "Installing Ollama"
        subtitleText.text = "Please wait..."
        statusText.text = "Preparing installation..."
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = "0%"
        actionButton.isEnabled = false
        actionButton.text = "Installing..."
        skipButton.visibility = View.GONE

        lifecycleScope.launch {
            try {
                installer.install(
                    onProgress = { progress, status ->
                        runOnUiThread {
                            progressBar.progress = progress
                            progressText.text = "$progress%"
                            statusText.text = status
                        }
                    },
                    onComplete = {
                        runOnUiThread {
                            showStep(2)
                        }
                    },
                    onError = { error ->
                        runOnUiThread {
                            statusText.text = "Error: $error"
                            actionButton.text = "Retry"
                            actionButton.isEnabled = true
                            currentStep = 0
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    actionButton.text = "Retry"
                    actionButton.isEnabled = true
                    currentStep = 0
                }
            }
        }
    }

    private fun showRemoteServerOption() {
        // Skip to main with remote server config
        OllamaApp.instance.setSetupComplete(true)
        OllamaApp.instance.setUseRemoteServer(true)
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("show_server_config", true)
        startActivity(intent)
        finish()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
