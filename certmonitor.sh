#!/usr/bin/env bash
# =============================================================================
# certmonitor.sh - CertMonitor full setup and Docker management script
# Usage: ./certmonitor.sh {setup|start|stop|restart|status|logs|build|clean|help}
#
# Designed to run on a BARE Ubuntu VM with nothing pre-installed.
# The 'setup' command (also auto-runs inside 'start') will install:
#   - curl, unzip, git, netcat (basic tools)
#   - Docker Engine + Compose plugin
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
APP_PORT="${APP_PORT:-443}"
DB_PORT="${DB_PORT:-5432}"
POSTGRES_IMAGE="postgres:16-alpine"

# ── Helpers ───────────────────────────────────────────────────────────────────
info()    { echo -e "${BLUE}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
header()  { echo -e "\n${BOLD}${CYAN}$*${RESET}"; echo -e "${CYAN}--------------------------------------------------${RESET}"; }
step()    { echo -e "\n${BOLD}  ▶ $*${RESET}"; }

# ── Privilege helper ──────────────────────────────────────────────────────────
# Use sudo only if we are not already root
maybe_sudo() {
    if [ "$(id -u)" -eq 0 ]; then
        "$@"
    else
        sudo "$@"
    fi
}

# ── OS / Distro detection ─────────────────────────────────────────────────────
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
        Darwin)  OS="macos" ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT) OS="windows" ;;
    esac
}

# =============================================================================
# SECTION 1 — Prerequisites (bare-VM safe)
# =============================================================================

install_base_tools_ubuntu() {
    step "Installing base tools (curl, unzip, git, netcat, ca-certificates)..."
    maybe_sudo apt-get update -qq
    maybe_sudo apt-get install -y -qq \
        curl \
        unzip \
        git \
        netcat-openbsd \
        ca-certificates \
        gnupg \
        lsb-release \
        apt-transport-https \
        software-properties-common
    success "Base tools installed."
}

install_docker_ubuntu() {
    step "Installing Docker Engine + Compose plugin..."

    # Remove any old/unofficial docker packages
    maybe_sudo apt-get remove -y -qq \
        docker docker-engine docker.io containerd runc 2>/dev/null || true

    # Add Docker's official GPG key
    maybe_sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://download.docker.com/linux/${DISTRO}/gpg" \
        | maybe_sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    maybe_sudo chmod a+r /etc/apt/keyrings/docker.gpg

    # Add Docker apt repository
    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
        https://download.docker.com/linux/${DISTRO} \
        $(lsb_release -cs) stable" \
        | maybe_sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

    maybe_sudo apt-get update -qq
    maybe_sudo apt-get install -y -qq \
        docker-ce \
        docker-ce-cli \
        containerd.io \
        docker-buildx-plugin \
        docker-compose-plugin

    success "Docker Engine and Compose plugin installed."
}

configure_docker_group() {
    step "Configuring Docker group permissions..."

    # Add user to docker group so sudo isn't needed for docker commands
    if ! groups "$USER" | grep -q docker; then
        maybe_sudo usermod -aG docker "$USER"
        info "Added '$USER' to the docker group."
    else
        info "User '$USER' is already in the docker group."
    fi

    # Enable + start Docker daemon
    maybe_sudo systemctl enable docker --quiet
    maybe_sudo systemctl start docker

    success "Docker daemon started and enabled on boot."
}

ensure_docker_accessible() {
    # After adding the user to the docker group, the current shell session
    # won't have the new group yet. We detect this and run docker via
    # 'sg docker' (switch group) for the rest of this script session.
    if docker info &>/dev/null 2>&1; then
        # Already accessible — nothing to do
        DOCKER_CMD="docker"
    elif sg docker -c "docker info" &>/dev/null 2>&1; then
        # Accessible via sg (group applied without re-login)
        DOCKER_CMD="sg docker -c docker"
        warn "Using 'sg docker' for this session. Log out and back in to make this permanent."
    elif sudo docker info &>/dev/null 2>&1; then
        # Fall back to sudo
        DOCKER_CMD="sudo docker"
        warn "Running Docker with sudo for this session."
    else
        error "Docker is installed but cannot be reached. Try logging out and back in."
        exit 1
    fi
    export DOCKER_CMD
}

