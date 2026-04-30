#!/bin/bash
# 배포 스크립트
# 전체 스택을 빌드하고 배포합니다

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 함수: 단계별 진행 표시
step() {
    echo -e "${BLUE}===================================================${NC}"
    echo -e "${YELLOW}$1${NC}"
    echo -e "${BLUE}===================================================${NC}"
}

# 함수: 성공 메시지
success() {
    echo -e "${GREEN}✓ $1${NC}"
}

# 함수: 실패 메시지
failure() {
    echo -e "${RED}✗ $1${NC}"
}

# 0. Clean up existing Docker containers and processes
step "STEP 0: Cleaning up existing deployment..."

# Kill any local postgres/redis processes
pkill -f "postgres" -9 2>/dev/null || true
pkill -f "redis-server" -9 2>/dev/null || true

# Stop docker-compose stack if running
if [ -f "docker-compose.yml" ]; then
    docker-compose down -v 2>/dev/null || true
fi

sleep 1
success "Cleanup complete"

# 1. Java 모듈 빌드
step "STEP 1: Building Java modules..."

if bash scripts/build-all.sh; then
    success "Java modules built"
else
    failure "Java module build failed"
    exit 1
fi

# 2. .env 파일 확인
step "STEP 2: Checking .env file..."

if [ ! -f ".env" ]; then
    failure ".env file not found"
    echo "Creating .env from .env.example..."
    if [ -f ".env.example" ]; then
        cp .env.example .env
        success ".env created from .env.example"
        echo -e "${YELLOW}⚠️  Please update .env with your configuration${NC}"
    else
        failure ".env.example not found"
        exit 1
    fi
else
    success ".env file exists"
fi

# 3. Docker 이미지 빌드
step "STEP 3: Building Docker images..."

if bash scripts/build-docker.sh; then
    success "Docker images built"
else
    failure "Docker image build failed"
    exit 1
fi

# 4. Docker Compose 시작
step "STEP 4: Starting Docker stack..."

if docker-compose up -d; then
    success "Docker stack started"
    
    # 5. 컨테이너 상태 확인
    step "STEP 5: Checking container status..."
    sleep 3
    
    echo ""
    docker-compose ps
    
    echo ""
    success "Deployment completed!"
    
    echo ""
    echo -e "${YELLOW}Important information:${NC}"
    echo "  • Backend API:  https://pelmon.kro.kr/api/..."
    echo "  • WebSocket:    wss://pelmon.kro.kr/ws/..."
    echo "  • Health Check: https://pelmon.kro.kr/health"
    echo "  • Frontend EXE: frontend/build/package/ClassroomDashboard/ClassroomDashboard.exe"
    echo ""
    echo -e "${YELLOW}Useful commands:${NC}"
    echo "  • View logs:    docker-compose logs -f [SERVICE_NAME]"
    echo "  • Stop stack:   docker-compose down"
    echo "  • Restart:      docker-compose restart [SERVICE_NAME]"
    echo "  • Remove all:   docker-compose down -v"
    echo ""
else
    failure "Docker Compose startup failed"
    exit 1
fi
