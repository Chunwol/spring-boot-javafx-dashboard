#!/bin/bash
# Build All Modules - Mac/Linux/Git Bash
# Usage: ./scripts/build-all.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Ensure execute permissions (needed when cloned on Mac/Linux without git +x)
chmod +x ./gradlew scripts/*.sh 2>/dev/null || true

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

# 0. Clean up stale processes before build
echo -e "${YELLOW}[0/4] Cleaning up stale database/cache processes...${NC}"
bash ./scripts/cleanup-runtime.sh --quiet
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
echo -e "${YELLOW}[2/4] Building Backend + Web Dashboard...${NC}"
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

echo ""
echo -e "${YELLOW}[4/4] Collecting release artifacts to dist/${NC}"
mkdir -p dist/backend dist/agent
cp -f backend/build/libs/backend-0.0.1-SNAPSHOT.jar dist/backend/backend.jar
cp -f agent/build/libs/agent-1.0.0.jar dist/agent/agent.jar

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}All modules built successfully!${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""
echo "Generated artifacts:"
echo "  • Backend + Web: dist/backend/backend.jar"
echo "  • Agent:         dist/agent/agent.jar"
echo ""
echo "Next steps:"
echo "  1. Deploy backend.jar on the server"
echo "  2. Open the web dashboard at the backend URL"
echo "  3. Distribute agent.jar only to managed PCs"
echo ""
