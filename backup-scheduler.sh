#!/usr/bin/env bash
# =============================================================================
#  backup-scheduler.sh — CertMonitor Automated Backup Scheduler
# =============================================================================
#
#  Manages scheduled backups via cron. Each schedule runs independently and
#  keeps its own retention window. All backup runs are logged to ./backups/logs/.
#
#  COMMANDS
#  --------
#  install          Install cron jobs based on config in this script
#  uninstall        Remove all CertMonitor cron entries
#  status           Show installed schedules, next run times, disk usage
#  run-backup TYPE  Execute a backup immediately (called by cron internally)
#                   TYPE: hourly | daily | weekly
#  list             List all backup files grouped by type
#  purge            Manually trigger retention cleanup on all types
#
#  CONFIGURATION (edit the section below)
#  ----------------------------------------
#  HOURLY_ENABLED   true/false
#  HOURLY_CRON      cron expression  (default: every hour at :00)
#  HOURLY_RETAIN    how many hourly backups to keep
#
#  DAILY_ENABLED    true/false
#  DAILY_CRON       cron expression  (default: 02:00 every day)
#  DAILY_RETAIN     how many daily backups to keep
#
#  WEEKLY_ENABLED   true/false
#  WEEKLY_CRON      cron expression  (default: Sunday 03:00)
#  WEEKLY_RETAIN    how many weekly backups to keep
#
#  USAGE EXAMPLES
#  --------------
#  ./backup-scheduler.sh install          # set up all enabled schedules
#  ./backup-scheduler.sh status           # see what's scheduled + disk usage
#  ./backup-scheduler.sh run-backup daily # run a daily backup right now
#  ./backup-scheduler.sh list             # browse all backup files
#  ./backup-scheduler.sh uninstall        # remove all schedules
# =============================================================================

set -euo pipefail

# ═════════════════════════════════════════════════════════════════════════════
#  CONFIGURATION — edit this section to change schedules and retention
# ═════════════════════════════════════════════════════════════════════════════

# ── Hourly backups ────────────────────────────────────────────────────────────
HOURLY_ENABLED=true
HOURLY_CRON="0 * * * *"          # every hour at :00
HOURLY_RETAIN=24                  # keep last 24 hourly backups (1 day)

# ── Daily backups ─────────────────────────────────────────────────────────────
DAILY_ENABLED=true
DAILY_CRON="0 2 * * *"           # every day at 02:00
DAILY_RETAIN=14                   # keep last 14 daily backups (2 weeks)

# ── Weekly backups ────────────────────────────────────────────────────────────
WEEKLY_ENABLED=true
WEEKLY_CRON="0 3 * * 0"          # every Sunday at 03:00
WEEKLY_RETAIN=8                   # keep last 8 weekly backups (2 months)

# ── Notifications (optional) ─────────────────────────────────────────────────
# Set to an email address to receive failure alerts (requires 'mail' command)
NOTIFY_EMAIL=""

# ── Backup directory layout ───────────────────────────────────────────────────
# backups/
#   hourly/   — hourly snapshots
#   daily/    — daily snapshots
#   weekly/   — weekly snapshots
#   logs/     — one log file per backup run

# ═════════════════════════════════════════════════════════════════════════════
#  INTERNALS — do not edit below this line
# ═════════════════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PATH="$(realpath "${BASH_SOURCE[0]}")"
ENV_FILE="$SCRIPT_DIR/.env"
DB_CONTAINER="cert_monitor_db"

BACKUP_ROOT="$SCRIPT_DIR/backups"
LOG_DIR="$BACKUP_ROOT/logs"

