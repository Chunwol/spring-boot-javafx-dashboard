#!/bin/bash
# Run Agent Only - Mac/Linux/Git Bash
# Usage: ./scripts/run-agent.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Agent Monitor Service"
echo "============================================================"
echo ""

if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Clean up stale processes (optional for agent, but good practice)
echo "[0/2] Cleaning up any stale database/cache processes..."
pkill -f "postgres" -9 2>/dev/null || true
pkill -f "redis-server" -9 2>/dev/null || true
sleep 1

# Check if JAR exists
if [ ! -f "agent/build/libs/agent-1.0.0.jar" ]; then
    echo "[1/2] Building Agent JAR first..."
    ./gradlew :agent:build -x test
fi

echo "[2/2] Starting Agent service..."
echo "Config dir: $SCRIPT_DIR"
echo "Press Ctrl+C to stop"
echo ""

java "-Dpcagent.config.dir=$SCRIPT_DIR" -jar agent/build/libs/agent-1.0.0.jar
