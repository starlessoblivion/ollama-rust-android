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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var actionButton: Button
    private lateinit var skipButton: Button

    private val prootExecutor by lazy { OllamaApp.instance.prootExecutor }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already set up
        if (OllamaApp.instance.isSetupComplete()) {
            startMainActivity()
            return
        }

        setupUI()
        requestPermissions()
    }

    private fun setupUI() {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF1a1a2e.toInt())
        }

        // Handle window insets for notch
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(48, systemBars.top + 48, 48, systemBars.bottom + 48)
            insets
        }

        // Logo
        val logo = TextView(this).apply {
            text = "🦙"
            textSize = 72f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 24)
        }
        container.addView(logo)

        // Title
        titleText = TextView(this).apply {
            text = "Welcome to Ollama"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleText)

        // Subtitle
        subtitleText = TextView(this).apply {
            text = "Run AI models locally on your device"
            textSize = 16f
            setTextColor(0xAAFFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 48)
        }
        container.addView(subtitleText)

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
            text = "Ready to set up Ollama"
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

        container.addView(statusContainer)

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
            params.setMargins(0, 16, 0, 8)
            layoutParams = params
            setOnClickListener { onActionButtonClick() }
        }
        container.addView(actionButton)

        // Skip button
        skipButton = Button(this).apply {
            text = "Use Remote Server Instead"
            textSize = 14f
            setBackgroundColor(0x00000000)
            setTextColor(0x99FFFFFF.toInt())
            setOnClickListener { showRemoteServerOption() }
        }
        container.addView(skipButton)

        setContentView(container)
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun onActionButtonClick() {
        when (actionButton.text) {
            "Install Ollama" -> startInstallation()
            "Start Chatting" -> {
                OllamaApp.instance.setSetupComplete(true)
                startMainActivity()
            }
            "Retry" -> startInstallation()
        }
    }

    private fun startInstallation() {
        titleText.text = "Setting Up"
        subtitleText.text = "This will download ~60MB"
        statusText.text = "Preparing..."
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.visibility = View.VISIBLE
        progressText.text = "0%"
        actionButton.isEnabled = false
        actionButton.text = "Installing..."
        skipButton.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // Step 1: Setup proot environment
                val prootSuccess = prootExecutor.setup { progress, status ->
                    runOnUiThread {
                        // Scale proot setup to 0-50%
                        val scaledProgress = (progress * 0.5).toInt()
                        progressBar.progress = scaledProgress
                        progressText.text = "$scaledProgress%"
                        statusText.text = status
                    }
                }

                if (!prootSuccess) {
                    showError("Failed to set up Linux environment")
                    return@launch
                }

                // Step 2: Install Ollama
                val ollamaSuccess = prootExecutor.installOllama { progress, status ->
                    runOnUiThread {
                        // Scale ollama install to 50-100%
                        val scaledProgress = 50 + (progress * 0.5).toInt()
                        progressBar.progress = scaledProgress
                        progressText.text = "$scaledProgress%"
                        statusText.text = status
                    }
                }

                if (!ollamaSuccess) {
                    showError("Failed to install Ollama")
                    return@launch
                }

                // Success!
                runOnUiThread {
                    showComplete()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showError(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun showComplete() {
        titleText.text = "Ready to Go!"
        subtitleText.text = "Ollama is installed"
        statusText.text = "✓ Linux environment ready\n✓ Ollama installed\n\nYou can now run AI models locally!"
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        actionButton.text = "Start Chatting"
        actionButton.isEnabled = true
        skipButton.visibility = View.GONE
    }

    private fun showError(error: String) {
        titleText.text = "Setup Failed"
        subtitleText.text = "An error occurred"
        statusText.text = "Error: $error\n\nPlease check your internet connection and try again."
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        actionButton.text = "Retry"
        actionButton.isEnabled = true
        skipButton.visibility = View.VISIBLE
    }

    private fun showRemoteServerOption() {
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
