#!/bin/bash
# Build Frontend Only - Mac/Linux/Git Bash
# Usage: ./scripts/build-frontend.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Build Frontend Only (ShadowJAR)"
echo "============================================================"
echo ""

if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Clean up stale processes (frontend doesn't use DB but good for consistency)
echo "[0/1] Cleaning up stale processes..."
bash ./scripts/cleanup-runtime.sh --quiet

echo "[1/1] Building Frontend ShadowJAR..."
./gradlew :frontend:shadowJar -x test

echo ""
echo "[OK] Frontend JAR created:"
echo "  frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar"
echo ""
echo "Run with: java -jar frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar"
echo ""
