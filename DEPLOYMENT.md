# 배포 가이드 (Deployment Guide)

## 개요 (Overview)

이 프로젝트는 3개의 주요 모듈로 구성되어 있습니다:

| 모듈 | 목적 | 배포 방식 | 포트 |
|------|------|---------|------|
| **Backend** | REST API + WebSocket 서버 | Docker 컨테이너 | 8888 (내부, Nginx 경유) |
| **Agent** | PC 원격제어 에이전트 (각 학생 PC) | JAR 또는 Docker | - |
| **Frontend** | JavaFX 대시보드 GUI | JAR (멀티디바이스) | - |

---

## 빠른 시작 (Quick Start)

### 1. 자동 배포 (권장)

```bash
# 모든 모듈을 빌드하고 Docker Compose로 스택 시작
chmod +x scripts/deploy.sh
bash scripts/deploy.sh
```

이 스크립트는 다음을 순서대로 수행합니다:
1. 모든 Java 모듈 빌드 (`Common` → `Backend` → `Agent` → `Frontend`)
2. Docker 이미지 생성 (3개 모듈용)
3. Docker Compose 스택 시작

### 2. 수동 배포

#### 2.1 모든 Java 모듈 빌드

```bash
chmod +x scripts/build-all.sh
bash scripts/build-all.sh
```

생성되는 아티팩트:
- `backend/build/libs/backend-0.0.1-SNAPSHOT.jar`
- `agent/build/libs/agent-1.0.0.jar`
- `frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar`

#### 2.2 Docker 이미지 빌드

```bash
chmod +x scripts/build-docker.sh
bash scripts/build-docker.sh
```

생성되는 이미지:
- `dashboard-backend:latest`
- `dashboard-agent:latest`
- `dashboard-frontend:latest`

#### 2.3 Docker Compose 스택 시작

```bash
docker-compose up -d
```

#### 2.4 상태 확인

```bash
# 컨테이너 확인
docker-compose ps

# 로그 보기
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f backend
docker-compose logs -f agent
docker-compose logs -f frontend
```

---

## 모듈별 배포 상세 가이드

### Backend 모듈

#### 역할
- REST API 서버 (포트 8888)
- WebSocket 서버 (명령 전달)
- JWT 인증
- SQLite 데이터베이스

#### 배포 방식: Docker

```bash
# Dockerfile을 통한 자동 빌드 & 실행
docker build -t dashboard-backend:latest -f Dockerfile .
docker run -d --name backend \
  -e SERVER_PORT=8888 \
  -e JWT_SECRET=your_secret_key \
  -e DB_URL=jdbc:postgresql://postgres:5432/dashboard_db \
  -e DB_DRIVER=org.postgresql.Driver \
  -e DB_USERNAME=dashboard \
  -e DB_PASSWORD=dashboard \
  -e REDIS_HOST=redis \
  -e REDIS_PORT=6379 \
  -v ./backend/uploads:/app/uploads \
  -p 8888:8888 \
  dashboard-backend:latest
```

#### 환경 변수 (.env)

```env
# Backend 설정
SERVER_PORT=8888

# JWT & Security
JWT_SECRET=your_jwt_secret_key_here
AGENT_INSTALL_KEY=your_agent_install_key_here

# Database
DB_URL=jdbc:postgresql://postgres:5432/dashboard_db
DB_DRIVER=org.postgresql.Driver
DB_USERNAME=dashboard
DB_PASSWORD=dashboard
FLYWAY_LOCATIONS=classpath:db/pgmigration

# 기타
ALLOWED_ORIGINS=https://pelmon.kro.kr
WEBSOCKET_URL=wss://pelmon.kro.kr/ws
```

#### 데이터베이스

- **엔진**: PostgreSQL 16+
- **초기화**: Flyway (`backend/src/main/resources/db/migration/postgresql/V1__baseline.sql`)
- **캐시**: Redis 7+

#### API 엔드포인트

```
GET  /health                  - 헬스체크
POST /api/auth/login          - 로그인
POST /api/commands            - 명령 생성
GET  /api/commands/{id}       - 명령 조회
GET  /api/pcs                 - PC 목록
WS   /ws/stomp                - WebSocket STOMP
```

---

### Agent 모듈

#### 역할
- 학생 PC에 설치되는 경량 에이전트
- 주기적 하트비트 전송
- 백엔드 명령 수신 및 실행
- 하드웨어 정보 전송

#### 배포 방식 1: JAR 파일 (권장)

```bash
# JAR 빌드
./gradlew :agent:build

# JAR 실행
java -jar agent/build/libs/agent-1.0.0.jar
```

**실행 옵션:**

```bash
# 백엔드 서버 지정
java -Dbackend.url=https://pelmon.kro.kr \
     -jar agent-1.0.0.jar

# 로깅 레벨 변경
java -Dlogging.level.com.example.agent=DEBUG \
     -jar agent-1.0.0.jar
```

