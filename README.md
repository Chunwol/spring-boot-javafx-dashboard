# Spring Boot JavaFX Dashboard

이 프로젝트는 Spring Boot를 사용한 백엔드 REST API와 JavaFX를 사용한 프론트엔드 대시보드를 포함합니다. SQLite 데이터베이스를 사용하여 데이터를 저장하며, 이메일 알림 시스템과 스케줄러 기능을 제공합니다.

## 프로젝트 구조

```
spring-boot-javafx-dashboard
├── backend/               # 백엔드 모듈 (Spring Boot 3.x)
│   └── src/main/java/com/example/backend/
│       ├── Application.java
│       ├── controller/    # REST API 컨트롤러
│       │   ├── AdminController.java
│       │   ├── AuthController.java
│       │   ├── CommandController.java
│       │   ├── DashboardApiController.java
│       │   ├── GroupController.java
│       │   ├── HeartbeatController.java
│       │   ├── ReportController.java
│       │   ├── ScriptController.java
│       │   └── ...
│       ├── service/       # 비즈니스 로직
│       ├── repository/    # JPA 레포지토리
│       ├── model/         # 엔티티 (PcMaster, ClassroomGroup 등)
│       └── config/        # Security, JWT, WebSocket 설정
├── frontend/              # 프론트엔드 모듈 (JavaFX 25)
│   └── src/main/java/com/example/frontend/
│       ├── DashboardApp.java
│       ├── controller/    # MVVM 뷰 컨트롤러
│       ├── viewmodel/     # DashboardViewModel
│       └── service/       # DashboardUIService, ApiClient
├── common/                # 공통 DTO 모듈
│   └── src/main/java/com/example/dto/
├── agent/                 # PC 에이전트 모듈 (Windows 서비스)
│   └── src/main/java/com/example/agent/
│       ├── AgentMain.java
│       ├── HeartbeatSender.java
│       └── CommandExecutor.java
├── scripts/               # 빌드/배포/테스트 스크립트
├── nginx/                 # Nginx 리버스 프록시 설정
├── docs/                  # 설계 문서
├── docker-compose.yml     # Docker 배포 구성
├── settings.gradle        # Gradle 멀티모듈 설정
└── README.md
```

## 기능

- **백엔드**: Spring Boot 3.x REST API, JWT 인증, WebSocket/STOMP 실시간 통신, SQLite + Flyway 마이그레이션
- **프론트엔드**: JavaFX 25 Grafana 스타일 대시보드 (AtlantaFX 테마, MVVM 패턴)
- **에이전트**: Windows PC 모니터링 서비스 (OSHI 하드웨어 정보, 하트비트, 원격 명령 실행)
- **강의실 관리**: IP 대역 기반 자동 매핑, 교사PC/학생PC 자동 분류
- **원격 제어**: 종료/재부팅, WOL, 명령 실행, PS1 스크립트 배포
- **리포트**: iTextPDF 기반 트래픽·가동률 보고서, 이메일 발송
- **배포**: Docker Compose, Nginx 리버스 프록시, ZeroSSL 자동 인증서

## 설정

### 빠른 시작 - 개발 환경 (Windows/Mac/Linux)

#### 1️⃣ 환경 설정 파일 생성
```bash
# backend/.env 및 frontend/.env 생성 (자세한 설정은 ENV_GUIDE.md 참고)
```

#### 2️⃣ 통합 개발모드 실행 (권장)

**Windows:**
```batch
scripts\run-dev.bat

REM 빠른 통합 실행(부트스트랩 + 백엔드 + 프론트엔드 분리 실행)
scripts\run-dashboard-stack.bat

REM 재부팅 후 자동 실행(로그온 시 자동 시작)
scripts\enable-dashboard-autostart.bat

REM 자동 실행 해제
scripts\disable-dashboard-autostart.bat
```

**Mac/Linux/Git Bash:**
```bash
scripts/mac-preflight.sh

scripts/run-dev.sh
```

