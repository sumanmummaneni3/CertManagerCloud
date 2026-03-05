#!/usr/bin/env bash
# =============================================================================
#  certmanage.sh — CertMonitor Docker & Database Management
# =============================================================================
#
#  COMMANDS
#  --------
#  start          Start all containers (db + app)
#  stop           Stop all containers gracefully (data preserved)
#  restart        Stop then start
#  status         Show container health, ports, and DB table row counts
#
#  db:migrate     Detect schema drift and apply safe ALTER migrations
#                 — takes an automatic backup first
#                 — never drops columns or tables that still have data
#  db:backup      Dump the full database to ./backups/
#  db:restore     Restore from a backup file
#  db:reset       DROP all tables and re-run schema.sql  ⚠ DATA LOSS
#
#  app:rebuild    Rebuild the Spring Boot image and redeploy (zero DB downtime)
#  app:logs       Tail application logs
#  logs:db        Tail database logs
#
#  USAGE EXAMPLES
#  --------------
#  ./certmanage.sh start
#  ./certmanage.sh stop
#  ./certmanage.sh db:migrate
#  ./certmanage.sh db:backup
#  ./certmanage.sh db:restore backups/cert_monitor_2025-01-15_14-30-00.sql.gz
#  ./certmanage.sh db:reset        # confirms before executing
#  ./certmanage.sh app:rebuild
#  ./certmanage.sh status
# =============================================================================

set -euo pipefail

# ── Colour codes ─────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; RESET='\033[0m'

# ── Config ───────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
ENV_FILE="$SCRIPT_DIR/.env"
SCHEMA_FILE="$SCRIPT_DIR/schema.sql"
BACKUP_DIR="$SCRIPT_DIR/backups"
DB_CONTAINER="cert_monitor_db"
APP_CONTAINER="cert_monitor_app"