#### 배포 방식 2: Docker 컨테이너

```bash
# Dockerfile 빌드
docker build -t dashboard-agent:latest -f agent/Dockerfile .

# 실행
docker run -d --name agent \
  -e BACKEND_URL=https://pelmon.kro.kr \
  --network=dashboard_network \
  dashboard-agent:latest
```

#### 설정 파일

Agent는 환경 변수를 통해 설정됩니다:

```env
# Backend 서버
BACKEND_URL=https://pelmon.kro.kr
BACKEND_PORT=443

# 하트비트
HEARTBEAT_INTERVAL=10000  # 10초
HEARTBEAT_TIMEOUT=30000   # 30초

# 로깅
LOG_LEVEL=INFO
```

#### 설치 스크립트

학생 PC에 자동 설치하는 스크립트:

```bash
# Windows
scripts/install-agent-windows.bat agent-1.0.0.jar

# Linux/Mac
scripts/install-agent-linux.sh agent-1.0.0.jar
```

---

### Frontend 모듈

#### 역할
- JavaFX 기반 대시보드 GUI
- 다중 교실의 PC 상태 모니터링
- 명령 전송 (종료, 재부팅, PCON 등)
- 트래픽 모니터링

#### 배포 방식 1: JAR 파일 (멀티 디바이스)

```bash
# shadow JAR 빌드
./gradlew :frontend:shadowJar

# 실행 (Linux/Mac)
java -jar frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar

# 실행 (Windows)
java -jar frontend/build/libs/frontend-0.0.1-SNAPSHOT-all.jar

# 로그인 화면에서:
#   서버: pelmon.kro.kr
#   포트: 443
#   아이디: admin
#   비밀번호: (설정된 비밀번호)
```

#### 배포 방식 2: Docker 컨테이너

```bash
# Dockerfile 빌드
docker build -t dashboard-frontend:latest -f frontend/Dockerfile .

# 실행 (X11 디스플레이 공유)
docker run -it --rm \
  -e DISPLAY=$DISPLAY \
  -v /tmp/.X11-unix:/tmp/.X11-unix \
  --network=dashboard_network \
  dashboard-frontend:latest
```

#### 설정 파일

`frontend/src/main/resources/application.properties`:

```properties
# 백엔드 서버
api.base.url=https://pelmon.kro.kr
api.port=443

# WebSocket
websocket.url=wss://pelmon.kro.kr/ws

# 클라이언트 설정
app.name=Dashboard
app.version=0.0.1
theme=LIGHT
```

---

## 네트워킹 구조

```
┌─────────────────────────────────────────┐
│  Nginx 리버스 프록시                     │
│  - HTTPS (443)                          │
│  - /api/* → Backend:8888                │
│  - /ws/* → Backend:8888 (WebSocket)    │
│  - /.well-known/* → Let's Encrypt      │
└──────────────┬──────────────────────────┘
               │
               │ (내부 통신, 8888)
               │
       ┌───────▼──────────┐
       │   Backend        │
       │   Spring Boot    │
       │   Port: 8888     │
       │   - REST API     │
       │   - WebSocket    │
       │   - JWT Auth     │
       └───────┬──────────┘
               │ (하트비트 수신)
               │
       ┌───────▼──────────┐     ┌──────────────┐
       │   Agent          │     │  Frontend    │
       │   각 PC           │     │  JavaFX GUI  │
       │   (학생)         │     │  (관리자)    │
       └──────────────────┘     └──────────────┘
```

---

## Docker Compose 스택

### 구조

`docker-compose.yml` 파일이 다음 서비스를 조율합니다:

```yaml
services:
  nginx:
    - HTTPS 리버스 프록시
    - 포트: 443/80
    - 인증서: /etc/letsencrypt/
    
  app (backend):
    - Spring Boot 서버
    - 포트: 8888 (내부)
    - 볼륨: ./backend/db, ./uploads
    
  agent:
    - 에이전트 (선택사항)
    - 네트워크: dashboard_network
    
  frontend:
    - JavaFX GUI (선택사항)
    - X11 디스플레이 공유
```

### 상태 확인

```bash
# 모든 서비스 상태
docker-compose ps

# 특정 서비스 재시작
docker-compose restart backend

# 로그 보기 (실시간)
docker-compose logs -f backend

# 볼륨 확인
docker volume ls | grep dashboard

# 네트워크 확인
docker network ls | grep dashboard
```

---

## SSL/TLS 인증서 (ZeroSSL)

### 초기 설정

```bash
# 1. .env 파일의 ZeroSSL EAB 자격증명 확인
grep -E "ZEROSSL|EAB" .env

# 2. 인증서 초기 발급 (자동)
docker exec nginx /opt/init-ssl.sh

# 3. Nginx 재시작
docker-compose restart nginx
```