# Cron marker so we can find and remove only our entries
CRON_MARKER="# certmonitor-backup-scheduler"

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; RESET='\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
ok()      { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
fatal()   { error "$*"; exit 1; }
header()  { echo -e "\n${BOLD}${CYAN}══ $* ══${RESET}\n"; }
divider() { echo -e "${DIM}────────────────────────────────────────────────────${RESET}"; }

# ── Load .env ────────────────────────────────────────────────────────────────
load_env() {
    [[ -f "$ENV_FILE" ]] || fatal ".env not found at $ENV_FILE"
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
}

# ─────────────────────────────────────────────────────────────────────────────
#  CORE: run one backup of a given type
# ─────────────────────────────────────────────────────────────────────────────
cmd_run_backup() {
    local type="${1:-daily}"
    case "$type" in
        hourly|daily|weekly) ;;
        *) fatal "Unknown backup type '$type' — use: hourly | daily | weekly" ;;
    esac

    load_env

    local ts
    ts=$(date +%Y-%m-%d_%H-%M-%S)
    local dest_dir="${BACKUP_ROOT}/${type}"
    local filename="${dest_dir}/cert_monitor_${ts}_${type}.sql.gz"
    local log_file="${LOG_DIR}/backup_${type}_${ts}.log"

    mkdir -p "$dest_dir" "$LOG_DIR"

    # Redirect all output to log file AND stdout
    exec > >(tee -a "$log_file") 2>&1

    echo "========================================================"
    echo " CertMonitor Backup — type=${type}"
    echo " Started: $(date)"
    echo "========================================================"

    # ── Check DB container is running ────────────────────────────────────────
    if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
        error "Database container '${DB_CONTAINER}' is not running — backup aborted"
        _notify_failure "$type" "DB container not running"
        exit 1
    fi

    # ── Wait for DB ready ────────────────────────────────────────────────────
    local attempts=0
    until docker exec "$DB_CONTAINER" \
            pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" -q 2>/dev/null; do
        attempts=$((attempts + 1))
        [[ $attempts -ge 15 ]] && {
            error "DB not ready after 15s — backup aborted"
            _notify_failure "$type" "DB not ready"
            exit 1
        }
        sleep 1
    done

    # ── Run pg_dump ──────────────────────────────────────────────────────────
    info "Dumping database to: $filename"
    if docker exec "$DB_CONTAINER" \
            pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
            --no-owner --no-acl --verbose \
            2>>"$log_file" \
        | gzip > "$filename"; then

        local size
        size=$(du -sh "$filename" | cut -f1)
        ok "Backup complete: $filename ($size)"

        # ── Row counts snapshot ──────────────────────────────────────────────
        echo
        echo "── Row counts at backup time ──"
        for tbl in organization '"user"' target certificate_record; do
            local count
            count=$(docker exec "$DB_CONTAINER" \
                psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t \
                -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null | xargs || echo "?")
            printf "  %-26s %s rows\n" "$tbl" "$count"
        done

    else
        local exit_code=$?
        error "pg_dump failed with exit code $exit_code"
        rm -f "$filename"   # remove partial file
        _notify_failure "$type" "pg_dump failed (exit $exit_code)"
        exit 1
    fi

    # ── Retention cleanup ─────────────────────────────────────────────────────
    echo
    _apply_retention "$type" "$dest_dir"

    # ── Disk usage summary ───────────────────────────────────────────────────
    echo
    echo "── Backup directory usage ──"
    du -sh "${BACKUP_ROOT}/${type}/"
    echo
    echo "Finished: $(date)"
    echo "========================================================"

    _trim_old_logs
}

