#!/usr/bin/env bash
# Cleanup local runtime processes (PostgreSQL/Redis) and optional Docker stack.
# Usage:
#   ./scripts/cleanup-runtime.sh
#   ./scripts/cleanup-runtime.sh --docker
#   ./scripts/cleanup-runtime.sh --quiet

set -u

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

QUIET=0
INCLUDE_DOCKER=0
for arg in "$@"; do
  case "$arg" in
    --quiet) QUIET=1 ;;
    --docker) INCLUDE_DOCKER=1 ;;
  esac
done

log() {
  if [[ "$QUIET" -ne 1 ]]; then
    echo "[CLEANUP] $1"
  fi
}

warn() {
  if [[ "$QUIET" -ne 1 ]]; then
    echo "[WARN] $1"
  fi
}

read_env_value() {
  local key="$1"
  local env_file="backend/.env"
  if [[ ! -f "$env_file" ]]; then
    return
  fi
  awk -F'=' -v k="$key" '$1==k {print $2; exit}' "$env_file" | tr -d '\r'
}

stop_pid() {
  local pid="$1"
  local reason="$2"
  if [[ -z "$pid" || ! "$pid" =~ ^[0-9]+$ ]]; then
    return
  fi
  if kill -0 "$pid" 2>/dev/null; then
    log "Stopping PID $pid ($reason)"
    kill "$pid" 2>/dev/null || true
    sleep 0.2
    kill -9 "$pid" 2>/dev/null || true
  fi
}

stop_from_pid_file() {
  local pid_file="$1"
  local reason="$2"
  if [[ ! -f "$pid_file" ]]; then
    return
  fi
  local pid
  pid="$(head -n 1 "$pid_file" 2>/dev/null | tr -d '\r[:space:]')"
  stop_pid "$pid" "$reason"
  rm -f "$pid_file" 2>/dev/null || true
}

stop_listeners_by_port() {
  local port="$1"
  if [[ -z "$port" || ! "$port" =~ ^[0-9]+$ ]]; then
    return
  fi
  if command -v lsof >/dev/null 2>&1; then
    local pids
    pids="$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
    for pid in $pids; do
      stop_pid "$pid" "listening on port $port"
    done
    return
  fi
  if command -v fuser >/dev/null 2>&1; then
    local pids
    pids="$(fuser -n tcp "$port" 2>/dev/null | tr -s ' ' '\n')"
    for pid in $pids; do
      stop_pid "$pid" "listening on port $port"
    done
  fi
}

POSTGRES_RUNTIME_PORT="$(read_env_value POSTGRES_RUNTIME_PORT)"
REDIS_PORT="$(read_env_value REDIS_PORT)"
REDIS_RUNTIME_PORT="$(read_env_value REDIS_RUNTIME_PORT)"

[[ -z "$POSTGRES_RUNTIME_PORT" ]] && POSTGRES_RUNTIME_PORT="5433"
if [[ -z "$REDIS_PORT" ]]; then
  REDIS_PORT="$REDIS_RUNTIME_PORT"
fi
[[ -z "$REDIS_PORT" ]] && REDIS_PORT="6379"

log "Project root: $PROJECT_ROOT"
stop_from_pid_file ".runtime/redis/redis-runtime.pid" "redis runtime pid file"
stop_from_pid_file ".runtime/postgres/data/postmaster.pid" "postgres runtime pid file"

# Fallback for stale listeners.
stop_listeners_by_port "$POSTGRES_RUNTIME_PORT"
stop_listeners_by_port "$REDIS_PORT"

if [[ "$INCLUDE_DOCKER" -eq 1 && -f "docker-compose.yml" ]]; then
  if command -v docker >/dev/null 2>&1; then
    log "Stopping docker compose stack"
    docker compose down -v >/dev/null 2>&1 || docker-compose down -v >/dev/null 2>&1 || warn "docker compose cleanup skipped"
  fi
fi

log "Cleanup complete"
exit 0
