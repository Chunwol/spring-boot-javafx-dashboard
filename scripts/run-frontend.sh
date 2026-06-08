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

detect_platform_jar() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Darwin)
            case "$arch" in
                arm64|aarch64) echo "frontend-mac-aarch64.jar" ;;
                *) echo "frontend-mac.jar" ;;
            esac
            ;;
        Linux)
            echo "frontend-linux.jar"
            ;;
        *)
            echo "frontend-linux.jar"
            ;;
    esac
}

JAR_NAME="$(detect_platform_jar)"
JAR_PATH="dist/frontend/${JAR_NAME}"

if [ ! -f "$JAR_PATH" ]; then
    echo "Building current-platform Frontend JAR first..."
    ./gradlew :frontend:publishCurrentPlatformJar -x test
fi

echo "Starting Frontend GUI..."
echo "Press Ctrl+C to stop"
echo ""

java -jar "$JAR_PATH"
