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

# Clean up stale processes
echo "[0/2] Cleaning up stale database/cache processes..."
pkill -f "postgres" -9 2>/dev/null || true
pkill -f "redis-server" -9 2>/dev/null || true
sleep 1

# Build
echo "[1/2] Building..."
./gradlew clean :backend:build -x test

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "[2/2] Build successful. Starting Backend..."
echo "Backend: http://localhost:8888"
echo "Press Ctrl+C to stop"
echo ""

./gradlew :backend:bootRun
