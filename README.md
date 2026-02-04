# Ollama Android

A native Android app for running AI models locally using Ollama and Termux.

## Features

- Clean chat interface with streaming responses
- Model management (pull, delete, select)
- Multiple themes (Light, Dark, AMOLED, Hacker, Nordic)
- Brave Search API integration for real-time web search
- Run AI models locally on your Android device
- One-click setup wizard

## Requirements

- Android 8.0 (API 26) or higher
- [Termux](https://f-droid.org/packages/com.termux/) from F-Droid (not Google Play)
- Minimum 4GB RAM recommended
- ~2GB storage per model

## Installation

### From APK (Recommended)

1. Download the latest APK from [Releases](https://github.com/starlessoblivion/ollama-rust-android/releases)
2. Install the APK on your device
3. Follow the setup wizard

### Build from Source

```bash
git clone https://github.com/starlessoblivion/ollama-rust-android.git
cd ollama-rust-android
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`

## Setup Guide

The app includes a setup wizard that will guide you through:

1. **Install Termux** - Download from F-Droid (required for local models)
2. **Install Ollama** - Run the installation commands in Termux
3. **Start Server** - Run `ollama serve` in Termux
4. **Ready!** - Start chatting with AI

### Manual Termux Setup

If you prefer manual setup, run these commands in Termux:

```bash
pkg update && pkg upgrade -y
pkg install curl -y
curl -fsSL https://ollama.com/install.sh | sh
ollama serve
```

## Usage

1. Make sure Termux is running with `ollama serve`
2. Open the Ollama app
3. Select a model from the dropdown
4. Start chatting!

### Pull New Models

1. Tap the model dropdown
2. Tap "+ Add Model"
3. Enter model name (e.g., `llama3.2`, `mistral`, `codellama`)
4. Tap "Pull" to download

### Web Search

Enable real-time web search for current information:

1. Tap "Status" in the header
2. Toggle "Web Search" on
3. Hover/tap to enter your Brave Search API key
4. Get a free key at https://brave.com/search/api/

## Remote Server

You can also connect to a remote Ollama server:

1. On setup screen, tap "Use remote server instead"
2. Enter the server URL (e.g., `http://192.168.1.100:11434`)
3. Test connection and complete setup

## Supported Models

Any model from the [Ollama Library](https://ollama.com/library) can be used, including:

- Llama 3.2 (1B, 3B)
- Mistral
- Phi-3
- Gemma 2
- CodeLlama
- And many more!

Note: Larger models require more RAM and may run slowly on mobile devices.

## Themes

- **Light** - Clean light interface
- **Dark** - Easy on the eyes
- **AMOLED** - Pure black for OLED screens
- **Hacker** - Green on black terminal style
- **Nordic** - Cool blue tones

## Troubleshooting

### "Ollama not running"
- Open Termux and run `ollama serve`
- Make sure Termux is not killed by battery optimization

### Slow response
- Try smaller models (1B-3B parameters)
- Close other apps to free RAM

### Models not downloading
- Check your internet connection
- Ensure enough storage space

## License

MIT

## Credits

Built with:
- [Ollama](https://ollama.com) - Run AI models locally
- [Termux](https://termux.dev) - Linux terminal for Android
- [Brave Search API](https://brave.com/search/api/) - Web search integration
