#!/bin/bash
# Build Agent Only - Mac/Linux/Git Bash
# Usage: ./scripts/build-agent.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Build Agent Module Only"
echo "============================================================"
echo ""

if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Clean up stale processes (agent doesn't use DB but good for consistency)
echo "[0/1] Cleaning up stale processes..."
bash ./scripts/cleanup-runtime.sh --quiet

echo "[1/1] Building Agent JAR..."
./gradlew :agent:build -x test

echo ""
echo "[OK] Agent JAR created:"
echo "  agent/build/libs/agent-1.0.0.jar"
echo ""
echo "Run with: java -jar agent/build/libs/agent-1.0.0.jar"
echo ""