# ─────────────────────────────────────────────────────────────────────────────
#  RETENTION: keep only the N most recent backups of a given type
# ─────────────────────────────────────────────────────────────────────────────
_apply_retention() {
    local type="$1"
    local dir="$2"
    local retain

    case "$type" in
        hourly)  retain=$HOURLY_RETAIN  ;;
        daily)   retain=$DAILY_RETAIN   ;;
        weekly)  retain=$WEEKLY_RETAIN  ;;
        *)       retain=10              ;;
    esac

    info "Applying retention: keep last ${retain} ${type} backups"

    # List all backup files sorted newest-first, delete any beyond the limit
    local files
    mapfile -t files < <(ls -t "${dir}"/cert_monitor_*_${type}.sql.gz 2>/dev/null || true)
    local total=${#files[@]}

    if [[ $total -le $retain ]]; then
        ok "  ${total}/${retain} backups present — nothing to remove"
        return
    fi

    local to_delete=$(( total - retain ))
    info "  Removing ${to_delete} old backup(s)..."

    for (( i=retain; i<total; i++ )); do
        local f="${files[$i]}"
        rm -f "$f"
        echo "  Deleted: $(basename "$f")"
    done

    ok "  Retention complete — ${retain} backups kept"
}

# ─────────────────────────────────────────────────────────────────────────────
#  TRIM LOG FILES: keep last 90 log files
# ─────────────────────────────────────────────────────────────────────────────
_trim_old_logs() {
    local max_logs=90
    local log_files
    mapfile -t log_files < <(ls -t "${LOG_DIR}"/backup_*.log 2>/dev/null || true)
    local total=${#log_files[@]}
    if [[ $total -gt $max_logs ]]; then
        for (( i=max_logs; i<total; i++ )); do
            rm -f "${log_files[$i]}"
        done
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
#  NOTIFICATION on failure (if NOTIFY_EMAIL is set)
# ─────────────────────────────────────────────────────────────────────────────
_notify_failure() {
    local type="$1" reason="$2"
    if [[ -n "$NOTIFY_EMAIL" ]] && command -v mail &>/dev/null; then
        echo "CertMonitor ${type} backup failed on $(hostname) at $(date): ${reason}" \
            | mail -s "[CertMonitor] Backup FAILED: ${type}" "$NOTIFY_EMAIL"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: install — write cron entries
# ─────────────────────────────────────────────────────────────────────────────
cmd_install() {
    header "Installing Backup Schedules"

    [[ -f "$SCRIPT_PATH" ]] || fatal "Cannot determine script path — run from its directory"

    # Remove any existing entries first (clean slate)
    _remove_cron_entries

    # Build new entries
    local new_entries=()

    if [[ "$HOURLY_ENABLED" == "true" ]]; then
        new_entries+=("${HOURLY_CRON} ${SCRIPT_PATH} run-backup hourly >> ${LOG_DIR}/cron_hourly.log 2>&1 ${CRON_MARKER}:hourly")
        ok "Hourly   — ${HOURLY_CRON}  (keep ${HOURLY_RETAIN})"
    else
        info "Hourly   — disabled"
    fi

    if [[ "$DAILY_ENABLED" == "true" ]]; then
        new_entries+=("${DAILY_CRON} ${SCRIPT_PATH} run-backup daily >> ${LOG_DIR}/cron_daily.log 2>&1 ${CRON_MARKER}:daily")
        ok "Daily    — ${DAILY_CRON}   (keep ${DAILY_RETAIN})"
    else
        info "Daily    — disabled"
    fi

    if [[ "$WEEKLY_ENABLED" == "true" ]]; then
        new_entries+=("${WEEKLY_CRON} ${SCRIPT_PATH} run-backup weekly >> ${LOG_DIR}/cron_weekly.log 2>&1 ${CRON_MARKER}:weekly")
        ok "Weekly   — ${WEEKLY_CRON}  (keep ${WEEKLY_RETAIN})"
    else
        info "Weekly   — disabled"
    fi

    if [[ ${#new_entries[@]} -eq 0 ]]; then
        warn "All schedules are disabled — nothing installed"
        return
    fi

    # Write to crontab
    local current_cron
    current_cron=$(crontab -l 2>/dev/null || true)

    {
        echo "$current_cron"
        echo ""
        echo "${CRON_MARKER} — DO NOT EDIT THIS BLOCK MANUALLY"
        for entry in "${new_entries[@]}"; do
            echo "$entry"
        done
        echo "${CRON_MARKER}:end"
    } | grep -v '^$' | crontab -

    mkdir -p "$LOG_DIR"

    echo
    ok "${#new_entries[@]} schedule(s) installed"
    echo
    info "Backup files will be written to:"
    echo "  ${BACKUP_ROOT}/hourly/"
    echo "  ${BACKUP_ROOT}/daily/"
    echo "  ${BACKUP_ROOT}/weekly/"
    echo
    info "Run logs: ${LOG_DIR}/"
    echo
    info "To see what's installed: ./backup-scheduler.sh status"
    info "To test immediately:     ./backup-scheduler.sh run-backup daily"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: uninstall — remove cron entries
# ─────────────────────────────────────────────────────────────────────────────
cmd_uninstall() {
    header "Uninstalling Backup Schedules"
    _remove_cron_entries
    ok "All CertMonitor backup schedules removed from crontab"
    info "Backup files in ${BACKUP_ROOT}/ are untouched"
}

_remove_cron_entries() {
    local current_cron
    current_cron=$(crontab -l 2>/dev/null || true)

    if [[ -z "$current_cron" ]]; then
        return
    fi

    # Remove the block between the start and end markers (inclusive)
    local cleaned
    cleaned=$(echo "$current_cron" \
        | awk "/${CRON_MARKER} — DO NOT/{found=1} !found{print} /${CRON_MARKER}:end/{found=0}")

    if [[ -z "$cleaned" ]]; then
        crontab -r 2>/dev/null || true
    else
        echo "$cleaned" | crontab -
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: status — show schedules, next runs, disk usage
# ─────────────────────────────────────────────────────────────────────────────
cmd_status() {
    header "Backup Scheduler Status"

    # ── Installed schedules ──────────────────────────────────────────────────
    echo -e "${BOLD}Configured Schedules:${RESET}"
    divider
    _print_schedule_row "Hourly"  "$HOURLY_ENABLED"  "$HOURLY_CRON"  "$HOURLY_RETAIN"
    _print_schedule_row "Daily"   "$DAILY_ENABLED"   "$DAILY_CRON"   "$DAILY_RETAIN"
    _print_schedule_row "Weekly"  "$WEEKLY_ENABLED"  "$WEEKLY_CRON"  "$WEEKLY_RETAIN"
    echo

    # ── Active cron entries ──────────────────────────────────────────────────
    echo -e "${BOLD}Crontab Entries:${RESET}"
    divider
    local cron_entries
    cron_entries=$(crontab -l 2>/dev/null | grep "$CRON_MARKER" | grep -v "DO NOT\|:end" || true)
    if [[ -z "$cron_entries" ]]; then
        warn "No CertMonitor cron entries found — run './backup-scheduler.sh install' to set up"
    else
        while IFS= read -r line; do
            # Extract cron schedule and type
            local sched type
            sched=$(echo "$line" | awk '{print $1,$2,$3,$4,$5}')
            type=$(echo "$line" | grep -oP '(?<=run-backup )\w+')
            printf "  %-12s  %s\n" "$type" "$sched"
        done <<< "$cron_entries"
    fi
    echo

    # ── Backup file counts and sizes ─────────────────────────────────────────
    echo -e "${BOLD}Backup Storage:${RESET}"
    divider
    for type in hourly daily weekly; do
        local dir="${BACKUP_ROOT}/${type}"
        if [[ -d "$dir" ]]; then
            local count size latest
            count=$(ls "${dir}"/cert_monitor_*.sql.gz 2>/dev/null | wc -l | xargs)
            size=$(du -sh "$dir" 2>/dev/null | cut -f1)
            latest=$(ls -t "${dir}"/cert_monitor_*.sql.gz 2>/dev/null | head -1 | xargs basename 2>/dev/null || echo "none")
            printf "  %-8s  %3s files  %6s  latest: %s\n" "$type" "$count" "$size" "$latest"
        else
            printf "  %-8s  %s\n" "$type" "(no backups yet)"
        fi
    done

    # Overall
    echo
    if [[ -d "$BACKUP_ROOT" ]]; then
        local total_size
        total_size=$(du -sh "$BACKUP_ROOT" 2>/dev/null | cut -f1)
        echo -e "  Total backup storage: ${BOLD}${total_size}${RESET}"
    fi
    echo

    # ── Recent log entries ───────────────────────────────────────────────────
    echo -e "${BOLD}Recent Backup Runs (last 5):${RESET}"
    divider
    if [[ -d "$LOG_DIR" ]]; then
        local recent_logs
        mapfile -t recent_logs < <(ls -t "${LOG_DIR}"/backup_*.log 2>/dev/null | head -5)
        if [[ ${#recent_logs[@]} -eq 0 ]]; then
            info "No backup logs yet"
        else
            for log in "${recent_logs[@]}"; do
                local basename_log
                basename_log=$(basename "$log")
                # Extract type and timestamp from filename: backup_daily_2025-01-15_14-30-00.log
                local log_type log_ts
                log_type=$(echo "$basename_log" | cut -d_ -f2)
                log_ts=$(echo "$basename_log" | sed 's/backup_[a-z]*_//' | sed 's/\.log//' | tr '_' ' ')

                # Check if backup succeeded by looking for OK marker in log
                local result
                if grep -q "\[OK\]" "$log" 2>/dev/null; then
                    result="${GREEN}✓ success${RESET}"
                elif grep -q "\[ERROR\]" "$log" 2>/dev/null; then
                    result="${RED}✗ failed${RESET}"
                else
                    result="${YELLOW}? unknown${RESET}"
                fi

                printf "  %-8s  %s  " "$log_type" "$log_ts"
                echo -e "$result"
            done
        fi
    else
        info "No logs yet — backups have not run"
    fi
    echo

    # ── Notify config ────────────────────────────────────────────────────────
    if [[ -n "$NOTIFY_EMAIL" ]]; then
        ok "Failure notifications → $NOTIFY_EMAIL"
    else
        info "Failure notifications: disabled (set NOTIFY_EMAIL to enable)"
    fi
}

_print_schedule_row() {
    local label="$1" enabled="$2" schedule="$3" retain="$4"
    if [[ "$enabled" == "true" ]]; then
        printf "  ${GREEN}●${RESET} %-8s  cron: %-16s  retain: %s\n" \
            "$label" "\"$schedule\"" "${retain} backups"
    else
        printf "  ${DIM}○ %-8s  disabled${RESET}\n" "$label"
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: list — list all backup files grouped by type
# ─────────────────────────────────────────────────────────────────────────────
cmd_list() {
    header "All Backup Files"

    local found=false
    for type in hourly daily weekly; do
        local dir="${BACKUP_ROOT}/${type}"
        if [[ -d "$dir" ]] && ls "${dir}"/cert_monitor_*.sql.gz &>/dev/null 2>&1; then
            found=true
            echo -e "${BOLD}${type}/${RESET}"
            divider
            ls -lht "${dir}"/cert_monitor_*.sql.gz 2>/dev/null \
                | awk '{printf "  %s  %s  %s\n", $5, $6" "$7" "$8, $9}' \
                | sed "s|${dir}/||g"
            echo
        fi
    done

    # Also list manual backups from the certmanage.sh root backups dir
    if ls "${BACKUP_ROOT}"/cert_monitor_*.sql.gz &>/dev/null 2>&1; then
        found=true
        echo -e "${BOLD}manual/ (from certmanage.sh db:backup)${RESET}"
        divider
        ls -lht "${BACKUP_ROOT}"/cert_monitor_*.sql.gz 2>/dev/null \
            | awk '{printf "  %s  %s  %s\n", $5, $6" "$7" "$8, $9}' \
            | sed "s|${BACKUP_ROOT}/||g"
        echo
    fi

    $found || info "No backup files found yet in ${BACKUP_ROOT}/"
}

# ─────────────────────────────────────────────────────────────────────────────
#  COMMAND: purge — manually run retention cleanup on all types
# ─────────────────────────────────────────────────────────────────────────────
cmd_purge() {
    header "Retention Cleanup"
    for type in hourly daily weekly; do
        local dir="${BACKUP_ROOT}/${type}"
        if [[ -d "$dir" ]]; then
            echo -e "${BOLD}${type}:${RESET}"
            _apply_retention "$type" "$dir"
            echo
        fi
    done
    ok "Purge complete"
}

# ─────────────────────────────────────────────────────────────────────────────
#  HELP
# ─────────────────────────────────────────────────────────────────────────────
cmd_help() {
    cat << EOF

${BOLD}${CYAN}CertMonitor Backup Scheduler${RESET}

${BOLD}USAGE${RESET}
  ./backup-scheduler.sh <command> [args]

${BOLD}COMMANDS${RESET}
  ${GREEN}install${RESET}              Install cron jobs for all enabled schedules
  ${GREEN}uninstall${RESET}            Remove all CertMonitor cron entries
  ${GREEN}status${RESET}               Show schedules, backup counts, disk usage, recent runs
  ${GREEN}run-backup${RESET} TYPE      Run a backup immediately — TYPE: hourly | daily | weekly
  ${GREEN}list${RESET}                 List all backup files grouped by type
  ${GREEN}purge${RESET}                Apply retention cleanup to all backup types

${BOLD}CURRENT SCHEDULE (edit this script to change)${RESET}
  Hourly   ${HOURLY_ENABLED:+enabled}$(  [[ "$HOURLY_ENABLED"  == "false" ]] && echo disabled)  — ${HOURLY_CRON}   — keep ${HOURLY_RETAIN}
  Daily    ${DAILY_ENABLED:+enabled}$(   [[ "$DAILY_ENABLED"   == "false" ]] && echo disabled)  — ${DAILY_CRON}    — keep ${DAILY_RETAIN}
  Weekly   ${WEEKLY_ENABLED:+enabled}$(  [[ "$WEEKLY_ENABLED"  == "false" ]] && echo disabled)  — ${WEEKLY_CRON}   — keep ${WEEKLY_RETAIN}

${BOLD}BACKUP LAYOUT${RESET}
  backups/hourly/    cert_monitor_YYYY-MM-DD_HH-MM-SS_hourly.sql.gz
  backups/daily/     cert_monitor_YYYY-MM-DD_HH-MM-SS_daily.sql.gz
  backups/weekly/    cert_monitor_YYYY-MM-DD_HH-MM-SS_weekly.sql.gz
  backups/logs/      one .log file per run — last 90 retained

${BOLD}EXAMPLES${RESET}
  ./backup-scheduler.sh install
  ./backup-scheduler.sh run-backup daily     # test before waiting for cron
  ./backup-scheduler.sh status
  ./backup-scheduler.sh list
  ./backup-scheduler.sh uninstall

${BOLD}RESTORE FROM A SCHEDULED BACKUP${RESET}
  Use certmanage.sh to restore any backup file:
  ./certmanage.sh db:restore backups/daily/cert_monitor_2025-01-15_02-00-00_daily.sql.gz

EOF
}

# ─────────────────────────────────────────────────────────────────────────────
#  ENTRYPOINT
# ─────────────────────────────────────────────────────────────────────────────
main() {
    local cmd="${1:-help}"
    case "$cmd" in
        install)        cmd_install ;;
        uninstall)      cmd_uninstall ;;
        status)         cmd_status ;;
        run-backup)     cmd_run_backup "${2:-daily}" ;;
        list)           cmd_list ;;
        purge)          cmd_purge ;;
        help|--help|-h) cmd_help ;;
        *)
            error "Unknown command: $cmd"
            cmd_help
            exit 1
            ;;
    esac
}

main "$@"
