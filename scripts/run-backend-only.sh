#!/bin/bash
# Backend Only - Mac/Linux/Git Bash
# Usage: ./scripts/run-backend-only.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT/.."

echo ""
echo "============================================================"
echo "=== Backend Only (Port 8888)"
echo "============================================================"
echo ""

# Check gradle wrapper
if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Build
echo "Building..."
./gradlew clean :backend:build -x test

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "[OK] Build successful. Starting Backend..."
echo "Backend: http://localhost:8888"
echo "Press Ctrl+C to stop"
echo ""

./gradlew :backend:bootRun
