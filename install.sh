#!/bin/bash
# Ollama Rust Web UI Installer v2.0.0
# Optimized for Desktop Linux (Debian/Ubuntu, Arch, Fedora, RHEL)
# Usage: curl -fsSL https://raw.githubusercontent.com/starlessoblivion/ollama-rust/main/install.sh | bash

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() { echo -e "${BLUE}[*]${NC} $1"; }
print_success() { echo -e "${GREEN}[✓]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[!]${NC} $1"; }
print_error() { echo -e "${RED}[✗]${NC} $1"; }

INSTALL_DIR="$HOME/ollama-rust"
REPO_URL="https://github.com/starlessoblivion/ollama-rust.git"

echo ""
echo -e "${GREEN}╔═══════════════════════════════════════╗${NC}"
echo -e "${GREEN}║     Ollama Rust Web UI Installer      ║${NC}"
echo -e "${GREEN}║         Desktop Linux Edition         ║${NC}"
echo -e "${GREEN}╚═══════════════════════════════════════╝${NC}"
echo ""

# Detect distro
detect_distro() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        echo "$ID"
    elif command -v apt-get &> /dev/null; then
        echo "debian"
    elif command -v pacman &> /dev/null; then
        echo "arch"
    elif command -v dnf &> /dev/null; then
        echo "fedora"
    else
        echo "unknown"
    fi
}

DISTRO=$(detect_distro)
print_status "Detected: $DISTRO Linux"

# Install dependencies
install_dependencies() {
    print_status "Installing system dependencies..."

    case "$DISTRO" in
        ubuntu|debian|pop|linuxmint|elementary)
            sudo apt-get update
            sudo apt-get install -y git curl build-essential pkg-config libssl-dev
            ;;
        arch|manjaro|endeavouros)
            sudo pacman -Sy --noconfirm git curl base-devel openssl pkg-config
            ;;
        fedora)
            sudo dnf install -y git curl gcc make openssl-devel pkg-config
            ;;
        rhel|centos|rocky|almalinux)
            sudo dnf install -y git curl gcc make openssl-devel pkg-config
            ;;
        opensuse*|suse)
            sudo zypper install -y git curl gcc make libopenssl-devel pkg-config
            ;;
        *)
            print_warning "Unknown distro: $DISTRO"
            print_warning "Please install manually: git, curl, gcc, make, openssl-dev, pkg-config"
            read -p "Press Enter to continue or Ctrl+C to cancel..."
            ;;
    esac

    print_success "Dependencies installed"
}

# Install Rust via rustup
install_rust() {
    if [ -f "$HOME/.cargo/env" ]; then
        source "$HOME/.cargo/env"
    fi

    if command -v rustup &> /dev/null && rustup show active-toolchain &> /dev/null 2>&1; then
        RUST_VER=$(rustc --version 2>/dev/null || echo "installed")
        print_success "Rust already installed: $RUST_VER"
        return 0
    fi

    if command -v rustc &> /dev/null && ! command -v rustup &> /dev/null; then
        print_warning "Found system rustc but no rustup - rustup required for WASM target"
        print_status "Installing rustup (will use alongside system rust)..."
    else
        print_status "Installing Rust via rustup..."
    fi

    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable

    if [ -f "$HOME/.cargo/env" ]; then
        source "$HOME/.cargo/env"
    fi

    print_success "Rust installed"
}

# Add WASM target
install_wasm_target() {
    print_status "Adding WebAssembly target..."
    rustup target add wasm32-unknown-unknown
    print_success "WASM target ready"
}

# Install cargo-leptos
install_cargo_leptos() {
    if command -v cargo-leptos &> /dev/null; then
        print_success "cargo-leptos already installed"
        return 0
    fi

    print_status "Installing cargo-leptos (this may take a few minutes)..."
    cargo install cargo-leptos --locked
    print_success "cargo-leptos installed"
}

# Clone or update repository
clone_repo() {
    if [ -d "$INSTALL_DIR" ]; then
        print_status "Updating existing installation..."
        cd "$INSTALL_DIR"
        git fetch origin main
        git reset --hard origin/main
    else
        print_status "Cloning repository..."
        git clone "$REPO_URL" "$INSTALL_DIR"
        cd "$INSTALL_DIR"
    fi
    print_success "Repository ready"
}

# Build the project
build_project() {
    print_status "Building project (this will take a few minutes on first run)..."
    cd "$INSTALL_DIR"
    cargo leptos build --release
    print_success "Build complete!"
}

# Create launcher script
create_launcher() {
    print_status "Creating launcher script..."

    cat > "$INSTALL_DIR/run.sh" << 'EOF'
#!/bin/bash
cd "$(dirname "$0")"
echo "Starting Ollama Rust Web UI..."
echo "Open http://localhost:3000 in your browser"
./target/release/ollama-rust
EOF

    chmod +x "$INSTALL_DIR/run.sh"

    # Create desktop entry
    mkdir -p "$HOME/.local/share/applications"
    cat > "$HOME/.local/share/applications/ollama-rust.desktop" << EOF
[Desktop Entry]
Name=Ollama Rust
Comment=Web interface for Ollama
Exec=$INSTALL_DIR/run.sh
Terminal=true
Type=Application
Categories=Development;Utility;
EOF

    print_success "Launcher created"
}

# Main
main() {
    install_dependencies
    install_rust

    if [ -f "$HOME/.cargo/env" ]; then
        source "$HOME/.cargo/env"
    fi

    install_wasm_target
    install_cargo_leptos
    clone_repo
    build_project
    create_launcher

    echo ""
    echo -e "${GREEN}╔═══════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║       Installation Complete!          ║${NC}"
    echo -e "${GREEN}╚═══════════════════════════════════════╝${NC}"
    echo ""
    echo -e "To start the server:"
    echo -e "  ${BLUE}$INSTALL_DIR/run.sh${NC}"
    echo ""
    echo -e "Or use cargo:"
    echo -e "  ${BLUE}cd $INSTALL_DIR && cargo leptos serve --release${NC}"
    echo ""
    echo -e "Then open ${GREEN}http://localhost:3000${NC} in your browser"
    echo ""
    echo -e "A desktop entry has been created - search for 'Ollama Rust' in your app menu"
    echo ""
}

main
