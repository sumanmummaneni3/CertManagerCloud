#!/usr/bin/env bash
# =============================================================================
# certmonitor.sh - Docker management script for CertMonitor
# Usage: ./certmonitor.sh {start|stop|restart|status|logs|build|clean|help}
#
# Supports automatic Docker installation on:
#   - Ubuntu / Debian
#   - RHEL / CentOS / Fedora / Amazon Linux
#   - macOS (via Homebrew)
#   - Windows users are guided to the installer
# =============================================================================

set -euo pipefail

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ── Config ────────────────────────────────────────────────────────────────────
COMPOSE_FILE="docker-compose.yml"
APP_SERVICE="app"
DB_SERVICE="db"
APP_CONTAINER="cert_monitor_app"
DB_CONTAINER="cert_monitor_db"
APP_PORT="${APP_PORT:-8080}"
DB_PORT="${DB_PORT:-5432}"
POSTGRES_IMAGE="postgres:16-alpine"

# ── Helpers ───────────────────────────────────────────────────────────────────
info()    { echo -e "${BLUE}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
header()  { echo -e "\n${BOLD}${CYAN}$*${RESET}"; echo -e "${CYAN}--------------------------------------------------${RESET}"; }

step() {
    echo -e "\n${BOLD}  ▶ $*${RESET}"
}

# ── OS Detection ──────────────────────────────────────────────────────────────
detect_os() {
    OS="unknown"
    DISTRO="unknown"

    case "$(uname -s)" in
        Linux)
            OS="linux"
            if [ -f /etc/os-release ]; then
                # shellcheck disable=SC1091
                . /etc/os-release
                DISTRO="${ID:-unknown}"
            fi
            ;;
        Darwin)
            OS="macos"
            ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT)
            OS="windows"
            ;;
    esac
}

# ── Docker Installation ───────────────────────────────────────────────────────

install_docker_ubuntu_debian() {
    step "Installing Docker on Ubuntu/Debian..."

    if ! command -v curl &>/dev/null; then
        info "Installing curl..."
        sudo apt-get update -qq
        sudo apt-get install -y -qq curl
    fi

    info "Adding Docker's official GPG key and repository..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq ca-certificates gnupg lsb-release

    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/${DISTRO}/gpg \
        | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg

    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
        https://download.docker.com/linux/${DISTRO} \
        $(lsb_release -cs) stable" \
        | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    info "Installing Docker Engine and Compose plugin..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    info "Adding current user to the docker group (avoid needing sudo)..."
    sudo usermod -aG docker "$USER"

    sudo systemctl enable docker
    sudo systemctl start docker

    success "Docker installed successfully."
    warn "NOTE: Log out and back in (or run 'newgrp docker') for group membership to take effect."
}

install_docker_rhel_centos() {
    step "Installing Docker on RHEL/CentOS/Fedora/Amazon Linux..."

    local pkg_mgr
    if command -v dnf &>/dev/null; then
        pkg_mgr="dnf"
    else
        pkg_mgr="yum"
    fi

    info "Adding Docker repository..."
    sudo "$pkg_mgr" install -y -q yum-utils
    sudo yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo

    info "Installing Docker Engine and Compose plugin..."
    sudo "$pkg_mgr" install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    info "Adding current user to the docker group..."
    sudo usermod -aG docker "$USER"

    sudo systemctl enable docker
    sudo systemctl start docker

    success "Docker installed successfully."
    warn "NOTE: Log out and back in (or run 'newgrp docker') for group membership to take effect."
}

install_docker_macos() {
    step "Installing Docker on macOS..."

    if ! command -v brew &>/dev/null; then
        info "Homebrew not found. Installing Homebrew first..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    fi

    info "Installing Docker Desktop via Homebrew..."
    brew install --cask docker

    info "Launching Docker Desktop..."
    open -a Docker

    success "Docker Desktop installed."
    warn "NOTE: Wait for Docker Desktop to fully start (whale icon in menu bar) before running this script again."
}

install_docker_windows() {
    header "Docker Installation — Windows"
    echo -e "
  Automatic installation is not supported on Windows from a shell script.
  Please follow these steps:

  ${BOLD}Option 1 — Docker Desktop (recommended):${RESET}
    1. Download from: ${CYAN}https://www.docker.com/products/docker-desktop/${RESET}
    2. Run the installer and follow the prompts
    3. Restart your computer when asked
    4. After Docker Desktop starts, re-run this script in Git Bash or WSL2

  ${BOLD}Option 2 — WSL2 + Docker (advanced):${RESET}
    1. Enable WSL2:  ${CYAN}wsl --install${RESET}
    2. Install Ubuntu from the Microsoft Store
    3. Inside Ubuntu, re-run this script — it will auto-install Docker for you

  ${BOLD}Verify installation:${RESET}
    docker --version
    docker compose version
"
    exit 0
}

