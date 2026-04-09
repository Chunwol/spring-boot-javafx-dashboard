#!/bin/bash
# Run Frontend (Packaged JAR) - Mac/Linux/Git Bash
# Usage: ./scripts/run-frontend.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Frontend (Packaged JAR)"
echo "============================================================"
echo ""

# Check if JAR exists
if [ ! -f "frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar" ]; then
    echo "Building Frontend JAR first..."
    ./gradlew :frontend:shadowJar -x test
fi

echo "Starting Frontend GUI..."
echo "Press Ctrl+C to stop"
echo ""

java -jar frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar
