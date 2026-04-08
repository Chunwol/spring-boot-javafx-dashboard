#!/bin/bash
# Frontend Only - Mac/Linux/Git Bash
# Usage: ./scripts/run-frontend-only.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT/.."

echo ""
echo "============================================================"
echo "=== Frontend Only (Development Mode)"
echo "============================================================"
echo "Make sure Backend is running on http://localhost:8888"
echo ""

# Check gradle wrapper
if [ ! -f "gradlew" ]; then
    echo "ERROR: gradlew not found"
    exit 1
fi

# Read env file if exists
API_HOST="localhost"
API_PORT="8888"

if [ -f "frontend/.env" ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            API_SERVER_HOST) API_HOST="$value" ;;
            API_SERVER_PORT) API_PORT="$value" ;;
        esac
    done < "frontend/.env"
fi

echo "Connecting to: http://$API_HOST:$API_PORT"
echo "Press Ctrl+C to stop"
echo ""

./gradlew :frontend:run
