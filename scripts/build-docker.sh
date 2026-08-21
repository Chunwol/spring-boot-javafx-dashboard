#!/bin/bash
# Docker 이미지 빌드 스크립트
# 각 모듈별 Docker 이미지를 생성합니다

set -e

echo "================================"
echo "Building Docker Images"
echo "================================"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Clean up: Stop any running containers
echo -e "${YELLOW}[0/3] Cleaning up existing containers and processes...${NC}"
bash ./scripts/cleanup-runtime.sh --quiet --docker

# 1. Backend Docker 이미지
echo -e "${YELLOW}[1/3] Building Backend Docker image...${NC}"
if docker build -t dashboard-backend:latest -f Dockerfile .; then
    echo -e "${GREEN}✓ Backend image built: dashboard-backend:latest${NC}"
else
    echo -e "${RED}✗ Backend Docker build failed${NC}"
    exit 1
fi

# 2. Agent Docker 이미지
echo -e "${YELLOW}[2/3] Building Agent Docker image...${NC}"
if docker build -t dashboard-agent:latest -f agent/Dockerfile .; then
    echo -e "${GREEN}✓ Agent image built: dashboard-agent:latest${NC}"
else
    echo -e "${RED}✗ Agent Docker build failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}================================"
echo "[3/3] All Docker images built successfully!"
echo "================================${NC}"
echo ""
echo "Docker images created:"
echo "  • dashboard-backend:latest"
echo "  • dashboard-agent:latest"
echo "  • Web dashboard is included in dashboard-backend:latest"
echo ""
echo "Next steps:"
echo "  1. Run 'docker-compose up -d' to start the stack"
echo "  2. Check images: docker images | grep dashboard"
echo "  3. Check containers: docker ps -a"
echo "  4. View logs: docker-compose logs -f"