install_docker() {
    header "Docker Not Found — Installing"
    detect_os

    echo -e "  Detected OS: ${BOLD}${OS}${RESET} / Distro: ${BOLD}${DISTRO}${RESET}\n"

    read -rp "  Docker is required. Install it now? [Y/n]: " confirm
    if [[ "$confirm" =~ ^[Nn]$ ]]; then
        error "Docker is required to run CertMonitor. Aborting."
        exit 1
    fi

    case "$OS" in
        linux)
            case "$DISTRO" in
                ubuntu|debian|raspbian|linuxmint|pop)
                    install_docker_ubuntu_debian
                    ;;
                rhel|centos|fedora|amzn|rocky|almalinux)
                    install_docker_rhel_centos
                    ;;
                *)
                    warn "Unrecognised Linux distro '${DISTRO}'. Trying the official Docker convenience script..."
                    curl -fsSL https://get.docker.com | sudo sh
                    sudo usermod -aG docker "$USER"
                    sudo systemctl enable docker
                    sudo systemctl start docker
                    success "Docker installed via convenience script."
                    warn "NOTE: Log out and back in for group membership to take effect."
                    ;;
            esac
            ;;
        macos)
            install_docker_macos
            ;;
        windows)
            install_docker_windows
            ;;
        *)
            error "Unsupported OS. Please install Docker manually: https://docs.docker.com/get-docker/"
            exit 1
            ;;
    esac
}

# ── Check & ensure Docker is running ─────────────────────────────────────────
ensure_docker_running() {
    # Check if the Docker daemon is actually responding (not just installed)
    if ! docker info &>/dev/null; then
        warn "Docker is installed but not running."
        detect_os
        case "$OS" in
            linux)
                info "Starting Docker daemon..."
                sudo systemctl start docker
                sleep 3
                if ! docker info &>/dev/null; then
                    error "Failed to start Docker. Try: sudo systemctl start docker"
                    exit 1
                fi
                success "Docker daemon started."
                ;;
            macos)
                info "Starting Docker Desktop..."
                open -a Docker
                info "Waiting for Docker Desktop to be ready (this can take up to 60 seconds)..."
                local count=0
                until docker info &>/dev/null || [ $count -ge 30 ]; do
                    sleep 2
                    count=$((count + 1))
                    echo -ne "\r  Waiting... ${count}/30"
                done
                echo ""
                if ! docker info &>/dev/null; then
                    error "Docker Desktop did not start in time. Please start it manually and retry."
                    exit 1
                fi
                success "Docker Desktop is running."
                ;;
            *)
                error "Docker is not running. Please start Docker and retry."
                exit 1
                ;;
        esac
    fi
}

# ── Pull PostgreSQL image if not present ─────────────────────────────────────
ensure_postgres_image() {
    if ! docker image inspect "$POSTGRES_IMAGE" &>/dev/null; then
        info "PostgreSQL image '${POSTGRES_IMAGE}' not found locally — pulling from Docker Hub..."
        docker pull "$POSTGRES_IMAGE"
        success "PostgreSQL image downloaded."
    else
        info "PostgreSQL image '${POSTGRES_IMAGE}' already present locally."
    fi
}

# ── Preflight checks ──────────────────────────────────────────────────────────
check_dependencies() {
    # 1. Install Docker if missing
    if ! command -v docker &>/dev/null; then
        install_docker
        # After install, re-exec the script so the new PATH / group takes effect
        if ! command -v docker &>/dev/null; then
            error "Docker still not found after installation. Please open a new terminal and retry."
            exit 1
        fi
    fi

    # 2. Ensure Docker daemon is running
    ensure_docker_running

    # 3. Check Compose plugin
    if ! docker compose version &>/dev/null; then
        error "Docker Compose plugin not found."
        error "If you installed Docker manually, ensure the Compose plugin is included."
        error "See: https://docs.docker.com/compose/install/"
        exit 1
    fi

    # 4. Check compose file
    if [ ! -f "$COMPOSE_FILE" ]; then
        error "docker-compose.yml not found. Run this script from the project root."
        exit 1
    fi

    # 5. Bootstrap .env if missing
    if [ ! -f ".env" ]; then
        warn ".env not found. Copying from .env.example..."
        if [ -f ".env.example" ]; then
            cp .env.example .env
            warn "Review and update .env with your actual credentials before starting."
        else
            error ".env.example not found. Cannot proceed without environment configuration."
            exit 1
        fi
    fi

    # 6. Pull DB image if not cached
    ensure_postgres_image
}

