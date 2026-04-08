#!/bin/bash
# Run Agent Only - Mac/Linux/Git Bash
# Usage: ./scripts/run-agent.sh

set -e

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

# Check if JAR exists
if [ ! -f "agent/build/libs/agent-1.0.0.jar" ]; then
    echo "Building Agent JAR first..."
    ./gradlew :agent:build -x test
fi

echo "Starting Agent service..."
echo "Press Ctrl+C to stop"
echo ""

java -jar agent/build/libs/agent-1.0.0.jar