이 명령은 자동으로:
- 로컬 PostgreSQL/Redis 관련 스테일 프로세스 정리
- 전체 프로젝트를 빌드
- 백엔드를 포트 8888에서 시작
- 프론트엔드를 시작

참고: Mac/Linux에서는 PostgreSQL/Redis 서비스가 별도로 설치되어 있거나 Docker 등으로 준비되어 있어야 합니다.

#### 3️⃣ 개별 실행 (필요시)

**Windows:**
```batch
REM 백엔드만
scripts\run-backend-only.bat

REM 다시 터미널 열어서 프론트엔드
scripts\run-frontend-dev.bat
```

**Mac/Linux/Git Bash:**
```bash
# 백엔드만
scripts/run-backend-only.sh

# 다시 터미널 열어서 프론트엔드
scripts/run-frontend-only.sh
```

### ⚠️ Rate Limit 이슈 해결

스타트 시 "IP 대역 목록 조회 실패 (Rate limit exceeded)" 에러가 나는 경우:

**backend/.env** 파일에 다음을 추가:
```properties
# 개발 환경용 넉넉한 설정
RATELIMIT_REQUESTS_PER_MINUTE=10000
RATELIMIT_REQUESTS_PER_HOUR=300000
```

참고: `/ip-ranges` 엔드포인트는 개발모드에서 자동 제외되므로 영향 없습니다.

### 전체 설정 가이드
모든 환경 설정값은 `.env` 파일에서 관리됩니다.

- **`.env` 파일 생성**: `.env.example` 파일을 복사하여 `.env` 파일 생성
- **`.env` 파일 수정**: 데이터베이스, 이메일, 서버 포트 등 설정
- **자세한 가이드**: [ENV_GUIDE.md](ENV_GUIDE.md) 참고

### 주요 설정값
```env
# 데이터베이스
DB_URL=jdbc:postgresql://localhost:5432/dashboard_db
DB_DRIVER=org.postgresql.Driver
DB_USERNAME=dashboard
DB_PASSWORD=dashboard
POSTGRES_SUPERUSER=postgres
POSTGRES_SUPER_PASSWORD=dashboard
DB_BACKUP_DIR=./backups/postgres
DB_BACKUP_KEEP_DAYS=14
DB_BACKUP_TIME=01:00
DB_BACKUP_ENABLED=true
DB_BACKUP_CRON=0 0 1 * * *
DB_BACKUP_ZONE=Asia/Seoul
DB_BACKUP_TIMEOUT_MINUTES=30
DB_BACKUP_LOCK_KEY=94503177
FLYWAY_LOCATIONS=classpath:db/pgmigration

# 서버
SERVER_PORT=8888

# 이메일
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=your-email@gmail.com

# JWT (프로덕션에서는 강력한 키 설정)
JWT_SECRET=your-secret-key
```
- `DB_BACKUP_TIME=HH:mm`만 바꿔도 자동으로 크론으로 변환됩니다.
- `DB_BACKUP_TIME`이 유효하면 `DB_BACKUP_CRON`보다 우선 적용됩니다.
- `DB_BACKUP_TIME` 형식이 잘못되면 `DB_BACKUP_CRON`으로 fallback 됩니다.

### 주의사항
- `.env` 파일은 Git에 커밋되지 않습니다 (`.gitignore`에 등록)
- 프로덕션 환경에서는 반드시 `JWT_SECRET` 값 변경
- 민감한 정보(API 키, 비밀번호)는 `.env` 파일에만 저장

## 실행 방법

### 최신 방식 (권장) - scripts 폴더의 스크립트 사용

#### Mac/Linux/Git Bash:
```bash
cd /path/to/spring-boot-javafx-dashboard

# 통합 개발모드 (권장)
./scripts/run-dev.sh

# 또는 개별 실행
./scripts/run-backend-only.sh      # 터미널 1
./scripts/run-frontend-only.sh     # 터미널 2
```

#### Windows:
```batch
cd path\to\spring-boot-javafx-dashboard

REM 통합 개발모드 (권장) - 별도 창에서 자동 실행
scripts\run-dev.bat

REM 또는 개별 실행
scripts\run-backend-only.bat        REM 터미널 1 (자동 설치/복구/백업 등록 포함)
scripts\run-frontend-dev.bat        REM 터미널 2
```

