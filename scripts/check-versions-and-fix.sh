#!/usr/bin/env bash
# Check runtime/tool versions and apply safe local fixes.
#
# ====================================================
# 맞춰야 하는 버전 (Required versions)
#   Java  : 25 LTS  (openjdk 25.0.x)
#   Gradle: 8.14.4  (gradlew wrapper 포함, 별도 설치 불필요)
#   Node  : 24.x 이상 (web-frontend)
#   Spring Boot: 4.x (backend/build.gradle)
# ====================================================
#
# Usage:
#   ./scripts/check-versions-and-fix.sh           # check only
#   ./scripts/check-versions-and-fix.sh check
#   ./scripts/check-versions-and-fix.sh fix       # check + safe fixes

set -euo pipefail

MODE="${1:-check}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FAIL_COUNT=0
WARN_COUNT=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; WARN_COUNT=$((WARN_COUNT + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

header() {
  echo
  echo "============================================================"
  echo "Version Check & Safe Fix"
  echo "Mode: $MODE"
  echo "Root: $PROJECT_ROOT"
  echo "============================================================"
}

require_mode() {
  if [[ "$MODE" != "check" && "$MODE" != "fix" ]]; then
    fail "Unknown mode: $MODE (allowed: check|fix)"
  fi
}

check_java() {
  if ! command -v java >/dev/null 2>&1; then
    fail "java not found in PATH"
    return
  fi

  local ver_line
  ver_line="$(java -version 2>&1 | head -n 1)"
  local major
  major="$(echo "$ver_line" | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')"

  if [[ -z "$major" || ! "$major" =~ ^[0-9]+$ ]]; then
    warn "Could not parse java version: $ver_line"
    return
  fi

  if (( major < 25 )); then
    fail "Java 25+ required. Current: $ver_line"
  else
    pass "Java OK: $ver_line"
  fi
}

check_gradle_wrapper() {
  if [[ ! -f "./gradlew" ]]; then
    fail "gradlew not found"
    return
  fi

  if ./gradlew --version >/dev/null 2>&1; then
    pass "Gradle wrapper runnable"
  else
    fail "Gradle wrapper failed to run"
  fi
}

check_optional_tools() {
  if command -v pwsh >/dev/null 2>&1; then
    pass "pwsh found (optional for cross-platform scripts)"
  else
    warn "pwsh not found (optional)"
  fi
}

check_agent_config_file() {
  if [[ -f "scripts/config.json" ]]; then
    pass "scripts/config.json exists"
  else
    warn "scripts/config.json missing"
  fi
}

fix_permissions() {
  if [[ -f "./gradlew" ]]; then
    chmod +x ./gradlew || true
    pass "Applied chmod +x to gradlew"
  fi

  if compgen -G "scripts/*.sh" > /dev/null; then
    chmod +x scripts/*.sh || true
    pass "Applied chmod +x to scripts/*.sh"
  fi
}

fix_config_json() {
  if [[ -f "scripts/config.json" ]]; then
    pass "scripts/config.json already present"
    return
  fi

  if [[ -f "config.json" ]]; then
    cp "config.json" "scripts/config.json"
    pass "Copied config.json -> scripts/config.json"
    return
  fi

  cat > "scripts/config.json" <<'JSON'
{
  "agentToken": "",
  "githubOwner": "",
  "githubRepo": "",
  "serverUrl": "http://127.0.0.1:8888",
  "heartbeatIntervalSec": 120,
  "installKey": "INST-CHANGE-ME-BEFORE-DEPLOY",
  "jitterSec": 30
}
JSON
  pass "Created default scripts/config.json"
}

run_fix() {
  echo
  echo "Applying safe fixes..."
  fix_permissions
  fix_config_json
}

summary() {
  echo
  echo "============================================================"
  echo "Summary: FAIL=$FAIL_COUNT WARN=$WARN_COUNT"
  echo "============================================================"

  if (( FAIL_COUNT > 0 )); then
    echo "Result: FAILED"
    exit 1
  fi

  echo "Result: OK"
}

header
require_mode
check_java
check_gradle_wrapper
check_optional_tools
check_agent_config_file

if [[ "$MODE" == "fix" ]]; then
  run_fix
fi

summary
