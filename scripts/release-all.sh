#!/usr/bin/env bash
# One-click Release Build (Mac/Linux)
# Usage: ./scripts/release-all.sh

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Ensure execute permissions (needed when cloned on Mac/Linux without git +x)
chmod +x ./gradlew scripts/*.sh 2>/dev/null || true

echo
echo "============================================================"
echo "=== One-click Release Build (Mac/Linux)"
echo "============================================================"
echo

./scripts/check-versions-and-fix.sh fix
./scripts/build-all.sh

echo
echo "[OK] Release build completed."
echo "Output: dist/"
echo