### 수동 실행 (서드파티 스크립트)

1. 백엔드 서버를 실행합니다 (터미널 1):
   ```bash
   # Mac/Linux/Git Bash
   ./gradlew :backend:bootRun

   # Windows
   gradlew.bat :backend:bootRun
   ```

2. 프론트엔드 애플리케이션을 실행합니다 (터미널 2):
   ```bash
   # Mac/Linux/Git Bash
   ./gradlew :frontend:run

   # Windows
   gradlew.bat :frontend:run
   ```

### 접근 정보

- **Backend API**: http://localhost:8888
- **Frontend**: JavaFX GUI 클라이언트 (독립 창)

### macOS에서 프론트엔드 창이 전체화면처럼 보이는 경우

**증상**: 프론트엔드 JavaFX 창이 화면 전체를 차지하거나 비정상 크기로 시작

**원인**:
- macOS Retina 디스플레이의 DPI 스케일링으로 인한 화면 크기 계산 오류
- Stage 설정에서 전체화면 모드 미비활성화

**해결방법** (이미 적용됨):
- 화면 크기 계산을 화면의 80% 이하로 제한 (보수적 크기 설정)
- `primaryStage.setFullScreen(false)`로 명시적으로 전체화면 비활성화
- `primaryStage.centerOnScreen()`으로 화면 중앙에 배치

만약 문제가 계속 발생하면, 다음 중 하나를 시도해 보세요:
1. 시스템 환경설정 > 디스플레이에서 "더 넓은 화면"/"기본" 설정 확인
2. 프론트엔드 재빌드: `./gradlew :frontend:clean :frontend:run`
3. JVM 옵션 추가: 위 명령 대신 아래로 실행
   ```bash
   ./gradlew :frontend:run -Pargs="-Dapple.awt.fullscreenutilization=false"
   ```

## 운영 스모크 테스트

백엔드가 실행 중인 상태에서 아래 스크립트로 핵심 운영 시나리오를 자동 점검할 수 있습니다.

Windows PowerShell:
```powershell
./scripts/smoke-test-dashboard.ps1
```

검증 항목:
- 대시보드 목록 조회
- 강의실 생성
- 강의실 목록 반영
- 비연속 IP 대역 등록
- 중복 대역 충돌(409) 확인
- 텍스트/이미지 위젯 API 조회

## 문제 해결 (Troubleshooting)

### 백엔드 재시작 시 "포트 이미 사용 중" 또는 "데이터베이스 잠금" 에러

**증상**: 첫 실행은 잘 되지만 백엔드를 껐다가 다시 켜면 PostgreSQL/Redis 연결 에러가 발생

**원인**:
- 이전 PostgreSQL/Redis 프로세스가 완전히 종료되지 않음
- 스테일(stale) PID 파일이 정리되지 않음
- 포트 바인딩 충돌

**해결방법**:
1. 백엔드 스크립트를 사용하여 시작하면 자동으로 기존 프로세스가 정리됩니다
   - Mac/Linux/Git Bash: `./scripts/run-backend-only.sh`
   - Windows: `scripts\run-backend-only.bat`
2. 수동으로 정리하려면 환경에 맞게 다음 중 하나를 실행:
   - Mac/Linux/Git Bash: `./scripts/cleanup-runtime.sh --quiet`
   - Windows PowerShell:
   ```powershell
   # PostgreSQL 프로세스 종료
   Get-Process -Name "postgres" -ErrorAction SilentlyContinue | ForEach-Object { taskkill /PID $_.Id /F }
   
   # Redis 프로세스 종료
   Get-Process -Name "redis-server" -ErrorAction SilentlyContinue | ForEach-Object { taskkill /PID $_.Id /F }
   
   # PID 파일 정리
   Remove-Item ".runtime\redis\redis-runtime.pid" -Force -ErrorAction SilentlyContinue
   Remove-Item ".runtime\postgres\data\postmaster.pid" -Force -ErrorAction SilentlyContinue
   ```