install_prerequisites_ubuntu() {
    install_base_tools_ubuntu
    install_docker_ubuntu
    configure_docker_group
    ensure_docker_accessible
}

install_prerequisites_rhel() {
    step "Installing base tools..."
    local pm="yum"
    command -v dnf &>/dev/null && pm="dnf"

    maybe_sudo "$pm" install -y -q \
        curl unzip git nmap-ncat ca-certificates

    step "Installing Docker Engine + Compose plugin..."
    maybe_sudo "$pm" install -y -q yum-utils
    maybe_sudo yum-config-manager \
        --add-repo https://download.docker.com/linux/centos/docker-ce.repo
    maybe_sudo "$pm" install -y \
        docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin

    maybe_sudo usermod -aG docker "$USER"
    maybe_sudo systemctl enable docker --quiet
    maybe_sudo systemctl start docker
    ensure_docker_accessible
}

install_prerequisites_macos() {
    step "Installing prerequisites on macOS..."
    if ! command -v brew &>/dev/null; then
        info "Installing Homebrew..."
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    fi
    brew install curl git netcat
    brew install --cask docker
    open -a Docker
    warn "Waiting for Docker Desktop to start (up to 60s)..."
    local i=0
    until docker info &>/dev/null || [ $i -ge 30 ]; do
        sleep 2; i=$((i+1)); echo -ne "\r  Waiting ${i}/30..."
    done
    echo ""
    docker info &>/dev/null || { error "Docker Desktop didn't start. Launch it manually then retry."; exit 1; }
    DOCKER_CMD="docker"
    export DOCKER_CMD
    success "Docker Desktop is running."
}

cmd_setup() {
    header "CertMonitor — Environment Setup"
    detect_os

    info "Detected OS: ${BOLD}${OS}${RESET} / Distro: ${BOLD}${DISTRO}${RESET}"

    # ── Already fully set up? ──────────────────────────────────────────────────
    local needs_install=false
    command -v curl    &>/dev/null || needs_install=true
    command -v git     &>/dev/null || needs_install=true
    command -v docker  &>/dev/null || needs_install=true

    if [ "$needs_install" = false ] && docker info &>/dev/null; then
        success "All prerequisites already installed."
        DOCKER_CMD="docker"
        export DOCKER_CMD
        return
    fi

    # ── Confirm before installing ──────────────────────────────────────────────
    echo ""
    warn "The following will be installed if missing:"
    echo "    curl, unzip, git, netcat, Docker Engine, Docker Compose plugin"
    echo ""
    read -rp "  Proceed with installation? [Y/n]: " confirm
    [[ "$confirm" =~ ^[Nn]$ ]] && { error "Aborted."; exit 1; }

    case "$OS" in
        linux)
            case "$DISTRO" in
                ubuntu|debian|raspbian|linuxmint|pop)
                    install_prerequisites_ubuntu ;;
                rhel|centos|fedora|amzn|rocky|almalinux)
                    install_prerequisites_rhel ;;
                *)
                    warn "Unrecognised distro '${DISTRO}' — trying Ubuntu path..."
                    DISTRO="ubuntu"
                    install_prerequisites_ubuntu ;;
            esac
            ;;
        macos)
            install_prerequisites_macos ;;
        windows)
            header "Windows — Manual Setup Required"
            echo -e "
  ${BOLD}Option 1 — Docker Desktop:${RESET}
    Download: ${CYAN}https://www.docker.com/products/docker-desktop/${RESET}

  ${BOLD}Option 2 — WSL2 + Ubuntu:${RESET}
    Run in PowerShell:  wsl --install
    Then re-run this script inside the Ubuntu terminal.
"
            exit 0 ;;
        *)
            error "Unsupported OS. Install Docker manually: https://docs.docker.com/get-docker/"
            exit 1 ;;
    esac

    echo ""
    success "Setup complete!"
    step "Verifying installation..."
    docker_version=$(${DOCKER_CMD} --version 2>/dev/null || echo "unknown")
    compose_version=${DOCKER_CMD} compose version 2>/dev/null || echo "unknown"
    success "  $docker_version"

    # ── Bootstrap .env ─────────────────────────────────────────────────────────
    if [ ! -f ".env" ] && [ -f ".env.example" ]; then
        cp .env.example .env
        warn ".env created from .env.example — review and update credentials before starting."
    fi
}

