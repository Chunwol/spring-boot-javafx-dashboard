#!/bin/bash
# Build All Modules - Mac/Linux/Git Bash
# Usage: ./scripts/build-all.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}=== Build All Modules (Mac/Linux/Git Bash)===${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""

# 1. Common module
echo -e "${YELLOW}[1/4] Building Common Module...${NC}"
if ./gradlew :common:build -x test; then
    echo -e "${GREEN}✓ Common module built successfully${NC}"
else
    echo -e "${RED}✗ Common module build failed${NC}"
    exit 1
fi
echo ""

# 2. Backend module
echo -e "${YELLOW}[2/4] Building Backend Module...${NC}"
if ./gradlew :backend:bootJar -x test; then
    echo -e "${GREEN}✓ Backend JAR: backend/build/libs/backend-0.0.1-SNAPSHOT.jar${NC}"
else
    echo -e "${RED}✗ Backend build failed${NC}"
    exit 1
fi
echo ""

# 3. Agent module
echo -e "${YELLOW}[3/4] Building Agent Module...${NC}"
if ./gradlew :agent:build -x test; then
    echo -e "${GREEN}✓ Agent JAR: agent/build/libs/agent-1.0.0.jar${NC}"
else
    echo -e "${RED}✗ Agent build failed${NC}"
    exit 1
fi
echo ""

# 4. Frontend module
echo -e "${YELLOW}[4/4] Building Frontend Module...${NC}"
if ./gradlew :frontend:shadowJar -x test; then
    echo -e "${GREEN}✓ Frontend JAR: frontend/build/libs/dashboard-0.0.1-all.jar${NC}"
else
    echo -e "${RED}✗ Frontend build failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}All modules built successfully!${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""
echo "Generated artifacts:"
echo "  • Backend:  backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
echo "  • Agent:    agent/build/libs/agent-1.0.0.jar"
echo "  • Frontend: frontend/build/libs/dashboard-0.0.1-all.jar"
echo ""
echo "Next steps:"
echo "  1. Run './scripts/run-backend-only.sh' to test backend"
echo "  2. Run './scripts/run-dev.sh' for integrated dev mode"
echo "  3. Run './scripts/build-docker.sh' to build Docker images"
echo ""
