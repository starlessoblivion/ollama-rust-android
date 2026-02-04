package com.ollamarust

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var actionButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var skipButton: Button

    private var currentStep = Step.WELCOME

    private enum class Step {
        WELCOME,
        NEED_TERMUX,
        INSTALLING_TERMUX_OLLAMA,
        CHECKING_OLLAMA,
        COMPLETE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already set up
        if (OllamaApp.instance.isSetupComplete()) {
            startMainActivity()
            return
        }

        setupUI()
        checkInitialState()
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
            text = "Checking requirements..."
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
            text = "Continue"
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

        // Secondary button
        secondaryButton = Button(this).apply {
            text = "Install Termux"
            textSize = 16f
            setBackgroundColor(0x40FFFFFF)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 20, 32, 20)
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            layoutParams = params
            setOnClickListener { onSecondaryButtonClick() }
        }
        container.addView(secondaryButton)

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

        // Request notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun checkInitialState() {
        if (TermuxHelper.isTermuxInstalled(this)) {
            // Termux is installed, check if Ollama is available
            showStep(Step.CHECKING_OLLAMA)
            checkOllamaInTermux()
        } else {
            // Need to install Termux first
            showStep(Step.NEED_TERMUX)
        }
    }

    private fun showStep(step: Step) {
        currentStep = step
        when (step) {
            Step.WELCOME -> {
                titleText.text = "Welcome to Ollama"
                subtitleText.text = "Run AI models locally on your device"
                statusText.text = "Checking requirements..."
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.text = "Continue"
                actionButton.isEnabled = true
                secondaryButton.visibility = View.GONE
                skipButton.visibility = View.VISIBLE
            }
            Step.NEED_TERMUX -> {
                titleText.text = "Termux Required"
                subtitleText.text = "Ollama runs inside Termux"
                statusText.text = "Termux provides the Linux environment needed to run Ollama.\n\nPlease install Termux from F-Droid (not Play Store)."
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.text = "Get Termux from F-Droid"
                actionButton.isEnabled = true
                secondaryButton.text = "I've Installed Termux"
                secondaryButton.visibility = View.VISIBLE
                skipButton.visibility = View.VISIBLE
            }
            Step.INSTALLING_TERMUX_OLLAMA -> {
                titleText.text = "Installing Ollama"
                subtitleText.text = "Setting up in Termux..."
                statusText.text = "Installing Ollama in Termux background.\nThis may take a few minutes.\n\nPlease keep Termux running."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = View.GONE
                actionButton.text = "Installing..."
                actionButton.isEnabled = false
                secondaryButton.text = "Open Termux"
                secondaryButton.visibility = View.VISIBLE
                skipButton.visibility = View.GONE
            }
            Step.CHECKING_OLLAMA -> {
                titleText.text = "Checking Ollama"
                subtitleText.text = "Please wait..."
                statusText.text = "Checking if Ollama is ready..."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = View.GONE
                actionButton.isEnabled = false
                secondaryButton.visibility = View.GONE
                skipButton.visibility = View.GONE
            }
            Step.COMPLETE -> {
                titleText.text = "Ready to Go!"
                subtitleText.text = "Ollama is set up"
                statusText.text = "✓ Termux installed\n✓ Ollama ready\n\nYou can now run AI models locally!"
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                actionButton.text = "Start Chatting"
                actionButton.isEnabled = true
                secondaryButton.visibility = View.GONE
                skipButton.visibility = View.GONE
            }
        }
    }

    private fun onActionButtonClick() {
        when (currentStep) {
            Step.NEED_TERMUX -> {
                // Open F-Droid to install Termux
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TermuxHelper.getTermuxFdroidUrl()))
                startActivity(intent)
            }
            Step.COMPLETE -> {
                OllamaApp.instance.setSetupComplete(true)
                startMainActivity()
            }
            else -> {}
        }
    }

    private fun onSecondaryButtonClick() {
        when (currentStep) {
            Step.NEED_TERMUX -> {
                // User says they installed Termux, check again
                if (TermuxHelper.isTermuxInstalled(this)) {
                    showStep(Step.CHECKING_OLLAMA)
                    checkOllamaInTermux()
                } else {
                    statusText.text = "Termux not detected.\n\nPlease install from F-Droid and open it once before continuing."
                }
            }
            Step.INSTALLING_TERMUX_OLLAMA -> {
                // Open Termux to let user see progress
                TermuxHelper.openTermux(this)
            }
            else -> {}
        }
    }

    private fun checkOllamaInTermux() {
        lifecycleScope.launch {
            // First, try to start Ollama serve to see if it's already installed
            TermuxHelper.startOllamaServe(this@OnboardingActivity)

            // Wait and check if server responds
            var attempts = 0
            while (attempts < 15) {
                delay(2000)
                if (OllamaApp.instance.ollamaRunner.checkServerRunning()) {
                    // Ollama is working!
                    showStep(Step.COMPLETE)
                    return@launch
                }
                attempts++
            }

            // Ollama not responding, need to install it
            showStep(Step.INSTALLING_TERMUX_OLLAMA)
            installOllamaInTermux()
        }
    }

    private fun installOllamaInTermux() {
        // Install ollama using curl
        TermuxHelper.installOllamaWithCurl(this)

        lifecycleScope.launch {
            // Monitor installation - wait for ollama to become available
            var attempts = 0
            while (attempts < 90) { // 3 minutes max
                delay(2000)

                // Try starting ollama
                if (attempts > 30 && attempts % 10 == 0) {
                    TermuxHelper.startOllamaServe(this@OnboardingActivity)
                }

                if (OllamaApp.instance.ollamaRunner.checkServerRunning()) {
                    showStep(Step.COMPLETE)
                    return@launch
                }

                runOnUiThread {
                    statusText.text = "Installing Ollama in Termux...\n\nThis may take a few minutes.\nAttempt ${attempts + 1}/90"
                }

                attempts++
            }

            // Installation seems to be taking too long
            runOnUiThread {
                statusText.text = "Installation is taking longer than expected.\n\nPlease open Termux and run:\ncurl -fsSL https://ollama.com/install.sh | sh\n\nThen run: ollama serve"
                actionButton.text = "Open Termux"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    TermuxHelper.openTermux(this@OnboardingActivity)
                }
                secondaryButton.text = "Check Again"
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.setOnClickListener {
                    showStep(Step.CHECKING_OLLAMA)
                    checkOllamaInTermux()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check state when returning to app
        if (currentStep == Step.NEED_TERMUX && TermuxHelper.isTermuxInstalled(this)) {
            showStep(Step.CHECKING_OLLAMA)
            checkOllamaInTermux()
        }
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
