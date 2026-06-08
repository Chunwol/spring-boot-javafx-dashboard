#!/bin/bash
# Build Frontend Only - Mac/Linux/Git Bash
# Usage: ./scripts/build-frontend.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Build Frontend Only (Platform JARs)"
echo "============================================================"
echo ""

if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Clean up stale processes (frontend doesn't use DB but good for consistency)
echo "[0/1] Cleaning up stale processes..."
bash ./scripts/cleanup-runtime.sh --quiet

echo "[1/1] Building Frontend platform JARs..."
./gradlew :frontend:publishPlatformJars -x test

echo ""
echo "[OK] Frontend platform JARs created:"
echo "  dist/frontend/frontend-win.jar"
echo "  dist/frontend/frontend-mac.jar"
echo "  dist/frontend/frontend-mac-aarch64.jar"
echo "  dist/frontend/frontend-linux.jar"
echo ""
echo "Run on this machine with: ./scripts/run-frontend.sh"
echo ""