# =============================================================================
# SECTION 2 — Runtime checks (called before every command)
# =============================================================================

check_dependencies() {
    detect_os

    # 1. Install everything if Docker is missing
    if ! command -v docker &>/dev/null; then
        cmd_setup
    fi

    # 2. Ensure daemon is running
    if ! docker info &>/dev/null; then
        warn "Docker daemon not running. Attempting to start..."
        case "$OS" in
            linux)
                maybe_sudo systemctl start docker
                sleep 3
                docker info &>/dev/null || { error "Failed to start Docker daemon."; exit 1; }
                success "Docker daemon started." ;;
            macos)
                open -a Docker
                local i=0
                until docker info &>/dev/null || [ $i -ge 30 ]; do
                    sleep 2; i=$((i+1)); echo -ne "\r  Waiting ${i}/30..."
                done
                echo ""
                docker info &>/dev/null || { error "Docker Desktop didn't start. Launch it manually."; exit 1; }
                success "Docker Desktop is running." ;;
            *)
                error "Docker is not running. Please start it manually."
                exit 1 ;;
        esac
    fi

    # 3. Make sure docker is accessible (handle group membership)
    ensure_docker_accessible 2>/dev/null || true
    DOCKER_CMD="${DOCKER_CMD:-docker}"

    # 4. Compose plugin
    if ! docker compose version &>/dev/null; then
        error "Docker Compose plugin not found. Re-run:  ./certmonitor.sh setup"
        exit 1
    fi

    # 5. Compose file present
    if [ ! -f "$COMPOSE_FILE" ]; then
        error "docker-compose.yml not found. Run this script from the project root."
        exit 1
    fi

    # 6. Bootstrap .env
    if [ ! -f ".env" ]; then
        if [ -f ".env.example" ]; then
            cp .env.example .env
            warn ".env created from .env.example — update credentials before starting."
        else
            error ".env file missing and no .env.example found."
            exit 1
        fi
    fi

    # 7. Pull postgres image if not cached
    if ! docker image inspect "$POSTGRES_IMAGE" &>/dev/null; then
        info "Pulling ${POSTGRES_IMAGE} from Docker Hub..."
        docker pull "$POSTGRES_IMAGE"
        success "PostgreSQL image ready."
    fi
}

container_running() {
    docker ps --filter "name=$1" --filter "status=running" --format "{{.Names}}" | grep -q "^$1$"
}

container_exists() {
    docker ps -a --filter "name=$1" --format "{{.Names}}" | grep -q "^$1$"
}

# =============================================================================
# SECTION 3 — Commands
# =============================================================================

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
    local retries=30 count=0
    until curl -sfk "https://localhost:${APP_PORT}" &>/dev/null || [ $count -ge $retries ]; do
        sleep 2; count=$((count+1))
        echo -ne "\r  Attempt $count/$retries..."
    done
    echo ""

    if [ $count -ge $retries ]; then
        warn "App did not respond within timeout — may still be initialising."
        warn "Check logs:  ./certmonitor.sh logs app"
    else
        success "Application is up at http://localhost:${APP_PORT}"
    fi

    cmd_status
}

cmd_stop() {
    header "Stopping CertMonitor"
    check_dependencies

    if ! container_exists "$APP_CONTAINER" && ! container_exists "$DB_CONTAINER"; then
        warn "No containers found — nothing to stop."
        return
    fi

    info "Stopping services gracefully..."
    docker compose stop
    success "All services stopped. Data volume preserved."
    info "To remove containers:  ./certmonitor.sh clean"
}

cmd_restart() {
    header "Restarting CertMonitor"
    check_dependencies
    info "Restarting all services..."
    docker compose restart
    success "Restart complete."
    cmd_status
}