container_running() {
    docker ps --filter "name=$1" --filter "status=running" --format "{{.Names}}" | grep -q "^$1$"
}

container_exists() {
    docker ps -a --filter "name=$1" --format "{{.Names}}" | grep -q "^$1$"
}

# ── start ─────────────────────────────────────────────────────────────────────
cmd_start() {
    header "Starting CertMonitor"
    check_dependencies

    if container_running "$APP_CONTAINER" && container_running "$DB_CONTAINER"; then
        warn "All services are already running."
        cmd_status
        return
    fi

    info "Starting services (DB first — app waits for DB healthcheck)..."
    docker compose up -d --remove-orphans

    info "Waiting for application to be ready on port ${APP_PORT}..."
    local retries=30
    local count=0
    until curl -sf "http://localhost:${APP_PORT}" &>/dev/null || [ $count -ge $retries ]; do
        sleep 2
        count=$((count + 1))
        echo -ne "\r  Attempt $count/$retries..."
    done
    echo ""

    if [ $count -ge $retries ]; then
        warn "App did not respond within timeout — it may still be initialising."
        warn "Check logs with:  ./certmonitor.sh logs app"
    else
        success "Application is up at http://localhost:${APP_PORT}"
    fi

    cmd_status
}

# ── stop ──────────────────────────────────────────────────────────────────────
cmd_stop() {
    header "Stopping CertMonitor"
    check_dependencies

    if ! container_exists "$APP_CONTAINER" && ! container_exists "$DB_CONTAINER"; then
        warn "No containers found — nothing to stop."
        return
    fi

    info "Stopping services gracefully..."
    docker compose stop
    success "All services stopped. Data volume is preserved."
    info "To remove containers entirely, run:  ./certmonitor.sh clean"
}

# ── restart ───────────────────────────────────────────────────────────────────
cmd_restart() {
    header "Restarting CertMonitor"
    check_dependencies
    info "Restarting all services..."
    docker compose restart
    success "Restart complete."
    cmd_status
}

# ── status ────────────────────────────────────────────────────────────────────
cmd_status() {
    header "CertMonitor Status"

    # Docker itself might not be running — handle gracefully
    if ! command -v docker &>/dev/null || ! docker info &>/dev/null; then
        warn "Docker is not installed or not running."
        return
    fi

    echo -e "\n${BOLD}Docker:${RESET}"
    docker_version=$(docker --version 2>/dev/null || echo "unknown")
    compose_version=$(docker compose version 2>/dev/null || echo "unknown")
    success "  $docker_version"
    success "  $compose_version"

    echo -e "\n${BOLD}Containers:${RESET}"
    printf "  %-28s %-14s %-22s %s\n" "NAME" "STATE" "HEALTH" "PORTS"
    printf "  %-28s %-14s %-22s %s\n" "----------------------------" "--------------" "----------------------" "-----"

    for container in "$DB_CONTAINER" "$APP_CONTAINER"; do
        if container_exists "$container"; then
            state=$(docker inspect --format '{{.State.Status}}' "$container" 2>/dev/null)
            health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' "$container" 2>/dev/null)
            ports=$(docker inspect --format '{{range $p, $conf := .NetworkSettings.Ports}}{{if $conf}}{{(index $conf 0).HostPort}}->{{$p}} {{end}}{{end}}' "$container" 2>/dev/null)

            case "$state" in
                running) state_out="${GREEN}running${RESET}" ;;
                exited)  state_out="${RED}exited${RESET}" ;;
                *)       state_out="${YELLOW}${state}${RESET}" ;;
            esac

            case "$health" in
                healthy)   health_out="${GREEN}healthy${RESET}" ;;
                unhealthy) health_out="${RED}unhealthy${RESET}" ;;
                starting)  health_out="${YELLOW}starting${RESET}" ;;
                *)         health_out="${health}" ;;
            esac

            printf "  %-28s %-24b %-32b %s\n" "$container" "$state_out" "$health_out" "$ports"
        else
            printf "  %-28s %b\n" "$container" "${RED}not found${RESET}"
        fi
    done

    echo -e "\n${BOLD}Images:${RESET}"
    if docker image inspect "$POSTGRES_IMAGE" &>/dev/null; then
        size=$(docker image inspect "$POSTGRES_IMAGE" --format '{{.Size}}' | awk '{printf "%.0f MB", $1/1024/1024}')
        success "  ${POSTGRES_IMAGE}  (${size})"
    else
        warn "  ${POSTGRES_IMAGE}  (not pulled)"
    fi

    echo -e "\n${BOLD}Endpoints:${RESET}"
    if curl -sf "http://localhost:${APP_PORT}" &>/dev/null; then
        success "  REST API   http://localhost:${APP_PORT}/api/v1"
    else
        warn "  REST API   http://localhost:${APP_PORT}/api/v1  (not reachable)"
    fi

    if nc -z localhost "$DB_PORT" &>/dev/null; then
        success "  PostgreSQL localhost:${DB_PORT}"
    else
        warn "  PostgreSQL localhost:${DB_PORT}  (not reachable)"
    fi

    echo -e "\n${BOLD}Volumes:${RESET}"
    docker volume ls --filter "name=postgres_data" --format "  {{.Name}}  (driver: {{.Driver}})" 2>/dev/null || true
    echo ""
}