3. 이후 해당 OS의 백엔드 스크립트로 다시 시작

## 기여

기여를 원하시면 이 저장소를 포크하고 Pull Request를 제출해 주세요.

## 배포 (Deployment)

### 배포 파일 생성 및 배포

프로젝트는 **3개의 배포 가능한 모듈**로 구성되어 있습니다:

| 모듈 | 배포 파일 | 위치 |
|------|----------|------|
| **Backend** | `backend.jar` | `dist/backend/` |
| **Agent** | `agent.jar` | `dist/agent/` |
| **Frontend** | `frontend-all.jar`, `ClassroomDashboard.exe` | `dist/frontend/` |

### 빠른 배포 (Windows)

```powershell
# 모든 모듈 빌드 및 배포 자동화
.\scripts\release-all.bat
```

### 빠른 배포 (Linux/Mac)

```bash
# 권한 부여
chmod +x scripts/release-all.sh

# Mac/Linux 사전 점검 (권한/Java/gradlew)
./scripts/mac-preflight.sh

# 모든 모듈 빌드 및 배포 자동화
./scripts/release-all.sh
```

### 수동 배포 단계

#### 1단계: Java 아티팩트 빌드

```bash
# Windows
.\scripts\build-all.bat

# 개별 빌드
.\scripts\build-backend.bat
.\scripts\build-agent.bat
.\scripts\build-frontend.bat

# Linux/Mac
./scripts/build-all.sh
```

#### 2단계: Docker 이미지 빌드

```bash
# Windows
.\scripts\build-docker.bat

# Linux/Mac
./scripts/build-docker.sh
```

#### 3단계: Docker Compose 스택 시작

```bash
docker compose up -d
```

#### 4단계: 상태 확인

```bash
# 컨테이너 실행 상태
docker compose ps

# 로그 확인
docker compose logs -f backend
```

### 배포 문서

자세한 배포 가이드는 다음 문서를 참고하세요:

- **[DEPLOYMENT.md](DEPLOYMENT.md)**: 상세 배포 가이드 (모듈별 배포, 설정, 문제 해결)
- **[DEPLOYMENT_CHECKLIST.md](DEPLOYMENT_CHECKLIST.md)**: 배포 체크리스트 (배포 전/후 검증)

### 환경 설정

배포 전에 `.env` 파일을 생성하고 설정하세요:

```bash
# .env 파일 생성 (템플릿 복사)
cp .env.example .env

# .env 파일 편집
# - SERVER_PORT: 8888 (내부 포트, 변경 불필요)
# - JWT_SECRET: 강력한 비밀키로 변경
# - AGENT_INSTALL_KEY: 에이전트 설치 키 변경
```

### 네트워킹 아키텍처

```
외부 (443 HTTPS)
       ↓
Nginx 리버스 프록시
       ↓
Backend (8888 내부)
       ↓
- REST API (/api/*)
- WebSocket (/ws/*)
- JWT 인증
```

### 포트 설정

| 포트 | 서비스 | 외부 접근 |
|------|--------|---------|
| 80 | HTTP → HTTPS 리다이렉트 | ✅ 허용 |
| 443 | HTTPS API & WebSocket | ✅ 허용 |
| 8888 | Backend (내부) | ❌ 차단 |

### SSL/TLS 인증서 (ZeroSSL)

자동 인증서 발급 및 갱신:

```bash
# 초기 인증서 발급 (자동)
docker exec nginx /opt/init-ssl.sh

# 수동 갱신
docker exec nginx /opt/renew-ssl.sh
```

### 모니터링

```bash
# 실시간 로그
docker compose logs -f

# 리소스 사용량
docker stats

# 컨테이너 상태
docker compose ps
```

### 배포 완료 확인

```bash
# Backend 헬스 체크
curl https://pelmon.kro.kr/health

# Frontend 실행 (GUI)
java -jar dist/frontend/frontend-all.jar

# Agent 실행 (모니터링)
java -jar dist/agent/agent.jar
```

