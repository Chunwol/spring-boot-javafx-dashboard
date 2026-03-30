# 빌드 및 배포 스크립트
# 모든 모듈을 빌드합니다
set -e

echo "================================"
echo "Building Dashboard Multi-Module"
echo "================================"

# 컬러 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 공통 모듈 빌드
echo -e "${YELLOW}[1/4] Building Common Module...${NC}"
if ./gradlew :common:build -x test; then
    echo -e "${GREEN}✓ Common module built successfully${NC}"
else
    echo -e "${RED}✗ Common module build failed${NC}"
    exit 1
fi

# 2. Backend 모듈 빌드
echo -e "${YELLOW}[2/4] Building Backend Module...${NC}"
if ./gradlew :backend:bootJar -x test; then
    echo -e "${GREEN}✓ Backend JAR created: backend/build/libs/backend-0.0.1-SNAPSHOT.jar${NC}"
else
    echo -e "${RED}✗ Backend build failed${NC}"
    exit 1
fi

# 3. Agent 모듈 빌드
echo -e "${YELLOW}[3/4] Building Agent Module...${NC}"
if ./gradlew :agent:build -x test; then
    echo -e "${GREEN}✓ Agent JAR created: agent/build/libs/agent-1.0.0.jar${NC}"
else
    echo -e "${RED}✗ Agent build failed${NC}"
    exit 1
fi

# 4. Frontend 모듈 빌드
echo -e "${YELLOW}[4/4] Building Frontend Module...${NC}"
if ./gradlew :frontend:shadowJar -x test; then
    echo -e "${GREEN}✓ Frontend shadowJAR created: frontend/build/libs/dashboard-0.0.1-all.jar${NC}"
else
    echo -e "${RED}✗ Frontend build failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}================================"
echo "All modules built successfully!"
echo "================================${NC}"
echo ""
echo "Generated artifacts:"
echo "  • Backend:  backend/build/libs/backend-0.0.1-SNAPSHOT.jar"
echo "  • Agent:    agent/build/libs/agent-1.0.0.jar"
echo "  • Frontend: frontend/build/libs/dashboard-0.0.1-all.jar"
echo ""
echo "Next steps:"
echo "  1. Run 'docker-compose up' to start the stack"
echo "  2. Or use scripts/build-docker.sh to build Docker images"
