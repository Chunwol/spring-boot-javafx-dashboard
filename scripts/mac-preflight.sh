#!/usr/bin/env bash
# macOS/Linux preflight for this repository
# Usage: ./scripts/mac-preflight.sh

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo
printf '%s
' "============================================================"
printf '%s
' "=== Mac/Linux Preflight"
printf '%s
' "============================================================"
echo

# 1) Force executable bits (safe to re-run)
chmod +x ./gradlew scripts/*.sh 2>/dev/null || true
echo "[OK] Applied chmod +x to gradlew and scripts/*.sh"

# 2) Validate core tooling
if ! command -v java >/dev/null 2>&1; then
  echo "[FAIL] java not found in PATH"
  exit 1
fi

JAVA_LINE="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(echo "$JAVA_LINE" | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')"
if [[ -z "$JAVA_MAJOR" || ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]]; then
  echo "[FAIL] Could not parse Java version: $JAVA_LINE"
  exit 1
fi
if (( JAVA_MAJOR < 25 )); then
  echo "[FAIL] Java 25+ required. Current: $JAVA_LINE"
  exit 1
fi
echo "[OK] Java: $JAVA_LINE"

if ! ./gradlew --version >/dev/null 2>&1; then
  echo "[FAIL] gradlew failed to run"
  exit 1
fi
echo "[OK] gradlew is runnable"

# 3) Validate required artifacts layout conventions
if [[ -f "web-frontend/package-lock.json" ]]; then
  echo "[OK] Web frontend lockfile exists"
else
  echo "[FAIL] web-frontend/package-lock.json not found"
  exit 1
fi

echo
echo "Preflight passed."
echo "Next: ./scripts/release-all.sh"
echo
