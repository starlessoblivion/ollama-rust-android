# Ollama-Rust

A lightweight Rust-based web interface for Ollama, optimized for desktop Linux.

## Features

- Clean chat interface with streaming responses
- Model management (pull, delete, select)
- Multiple themes (Light, Dark, AMOLED, Hacker, Nordic)
- Brave Search API integration for real-time web search
- Ollama service control (start/stop)

## Quick Start

```bash
curl -fsSL https://raw.githubusercontent.com/starlessoblivion/ollama-rust/main/install.sh | bash
```

The installer handles all dependencies, builds the project, and creates a desktop entry.

## Supported Distributions

- Debian / Ubuntu / Pop!_OS / Linux Mint
- Arch Linux / Manjaro / EndeavourOS
- Fedora
- RHEL / CentOS / Rocky / AlmaLinux
- openSUSE

## Usage

1. Start the server: `~/ollama-rust/run.sh`
2. Open http://localhost:3000 in your browser
3. Make sure Ollama is installed and running

## Web Search

Enable the "Web Search" toggle in the Status menu to use Brave Search API:

1. Get an API key from https://brave.com/search/api/
2. Enter your token in the submenu
3. Click Save or Test to verify
4. When enabled, your queries will include relevant web results

## License

MIT
