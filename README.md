# Ollama Android

A native Android app for running AI models locally on your device. **No Termux required** - Ollama is built right into the app!

## Features

- **One-click installation** - Just tap "Install Ollama" and you're ready
- Clean chat interface with streaming responses
- Model management (pull, delete, select)
- Multiple themes (Light, Dark, AMOLED, Hacker, Nordic)
- Brave Search API integration for real-time web search
- Run AI models locally on your Android device
- Background service keeps Ollama running
- Optional remote server support

## Requirements

- Android 8.0 (API 26) or higher
- Minimum 4GB RAM recommended
- ~500MB for the app + 1-5GB per model

## Installation

### From APK (Recommended)

1. Download the latest APK from [Releases](https://github.com/starlessoblivion/ollama-rust-android/releases)
2. Install the APK on your device
3. Tap "Install Ollama" on the welcome screen
4. Wait for the download to complete (~50MB)
5. Start chatting!

### Build from Source

```bash
git clone https://github.com/starlessoblivion/ollama-rust-android.git
cd ollama-rust-android
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`

## Usage

1. Open the app - Ollama starts automatically
2. Select a model from the dropdown (or pull a new one)
3. Start chatting!

### Pull New Models

1. Tap the model dropdown
2. Tap "+ Add Model"
3. Enter model name (e.g., `llama3.2`, `mistral`, `codellama`)
4. Tap "Pull" to download

### Web Search

Enable real-time web search for current information:

1. Tap "Status" in the header
2. Toggle "Web Search" on
3. Tap to enter your Brave Search API key
4. Get a free key at https://brave.com/search/api/

## Remote Server

Want to use a more powerful machine? Connect to a remote Ollama server:

1. On the welcome screen, tap "Use Remote Server Instead"
2. Enter the server URL (e.g., `http://192.168.1.100:11434`)
3. Test connection and start chatting

## Supported Models

Any model from the [Ollama Library](https://ollama.com/library) can be used:

| Model | Size | Best For |
|-------|------|----------|
| llama3.2:1b | ~1GB | Fast responses, basic tasks |
| llama3.2:3b | ~2GB | Good balance of speed/quality |
| phi3:mini | ~2GB | Efficient reasoning |
| mistral | ~4GB | Strong general performance |
| codellama | ~4GB | Code generation |

**Tip:** Start with smaller models (1B-3B) for best mobile experience.

## Themes

- **Light** - Clean light interface
- **Dark** - Easy on the eyes (default)
- **AMOLED** - Pure black for OLED screens, saves battery
- **Hacker** - Green on black terminal style
- **Nordic** - Cool blue tones

## Troubleshooting

### "Ollama not running"
- Check the Status menu and toggle Ollama on
- Make sure the app isn't being killed by battery optimization
- Go to Settings → Apps → Ollama → Battery → Unrestricted

### Slow responses
- Try smaller models (1B-3B parameters)
- Close other apps to free RAM
- Use a remote server for better performance

### Installation failed
- Ensure you have enough storage space
- Check your internet connection
- Try restarting the app

### Models not downloading
- Check your internet connection
- Ensure enough storage space (~1-5GB per model)
- Some models require more RAM than available

## Privacy

- All AI processing happens on your device (or your remote server)
- No data is sent to external servers unless you enable Web Search
- Web Search only sends your query to Brave Search API

## License

MIT

## Credits

Built with:
- [Ollama](https://ollama.com) - Run AI models locally
- [Brave Search API](https://brave.com/search/api/) - Web search integration