# ── logs ──────────────────────────────────────────────────────────────────────
cmd_logs() {
    check_dependencies
    local service="${1:-}"
    case "$service" in
        app)
            header "App Logs"
            docker compose logs -f --tail=100 "$APP_SERVICE"
            ;;
        db)
            header "DB Logs"
            docker compose logs -f --tail=100 "$DB_SERVICE"
            ;;
        "")
            header "All Logs"
            docker compose logs -f --tail=50
            ;;
        *)
            error "Unknown service '$service'. Use:  logs [app|db]"
            exit 1
            ;;
    esac
}

# ── build ─────────────────────────────────────────────────────────────────────
cmd_build() {
    header "Building CertMonitor Images"
    check_dependencies
    info "Rebuilding all images from scratch (no cache)..."
    docker compose build --no-cache
    success "Build complete."
}

# ── clean ─────────────────────────────────────────────────────────────────────
cmd_clean() {
    header "Cleaning CertMonitor"
    check_dependencies
    warn "This will stop and remove all containers."
    read -rp "  Remove data volume too? This will DELETE all database data [y/N]: " confirm_volume
    echo ""
    info "Removing containers..."
    if [[ "$confirm_volume" =~ ^[Yy]$ ]]; then
        docker compose down -v
        success "Containers and data volume removed."
    else
        docker compose down
        success "Containers removed. Data volume preserved."
    fi
}

# ── help ──────────────────────────────────────────────────────────────────────
cmd_help() {
    echo -e "
${BOLD}${CYAN}CertMonitor Docker Manager${RESET}

${BOLD}Usage:${RESET}
  ./certmonitor.sh <command> [options]

${BOLD}Commands:${RESET}
  ${GREEN}start${RESET}            Start all services — installs Docker automatically if needed
  ${GREEN}stop${RESET}             Stop all services gracefully (data preserved)
  ${GREEN}restart${RESET}          Restart all running services
  ${GREEN}status${RESET}           Show Docker info, container health, and endpoint availability
  ${GREEN}logs${RESET} [app|db]    Tail logs — omit argument to follow all services
  ${GREEN}build${RESET}            Rebuild Docker images from scratch (no cache)
  ${GREEN}clean${RESET}            Remove containers (prompts before deleting the data volume)
  ${GREEN}help${RESET}             Show this message

${BOLD}Auto-install support:${RESET}
  Ubuntu / Debian       Automatic via apt + Docker official repo
  RHEL / CentOS / Fedora / Amazon Linux    Automatic via yum/dnf + Docker official repo
  Other Linux           Automatic via get.docker.com convenience script
  macOS                 Automatic via Homebrew (installs Homebrew too if needed)
  Windows               Manual — follow the printed instructions

${BOLD}Examples:${RESET}
  ./certmonitor.sh start          # First run: installs Docker if needed, then starts
  ./certmonitor.sh logs app
  ./certmonitor.sh status
  ./certmonitor.sh stop
  ./certmonitor.sh clean
"
}

# ── Entry point ───────────────────────────────────────────────────────────────
COMMAND="${1:-help}"

case "$COMMAND" in
    start)           cmd_start ;;
    stop)            cmd_stop ;;
    restart)         cmd_restart ;;
    status)          cmd_status ;;
    logs)            cmd_logs "${2:-}" ;;
    build)           cmd_build ;;
    clean)           cmd_clean ;;
    help|--help|-h)  cmd_help ;;
    *)
        error "Unknown command: '$COMMAND'"
        cmd_help
        exit 1
        ;;
esac
