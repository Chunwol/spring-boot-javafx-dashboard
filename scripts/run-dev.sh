#!/bin/bash
# Development Mode - Integrated (Mac/Linux/Git Bash)
# Usage: ./scripts/run-dev.sh
# Builds project and starts both Backend and Frontend

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}=== Qsync Dashboard - Development Mode (Integrated)===${NC}"
echo -e "${GREEN}============================================================${NC}"
echo "Project Root: $PROJECT_ROOT"
echo ""

# Check if gradle wrapper exists
if [ ! -f "gradlew" ]; then
    echo -e "${RED}ERROR: gradlew not found${NC}"
    exit 1
fi

# Clean up stale processes before starting
echo -e "${YELLOW}[0/4] Cleaning up stale database/cache processes...${NC}"
pkill -f "postgres" -9 2>/dev/null || true
pkill -f "redis-server" -9 2>/dev/null || true
sleep 1

# Bootstrap PostgreSQL/Redis if available
if [ -f "scripts/backend-bootstrap.ps1" ]; then
    echo -e "${YELLOW}[1/4] Bootstrap PostgreSQL/Redis...${NC}"
    # For non-Windows: PostgreSQL/Redis should already be running via system package manager
    # This is optional and may not apply on Mac/Linux
fi

# Build the project
echo -e "${YELLOW}[2/4] Building project...${NC}"
./gradlew clean build -x test

# Check build status
if [ $? -ne 0 ]; then
    echo -e "${RED}Build failed!${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Build successful${NC}"
echo ""

# Function to gracefully shutdown on Ctrl+C
cleanup() {
    echo -e "\n${YELLOW}Shutting down services...${NC}"
    jobs -p | xargs -r kill 2>/dev/null || true
    sleep 1
    echo -e "${GREEN}Shutdown complete${NC}"
    exit 0
}

trap cleanup SIGINT SIGTERM

# Start backend
echo -e "${YELLOW}[3/4] Starting Backend (port 8888)...${NC}"
./gradlew :backend:bootRun > backend.log 2>&1 &
BACKEND_PID=$!
echo "Backend PID: $BACKEND_PID"

# Wait for backend to start
sleep 6

# Check if backend started successfully
if ! kill -0 $BACKEND_PID 2>/dev/null; then
    echo -e "${RED}Backend failed to start!${NC}"
    echo -e "${RED}Log:${NC}"
    cat backend.log
    exit 1
fi

echo -e "${GREEN}✓ Backend started${NC}"
echo ""

# Start frontend
echo -e "${YELLOW}[4/4] Starting Frontend...${NC}"
./gradlew :frontend:run > frontend.log 2>&1 &
FRONTEND_PID=$!
echo "Frontend PID: $FRONTEND_PID"

echo -e "${GREEN}✓ Frontend starting${NC}"
echo ""

# Display info
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}=== Services Running ===${NC}"
echo -e "${GREEN}============================================================${NC}"
echo "Backend:  http://localhost:8888"
echo "Frontend: JavaFX GUI window"
echo ""
echo "Logs:"
echo "  Backend:  $PROJECT_ROOT/backend.log"
echo "  Frontend: $PROJECT_ROOT/frontend.log"
echo ""
echo -e "${YELLOW}Press Ctrl+C to stop all services${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""

# Keep script running
wait
