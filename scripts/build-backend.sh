#!/bin/bash
# Build Backend Only - Mac/Linux/Git Bash
# Usage: ./scripts/build-backend.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo ""
echo "============================================================"
echo "=== Build Backend Only"
echo "============================================================"
echo ""

if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

echo "Building Backend JAR..."
./gradlew :backend:bootJar -x test

echo ""
echo "[OK] Backend JAR created:"
echo "  backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
echo ""