cmd_status() {
    header "CertMonitor Status"

    if ! command -v docker &>/dev/null || ! docker info &>/dev/null; then
        warn "Docker is not installed or not running. Run:  ./certmonitor.sh setup"
        return
    fi

    echo -e "\n${BOLD}Environment:${RESET}"
    success "  $(docker --version)"
    success "  $(docker compose version)"
    success "  OS: $(uname -srm)"

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
        warn "  ${POSTGRES_IMAGE}  (not pulled yet)"
    fi

    echo -e "\n${BOLD}Endpoints:${RESET}"
    if curl -sfk "https://localhost:${APP_PORT}" &>/dev/null; then
        success "  REST API   http://localhost:${APP_PORT}/api/v1"
    else
        warn "  REST API   http://localhost:${APP_PORT}/api/v1  (not reachable)"
    fi

    if nc -z localhost "$DB_PORT" &>/dev/null 2>&1; then
        success "  PostgreSQL localhost:${DB_PORT}"
    else
        warn "  PostgreSQL localhost:${DB_PORT}  (not reachable)"
    fi

    echo -e "\n${BOLD}Volumes:${RESET}"
    docker volume ls --filter "name=postgres_data" \
        --format "  {{.Name}}  (driver: {{.Driver}})" 2>/dev/null || true
    echo ""
}

cmd_logs() {
    check_dependencies
    local service="${1:-}"
    case "$service" in
        app) header "App Logs";  docker compose logs -f --tail=100 "$APP_SERVICE" ;;
        db)  header "DB Logs";   docker compose logs -f --tail=100 "$DB_SERVICE" ;;
        "")  header "All Logs";  docker compose logs -f --tail=50 ;;
        *)   error "Unknown service '$service'. Use: logs [app|db]"; exit 1 ;;
    esac
}

cmd_build() {
    header "Building CertMonitor Images"
    check_dependencies
    info "Rebuilding all images from scratch (no cache)..."
    docker compose build --no-cache
    success "Build complete."
}

cmd_clean() {
    header "Cleaning CertMonitor"
    check_dependencies
    warn "This will stop and remove all containers."
    read -rp "  Remove data volume too? This will DELETE all database data [y/N]: " confirm_volume
    echo ""
    if [[ "$confirm_volume" =~ ^[Yy]$ ]]; then
        docker compose down -v
        success "Containers and data volume removed."
    else
        docker compose down
        success "Containers removed. Data volume preserved."
    fi
}

cmd_help() {
    echo -e "
${BOLD}${CYAN}CertMonitor Docker Manager${RESET}
${BOLD}Designed to run on a bare Ubuntu VM — installs all tools automatically.${RESET}

${BOLD}Usage:${RESET}
  ./certmonitor.sh <command> [options]

${BOLD}Commands:${RESET}
  ${GREEN}setup${RESET}            Install all prerequisites (curl, git, Docker, Compose)
  ${GREEN}start${RESET}            Start all services — runs setup automatically if needed
  ${GREEN}stop${RESET}             Stop all services gracefully (data preserved)
  ${GREEN}restart${RESET}          Restart all running services
  ${GREEN}status${RESET}           Show environment info, container health, endpoints
  ${GREEN}logs${RESET} [app|db]    Tail logs — omit argument to follow all services
  ${GREEN}build${RESET}            Rebuild Docker images from scratch (no cache)
  ${GREEN}clean${RESET}            Remove containers (prompts before deleting the data volume)
  ${GREEN}help${RESET}             Show this message

${BOLD}First-time usage on a bare Ubuntu VM:${RESET}
  1. Upload or clone the project folder to the VM
  2. chmod +x certmonitor.sh
  3. ./certmonitor.sh start
     (installs curl, git, netcat, Docker, Compose automatically — requires sudo)

${BOLD}What gets installed automatically:${RESET}
  Ubuntu/Debian     curl, unzip, git, netcat-openbsd, Docker CE, Compose plugin
  RHEL/CentOS       curl, unzip, git, nmap-ncat,      Docker CE, Compose plugin
  macOS             Homebrew, git, netcat, Docker Desktop

${BOLD}Examples:${RESET}
  ./certmonitor.sh start
  ./certmonitor.sh logs app
  ./certmonitor.sh status
  ./certmonitor.sh stop
  ./certmonitor.sh clean
"
}

# ── Entry point ───────────────────────────────────────────────────────────────
COMMAND="${1:-help}"
DOCKER_CMD="docker"

case "$COMMAND" in
    setup)           cmd_setup ;;
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
        exit 1 ;;
esac