### 인증서 갱신

```bash
# 수동 갱신
docker exec nginx /opt/renew-ssl.sh

# 자동 갱신 (크론 작업, 매월 1일)
0 0 1 * * docker exec nginx /opt/renew-ssl.sh
```

---

## 문제 해결 (Troubleshooting)

### Backend가 시작되지 않음

```bash
# 로그 확인
docker-compose logs backend

# 포트 충돌 확인
lsof -i :8888

# 데이터베이스 초기화
docker exec backend mysql -u root -p < scripts/init-sql/init-db.sql
```

### Agent가 백엔드에 연결되지 않음

```bash
# 백엔드 헬스체크
curl https://pelmon.kro.kr/health

# 네트워크 확인
docker network inspect dashboard_network

# Agent 로그
docker-compose logs agent
```

### Frontend GUI가 표시되지 않음

```bash
# X11 디스플레이 확인
echo $DISPLAY

# Docker에 디스플레이 공유 확인
docker inspect frontend | grep DISPLAY

# JavaFX 로그
docker-compose logs frontend | grep -i javafx
```

### 인증서 갱신 실패

```bash
# 인증서 상태
docker exec nginx ls -la /etc/letsencrypt/live/

# 로그 확인
docker logs nginx

# ZeroSSL 자격증명 확인
docker exec app cat /app/.env | grep EAB
```

---

## 성능 최적화

### Backend

```bash
# JVM 메모리 설정 (docker-compose.yml)
environment:
  JAVA_OPTS: "-Xmx1024m -Xms512m"
```

### 데이터베이스 인덱스

```sql
-- /app/db/mydatabase.db
CREATE INDEX idx_pc_group ON pc(group_id);
CREATE INDEX idx_command_status ON command(status);
CREATE INDEX idx_heartbeat_pc ON heartbeat(pc_id);
```

---

## 보안 체크리스트

- [ ] JWT 시크릿 키 변경 (.env의 JWT_SECRET)
- [ ] Agent 설치 키 변경 (.env의 AGENT_INSTALL_KEY)
- [ ] HTTPS 인증서 자동 갱신 확인
- [ ] 백엔드 포트 8888이 외부에 노출되지 않음
- [ ] Nginx X-Forwarded-* 헤더 활성화 확인
- [x] 데이터베이스 백업 정책 수립 (일일 자동 백업 + 빈 DB 자동 복구)
- [ ] 방화벽 규칙: 80, 443 외부 접근 허용, 8888 차단

---

## 유지보수

### 백업

```bash
# Windows 로컬 실행(권장)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/db-backup.ps1

# Docker 환경에서 수동 PostgreSQL 백업 예시
docker exec -e PGPASSWORD=$DB_PASSWORD postgres pg_dump -U $DB_USERNAME -d $POSTGRES_DB -F c > db_$(date +%Y%m%d).dump

# 업로드 파일 백업 (Docker)
docker exec backend cp -r /app/uploads /backup/uploads_$(date +%Y%m%d)
```

### 업그레이드

```bash
# 1. 이미지 재빌드
bash scripts/build-docker.sh

# 2. 컨테이너 재시작
docker-compose down
docker-compose up -d
```

### 모니터링

```bash
# 실시간 로그 모니터링
docker-compose logs -f

# 컨테이너 리소스 사용량
docker stats
```

---

## 주요 파일 위치

| 파일 | 설명 |
|------|------|
| `Dockerfile` | Backend 컨테이너 정의 |
| `agent/Dockerfile` | Agent 컨테이너 정의 |
| `frontend/Dockerfile` | Frontend 컨테이너 정의 |
| `docker-compose.yml` | 전체 스택 정의 |
| `.env` | 환경 변수 (보안) |
| `.env.example` | 환경 변수 템플릿 |
| `scripts/build-all.sh` | Java 모듈 빌드 |
| `scripts/build-docker.sh` | Docker 이미지 빌드 |
| `scripts/deploy.sh` | 전체 배포 스크립트 |
| `nginx/conf.d/default.conf` | HTTP 리다이렉트 설정 |
| `nginx/conf.d/ssl.conf.disabled` | HTTPS 설정 (활성화 필요) |
| `scripts/init-sql/init-db.sql` | 데이터베이스 초기화 스크립트 |

---

## 다음 단계

1. ✅ 배포 파일 생성 (이 문서)
2. ⏳ 각 모듈 테스트
   - Backend: `curl https://pelmon.kro.kr/health`
   - Agent: 하트비트 로그 확인
   - Frontend: GUI 실행 및 로그인 테스트
3. ⏳ 프로덕션 배포
   - 보안 체크리스트 확인
   - 로드 밸런싱 설정 (필요시)
   - 모니터링 대시보드 구성

---

**마지막 업데이트**: 2026-03-18
**담당자**: DevOps Team