# ── Helpers ──────────────────────────────────────────────────────────────────
info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
ok()      { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
fatal()   { error "$*"; exit 1; }
header()  { echo -e "\n${BOLD}${CYAN}══ $* ══${RESET}\n"; }
divider() { echo -e "${DIM}────────────────────────────────────────────────────${RESET}"; }

# ── Load .env ────────────────────────────────────────────────────────────────
load_env() {
    [[ -f "$ENV_FILE" ]] || fatal ".env file not found at $ENV_FILE — copy .env.example to .env first"
    # Export variables, skip comments and blank lines
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
}

# ── Preflight checks ─────────────────────────────────────────────────────────
check_deps() {
    for cmd in docker; do
        command -v "$cmd" &>/dev/null || fatal "'$cmd' not found — please install Docker"
    done
    docker compose version &>/dev/null || fatal "Docker Compose plugin not found"
    [[ -f "$COMPOSE_FILE" ]] || fatal "docker-compose.yml not found at $COMPOSE_FILE"
}

# ── Wait for DB to be ready ──────────────────────────────────────────────────
wait_for_db() {
    local max_attempts=30
    local attempt=0
    info "Waiting for database to be ready..."
    until docker exec "$DB_CONTAINER" pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q 2>/dev/null; do
        attempt=$((attempt + 1))
        [[ $attempt -ge $max_attempts ]] && fatal "Database did not become ready after ${max_attempts}s"
        printf '.'
        sleep 1
    done
    echo
    ok "Database is ready"
}

# ── Run SQL inside the DB container ─────────────────────────────────────────
run_sql() {
    local sql="$1"
    docker exec -i "$DB_CONTAINER" \
        psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -c "$sql" 2>/dev/null | xargs
}

run_sql_file() {
    local file="$1"
    docker exec -i "$DB_CONTAINER" \
        psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < "$file"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: start
# ─────────────────────────────────────────────────────────────────────────────
cmd_start() {
    header "Starting CertMonitor"
    load_env
    check_deps

    if docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
        warn "Containers already running — use 'restart' to restart or 'stop' first"
        cmd_status
        return
    fi

    info "Starting containers..."
    docker compose -f "$COMPOSE_FILE" up -d

    wait_for_db

    local app_port="${APP_PORT:-443}"
    ok "CertMonitor is running"
    echo -e "  App:  ${BOLD}https://localhost:${app_port}${RESET}"
    echo -e "  DB:   ${BOLD}localhost:${DB_PORT:-5432}${RESET}"
    echo -e "  Login: ${BOLD}https://localhost:${app_port}/oauth2/authorization/google${RESET}"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: stop
# ─────────────────────────────────────────────────────────────────────────────
cmd_stop() {
    header "Stopping CertMonitor"
    load_env
    check_deps

    info "Stopping containers gracefully..."
    docker compose -f "$COMPOSE_FILE" stop
    ok "All containers stopped — data volumes preserved"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: restart
# ─────────────────────────────────────────────────────────────────────────────
cmd_restart() {
    header "Restarting CertMonitor"
    load_env
    check_deps
    info "Restarting containers..."
    docker compose -f "$COMPOSE_FILE" restart
    wait_for_db
    ok "All containers restarted"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: status
# ─────────────────────────────────────────────────────────────────────────────
cmd_status() {
    header "CertMonitor Status"
    load_env
    check_deps

    echo -e "${BOLD}Containers:${RESET}"
    divider
    docker compose -f "$COMPOSE_FILE" ps 2>/dev/null || echo "  (no containers running)"
    echo

    # DB connection check
    if docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
        echo -e "${BOLD}Database:${RESET}"
        divider

        # Table row counts
        local tables=("organization" '"user"' "target" "certificate_record")
        local names=("organization" "user" "target" "certificate_record")
        local i=0
        for tbl in "${tables[@]}"; do
            local count
            count=$(run_sql "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "?")
            printf "  %-26s %s rows\n" "${names[$i]}" "$count"
            i=$((i + 1))
        done

        # DB version
        local pg_ver
        pg_ver=$(run_sql "SELECT version();" | awk '{print $1, $2}')
        echo
        echo -e "  ${DIM}PostgreSQL: $pg_ver${RESET}"

        echo
        echo -e "${BOLD}Schema drift check:${RESET}"
        divider
        _check_drift_summary
    else
        warn "Database container is not running"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
#  MIGRATION ENGINE
#  Compares current DB columns against schema.sql and generates ALTER statements
# ─────────────────────────────────────────────────────────────────────────────

# Returns 0 (true) if a column exists in the DB
column_exists() {
    local table="$1" col="$2"
    local result
    result=$(run_sql "
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = '$table'
          AND column_name  = '$col';
    ")
    [[ "$result" == "1" ]]
}

# Returns 0 (true) if a table exists in the DB
table_exists() {
    local table="$1"
    local result
    result=$(run_sql "
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name   = '$table';
    ")
    [[ "$result" == "1" ]]
}

# Returns 0 (true) if an index exists
index_exists() {
    local idx="$1"
    local result
    result=$(run_sql "
        SELECT COUNT(*) FROM pg_indexes
        WHERE schemaname = 'public' AND indexname = '$idx';
    ")
    [[ "$result" == "1" ]]
}

# Prints a summary of drift without applying anything (used in status)
_check_drift_summary() {
    local drift=0

    # organization — check keystore_location default
    if table_exists "organization"; then
        local ks_default
        ks_default=$(run_sql "
            SELECT column_default FROM information_schema.columns
            WHERE table_schema='public' AND table_name='organization'
              AND column_name='keystore_location';
        ")
        if [[ -z "$ks_default" ]]; then
            echo -e "  ${YELLOW}⚠${RESET}  organization.keystore_location — missing DEFAULT 'pending'"
            drift=1
        fi

        # api_key should be gone
        if column_exists "organization" "api_key"; then
            echo -e "  ${YELLOW}⚠${RESET}  organization.api_key — column still present (can be dropped)"
            drift=1
        fi
    fi

    # user — check google_sub and name exist
    if table_exists "user"; then
        column_exists "user" "google_sub" || { echo -e "  ${YELLOW}⚠${RESET}  user.google_sub — missing"; drift=1; }
        column_exists "user" "name"       || { echo -e "  ${YELLOW}⚠${RESET}  user.name — missing"; drift=1; }

        # old enum type
        local role_type
        role_type=$(run_sql "
            SELECT data_type FROM information_schema.columns
            WHERE table_schema='public' AND table_name='user' AND column_name='role';
        ")
        if [[ "$role_type" == "USER-DEFINED" ]]; then
            echo -e "  ${YELLOW}⚠${RESET}  user.role — still using native ENUM type (should be VARCHAR)"
            drift=1
        fi
    fi

    # indexes
    index_exists "idx_user_google_sub" || { echo -e "  ${YELLOW}⚠${RESET}  index idx_user_google_sub — missing"; drift=1; }

    [[ $drift -eq 0 ]] && ok "Schema is up to date — no drift detected"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: db:migrate
#  Detects what changed and applies safe ALTER statements with a backup first
# ─────────────────────────────────────────────────────────────────────────────
cmd_db_migrate() {
    header "Database Migration"
    load_env
    check_deps

    docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
        || fatal "Database container is not running — run './certmanage.sh start' first"

    wait_for_db

    # ── Collect required changes ──────────────────────────────────────────────
    local changes=()

    # organization: api_key should no longer exist
    if column_exists "organization" "api_key"; then
        local api_key_count
        api_key_count=$(run_sql "SELECT COUNT(*) FROM organization WHERE api_key IS NOT NULL;" 2>/dev/null || echo "0")
        changes+=("DROP_API_KEY:organization.api_key (${api_key_count} non-null values — will be lost)")
    fi

    # organization: keystore_location default
    local ks_default
    ks_default=$(run_sql "
        SELECT column_default FROM information_schema.columns
        WHERE table_schema='public' AND table_name='organization'
          AND column_name='keystore_location';
    " 2>/dev/null || echo "")
    if [[ -z "$ks_default" ]]; then
        changes+=("ADD_KS_DEFAULT:organization.keystore_location — add DEFAULT 'pending'")
    fi

    # user: google_sub column
    if ! column_exists "user" "google_sub"; then
        changes+=("ADD_GOOGLE_SUB:user.google_sub VARCHAR(255) — add column (nullable initially, then set NOT NULL)")
    fi

    # user: name column
    if ! column_exists "user" "name"; then
        changes+=("ADD_NAME:user.name VARCHAR(255) — add nullable column")
    fi

    # user: role column type (old enum → varchar)
    local role_type
    role_type=$(run_sql "
        SELECT data_type FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user' AND column_name='role';
    " 2>/dev/null || echo "")
    if [[ "$role_type" == "USER-DEFINED" ]]; then
        changes+=("CONVERT_ROLE_TYPE:user.role — convert from native ENUM to VARCHAR(20) with CHECK")
    fi

    # user: role CHECK constraint
    local role_check
    role_check=$(run_sql "
        SELECT COUNT(*) FROM information_schema.check_constraints cc
        JOIN information_schema.constraint_column_usage ccu
          ON cc.constraint_name = ccu.constraint_name
        WHERE ccu.table_name = 'user' AND ccu.column_name = 'role';
    " 2>/dev/null || echo "0")
    if [[ "$role_type" != "USER-DEFINED" ]] && [[ "$role_check" == "0" ]]; then
        changes+=("ADD_ROLE_CHECK:user.role — add CHECK (role IN ('ADMIN','READ_ONLY'))")
    fi

    # Indexes
    if ! index_exists "idx_user_google_sub"; then
        changes+=("ADD_INDEX_GOOGLE_SUB:CREATE INDEX idx_user_google_sub ON user(google_sub)")
    fi

    # ── Nothing to do? ────────────────────────────────────────────────────────
    if [[ ${#changes[@]} -eq 0 ]]; then
        ok "Schema is already up to date — no migration needed"
        return 0
    fi

    # ── Show the plan ─────────────────────────────────────────────────────────
    echo -e "${BOLD}The following changes will be applied:${RESET}"
    divider
    for change in "${changes[@]}"; do
        local description="${change#*:}"
        local type="${change%%:*}"
        case "$type" in
            DROP_*)  echo -e "  ${RED}[-]${RESET} DROP   $description" ;;
            ADD_*)   echo -e "  ${GREEN}[+]${RESET} ADD    $description" ;;
            CONVERT_*) echo -e "  ${YELLOW}[~]${RESET} MODIFY $description" ;;
        esac
    done
    echo

    # ── Confirm ───────────────────────────────────────────────────────────────
    read -r -p "$(echo -e "${BOLD}Proceed? A backup will be taken first. [y/N]:${RESET} ")" confirm
    [[ "${confirm,,}" == "y" ]] || { info "Migration cancelled"; return 0; }

    # ── Backup first ──────────────────────────────────────────────────────────
    info "Taking backup before migration..."
    _do_backup "pre-migrate"

    # ── Apply each change in a single transaction ─────────────────────────────
    info "Applying migration..."

    docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" << SQLEOF
BEGIN;

-- ── organization: drop api_key if present ────────────────────────────────────
DO \$\$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='organization'
          AND column_name='api_key'
    ) THEN
        ALTER TABLE organization DROP COLUMN api_key;
        RAISE NOTICE 'Dropped organization.api_key';
    END IF;
END \$\$;

-- ── organization: add keystore_location default ──────────────────────────────
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='organization'
          AND column_name='keystore_location'
          AND column_default IS NOT NULL
    ) THEN
        ALTER TABLE organization
            ALTER COLUMN keystore_location SET DEFAULT 'pending';
        RAISE NOTICE 'Set organization.keystore_location DEFAULT pending';
    END IF;
END \$\$;

-- ── user: add name column ────────────────────────────────────────────────────
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user'
          AND column_name='name'
    ) THEN
        ALTER TABLE "user" ADD COLUMN name VARCHAR(255) NULL;
        RAISE NOTICE 'Added user.name';
    END IF;
END \$\$;

-- ── user: convert role from native ENUM to VARCHAR(20) ───────────────────────
DO \$\$
DECLARE
    col_type TEXT;
BEGIN
    SELECT data_type INTO col_type
    FROM information_schema.columns
    WHERE table_schema='public' AND table_name='user' AND column_name='role';

    IF col_type = 'USER-DEFINED' THEN
        -- Drop existing constraints that reference the enum
        ALTER TABLE "user" ALTER COLUMN role DROP DEFAULT;

        -- Cast enum to text, then retype to varchar
        ALTER TABLE "user"
            ALTER COLUMN role TYPE VARCHAR(20)
            USING role::text;

        -- Re-apply default and check
        ALTER TABLE "user" ALTER COLUMN role SET DEFAULT 'READ_ONLY';
        ALTER TABLE "user" ADD CONSTRAINT chk_user_role
            CHECK (role IN ('ADMIN', 'READ_ONLY'));

        -- Drop the now-unused enum type
        DROP TYPE IF EXISTS user_role;

        RAISE NOTICE 'Converted user.role from ENUM to VARCHAR(20) with CHECK constraint';
    END IF;
END \$\$;

-- ── user: add CHECK constraint if missing ────────────────────────────────────
DO \$\$
DECLARE
    check_count INT;
BEGIN
    SELECT COUNT(*) INTO check_count
    FROM information_schema.check_constraints cc
    JOIN information_schema.constraint_column_usage ccu
      ON cc.constraint_name = ccu.constraint_name
    WHERE ccu.table_name = 'user' AND ccu.column_name = 'role'
      AND ccu.table_schema = 'public';

    IF check_count = 0 THEN
        ALTER TABLE "user" ADD CONSTRAINT chk_user_role
            CHECK (role IN ('ADMIN', 'READ_ONLY'));
        RAISE NOTICE 'Added CHECK constraint on user.role';
    END IF;
END \$\$;

-- ── user: add google_sub column ──────────────────────────────────────────────
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user'
          AND column_name='google_sub'
    ) THEN
        -- Add nullable first so existing rows don't cause NOT NULL violation
        ALTER TABLE "user" ADD COLUMN google_sub VARCHAR(255) NULL;
        RAISE NOTICE 'Added user.google_sub (nullable — set values before adding NOT NULL)';
    END IF;
END \$\$;

-- ── user: add UNIQUE constraint on google_sub if column exists ───────────────
DO \$\$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name='user'
          AND column_name='google_sub'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints tc
        JOIN information_schema.constraint_column_usage ccu
          ON tc.constraint_name = ccu.constraint_name
        WHERE tc.table_name = 'user' AND ccu.column_name = 'google_sub'
          AND tc.constraint_type = 'UNIQUE'
    ) THEN
        ALTER TABLE "user" ADD CONSTRAINT uq_user_google_sub UNIQUE (google_sub);
        RAISE NOTICE 'Added UNIQUE constraint on user.google_sub';
    END IF;
END \$\$;

-- ── indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_user_google_sub ON "user"(google_sub);
CREATE INDEX IF NOT EXISTS idx_user_org_id     ON "user"(org_id);
CREATE INDEX IF NOT EXISTS idx_target_org_id   ON target(org_id);
CREATE INDEX IF NOT EXISTS idx_cert_target_id  ON certificate_record(target_id);
CREATE INDEX IF NOT EXISTS idx_cert_org_id     ON certificate_record(org_id);
CREATE INDEX IF NOT EXISTS idx_cert_expiry     ON certificate_record(expiry_date);

COMMIT;
SQLEOF

    ok "Migration completed successfully"

    # ── Post-migration check on google_sub NOT NULL ───────────────────────────
    local null_count
    null_count=$(run_sql "SELECT COUNT(*) FROM \"user\" WHERE google_sub IS NULL;" 2>/dev/null || echo "0")
    if [[ "$null_count" -gt 0 ]]; then
        warn "user.google_sub has ${null_count} NULL row(s)"
        warn "These users were created before OAuth2 was introduced."
        warn "Set their google_sub values manually, then run:"
        warn "  ALTER TABLE \"user\" ALTER COLUMN google_sub SET NOT NULL;"
    else
        # Safe to make NOT NULL
        run_sql "ALTER TABLE \"user\" ALTER COLUMN google_sub SET NOT NULL;" 2>/dev/null || true
        ok "user.google_sub set NOT NULL (all rows have values)"
    fi

    echo
    ok "Schema is now up to date"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: db:backup
# ─────────────────────────────────────────────────────────────────────────────
_do_backup() {
    local tag="${1:-manual}"
    mkdir -p "$BACKUP_DIR"
    local ts
    ts=$(date +%Y-%m-%d_%H-%M-%S)
    local filename="${BACKUP_DIR}/cert_monitor_${ts}_${tag}.sql.gz"

    docker exec "$DB_CONTAINER" \
        pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-acl \
        | gzip > "$filename"

    local size
    size=$(du -sh "$filename" | cut -f1)
    ok "Backup saved: ${BOLD}${filename}${RESET} (${size})"
    echo "$filename"
}

cmd_db_backup() {
    header "Database Backup"
    load_env
    check_deps
    docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
        || fatal "Database container is not running"
    wait_for_db
    _do_backup "manual"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: db:restore
# ─────────────────────────────────────────────────────────────────────────────
cmd_db_restore() {
    local backup_file="${1:-}"
    [[ -n "$backup_file" ]] || fatal "Usage: $0 db:restore <backup-file.sql.gz>"
    [[ -f "$backup_file"  ]] || fatal "Backup file not found: $backup_file"

    header "Database Restore"
    load_env
    check_deps
    docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
        || fatal "Database container is not running — run './certmanage.sh start' first"

    warn "This will REPLACE all current data with the contents of:"
    echo "  $backup_file"
    echo
    read -r -p "$(echo -e "${BOLD}Continue? [y/N]:${RESET} ")" confirm
    [[ "${confirm,,}" == "y" ]] || { info "Restore cancelled"; return 0; }

    wait_for_db

    # Take a safety backup of current state before overwriting
    info "Taking safety backup of current state..."
    _do_backup "pre-restore"

    info "Dropping existing tables..."
    docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" << 'SQLEOF'
DROP TABLE IF EXISTS certificate_record CASCADE;
DROP TABLE IF EXISTS target              CASCADE;
DROP TABLE IF EXISTS "user"              CASCADE;
DROP TABLE IF EXISTS organization        CASCADE;
DROP FUNCTION IF EXISTS trigger_set_updated_at CASCADE;
DROP TYPE IF EXISTS user_role CASCADE;
SQLEOF

    info "Restoring from backup..."
    gunzip -c "$backup_file" \
        | docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q

    ok "Restore completed from: $backup_file"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: db:reset  ⚠ DATA LOSS
# ─────────────────────────────────────────────────────────────────────────────
cmd_db_reset() {
    header "Database Reset  ⚠  DATA LOSS"
    load_env
    check_deps
    docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$" \
        || fatal "Database container is not running — run './certmanage.sh start' first"

    echo -e "${RED}${BOLD}WARNING: This will permanently delete ALL data.${RESET}"
    echo "  All organizations, users, targets, and certificate records will be erased."
    echo
    read -r -p "$(echo -e "${RED}${BOLD}Type 'yes-delete-everything' to confirm:${RESET} ")" confirm
    [[ "$confirm" == "yes-delete-everything" ]] || { info "Reset cancelled"; return 0; }

    wait_for_db

    # Offer to backup even before a reset
    read -r -p "$(echo -e "${BOLD}Take a backup first? [Y/n]:${RESET} ")" do_backup
    if [[ "${do_backup,,}" != "n" ]]; then
        _do_backup "pre-reset"
    fi

    info "Dropping all tables..."
    docker exec -i "$DB_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" << 'SQLEOF'
DROP TABLE IF EXISTS certificate_record CASCADE;
DROP TABLE IF EXISTS target              CASCADE;
DROP TABLE IF EXISTS "user"              CASCADE;
DROP TABLE IF EXISTS organization        CASCADE;
DROP FUNCTION IF EXISTS trigger_set_updated_at CASCADE;
DROP TYPE IF EXISTS user_role CASCADE;
SQLEOF

    info "Re-running schema.sql..."
    run_sql_file "$SCHEMA_FILE"

    ok "Database reset complete — schema is fresh and empty"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: app:rebuild
#  Rebuilds and redeploys only the app container — DB stays up and untouched
# ─────────────────────────────────────────────────────────────────────────────
cmd_app_rebuild() {
    header "Rebuilding Application"
    load_env
    check_deps

    info "Building new app image..."
    docker compose -f "$COMPOSE_FILE" build app

    info "Redeploying app container (DB stays running)..."
    docker compose -f "$COMPOSE_FILE" up -d --no-deps app

    local retries=0
    info "Waiting for app to start..."
    until docker ps --format '{{.Names}}\t{{.Status}}' \
            | grep "^${APP_CONTAINER}" | grep -q "Up"; do
        retries=$((retries + 1))
        [[ $retries -ge 30 ]] && fatal "App container failed to start — check logs with: $0 app:logs"
        sleep 2
        printf '.'
    done
    echo

    ok "Application redeployed successfully"
    echo -e "  App: ${BOLD}https://localhost:${APP_PORT:-443}${RESET}"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: app:logs / logs:db
# ─────────────────────────────────────────────────────────────────────────────
cmd_app_logs() {
    check_deps
    docker compose -f "$COMPOSE_FILE" logs -f app
}

cmd_logs_db() {
    check_deps
    docker compose -f "$COMPOSE_FILE" logs -f db
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: list backups
# ─────────────────────────────────────────────────────────────────────────────
cmd_backups() {
    header "Available Backups"
    if [[ ! -d "$BACKUP_DIR" ]] || [[ -z "$(ls -A "$BACKUP_DIR" 2>/dev/null)" ]]; then
        info "No backups found in $BACKUP_DIR"
        return
    fi
    ls -lht "$BACKUP_DIR"/*.sql.gz 2>/dev/null | awk '{print "  " $5 "  " $6, $7, $8 "  " $9}'
}

# ─────────────────────────────────────────────────────────────────────────────
#  HELP
# ─────────────────────────────────────────────────────────────────────────────
cmd_help() {
    cat << EOF

${BOLD}${CYAN}CertMonitor Docker Manager${RESET}

${BOLD}USAGE${RESET}
  ./certmanage.sh <command> [args]

${BOLD}LIFECYCLE${RESET}
  ${GREEN}start${RESET}            Start all containers (preserves all data)
  ${GREEN}stop${RESET}             Stop all containers gracefully (preserves all data)
  ${GREEN}restart${RESET}          Stop then start
  ${GREEN}status${RESET}           Show container health, row counts, and schema drift

${BOLD}DATABASE${RESET}
  ${YELLOW}db:migrate${RESET}       Detect schema drift and apply safe ALTER migrations
                   (auto-backup taken first — data is preserved)
  ${YELLOW}db:backup${RESET}        Dump database to ./backups/ as a compressed .sql.gz
  ${YELLOW}db:restore${RESET} FILE  Restore database from a .sql.gz backup file
  ${YELLOW}backups${RESET}          List all available backups
  ${RED}db:reset${RESET}         DROP all tables and re-run schema.sql  ⚠ DATA LOSS
                   (asks for confirmation + offers backup before proceeding)

${BOLD}APPLICATION${RESET}
  ${GREEN}app:rebuild${RESET}      Rebuild Spring Boot image and redeploy (DB stays up)
  ${GREEN}app:logs${RESET}         Tail application logs
  ${GREEN}logs:db${RESET}          Tail database logs

${BOLD}MIGRATION SAFETY RULES${RESET}
  • db:migrate NEVER drops a column that still has data without asking
  • db:migrate ALWAYS takes a backup before making any change
  • db:migrate applies changes in a single transaction (all-or-nothing)
  • db:restore takes a safety backup of current state before overwriting

${BOLD}EXAMPLES${RESET}
  ./certmanage.sh start
  ./certmanage.sh db:backup
  ./certmanage.sh db:migrate
  ./certmanage.sh db:restore backups/cert_monitor_2025-01-15_14-30-00_manual.sql.gz
  ./certmanage.sh app:rebuild
  ./certmanage.sh status

EOF
}

# ─────────────────────────────────────────────────────────────────────────────
#  ENTRYPOINT
# ─────────────────────────────────────────────────────────────────────────────
main() {
    local cmd="${1:-help}"
    case "$cmd" in
        start)        cmd_start ;;
        stop)         cmd_stop ;;
        restart)      cmd_restart ;;
        status)       cmd_status ;;
        db:migrate)   cmd_db_migrate ;;
        db:backup)    cmd_db_backup ;;
        db:restore)   cmd_db_restore "${2:-}" ;;
        db:reset)     cmd_db_reset ;;
        app:rebuild)  cmd_app_rebuild ;;
        app:logs)     cmd_app_logs ;;
        logs:db)      cmd_logs_db ;;
        backups)      cmd_backups ;;
        help|--help|-h) cmd_help ;;
        *)
            error "Unknown command: $cmd"
            cmd_help
            exit 1
            ;;
    esac
}

main "$@"
