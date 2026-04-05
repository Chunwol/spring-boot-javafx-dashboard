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
├── frontend/              # 프론트엔드 모듈 (JavaFX 21)
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
- **프론트엔드**: JavaFX 21 Grafana 스타일 대시보드 (AtlantaFX 테마, MVVM 패턴)
- **에이전트**: Windows PC 모니터링 서비스 (OSHI 하드웨어 정보, 하트비트, 원격 명령 실행)
- **강의실 관리**: IP 대역 기반 자동 매핑, 교사PC/학생PC 자동 분류
- **원격 제어**: 종료/재부팅, WOL, 명령 실행, PS1 스크립트 배포
- **리포트**: iTextPDF 기반 트래픽·가동률 보고서, 이메일 발송
- **배포**: Docker Compose, Nginx 리버스 프록시, ZeroSSL 자동 인증서

## 설정

### 빠른 시작
```bash
# 1. .env 파일 생성
cp .env.example .env

# 2. .env 파일을 환경에 맞게 수정
# (자세한 설정 방법은 ENV_GUIDE.md 참고)

# 3. 백엔드 실행
./scripts/run-backend.bat

# 4. 프론트엔드 개발 실행 (다른 터미널에서)
./scripts/run-frontend-dev.bat
```

### 상세 설정
모든 환경 설정값은 `.env` 파일에서 관리됩니다.

- **`.env` 파일 생성**: `.env.example` 파일을 복사하여 `.env` 파일 생성
- **`.env` 파일 수정**: 데이터베이스, 이메일, 서버 포트 등 설정
- **자세한 가이드**: [ENV_GUIDE.md](ENV_GUIDE.md) 참고

### 주요 설정값
```env
# 데이터베이스
DB_URL=jdbc:sqlite:db/mydatabase.db

# 서버
SERVER_PORT=8888

# 이메일
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=your-email@gmail.com

# JWT (프로덕션에서는 강력한 키 설정)
JWT_SECRET=your-secret-key
```

### 주의사항
- `.env` 파일은 Git에 커밋되지 않습니다 (`.gitignore`에 등록)
- 프로덕션 환경에서는 반드시 `JWT_SECRET` 값 변경
- 민감한 정보(API 키, 비밀번호)는 `.env` 파일에만 저장

## 실행 방법

1. 백엔드 서버를 실행합니다:
       ```bat
       scripts\run-backend.bat
       ```

2. 프론트엔드 애플리케이션을 실행합니다:
       ```bat
       scripts\run-frontend-dev.bat
       ```

3. 패키징된 프론트 EXE를 실행하려면:
       ```bat
       scripts\run-frontend.bat
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

## 기여

기여를 원하시면 이 저장소를 포크하고 Pull Request를 제출해 주세요.

## 배포 (Deployment)

### 배포 파일 생성 및 배포

프로젝트는 **3개의 배포 가능한 모듈**로 구성되어 있습니다:

| 모듈 | JAR 파일 | 위치 |
|------|----------|------|
| **Backend** | `backend-0.0.1-SNAPSHOT.jar` | `backend/build/libs/` |
| **Agent** | `agent-1.0.0.jar` | `agent/build/libs/` |
| **Frontend** | `ClassroomDashboard.exe` | `frontend/build/package/ClassroomDashboard/` |

### 빠른 배포 (Windows)

```powershell
# 모든 모듈 빌드 및 배포 자동화
.\scripts\deploy.bat
```

### 빠른 배포 (Linux/Mac)

```bash
# 권한 부여
chmod +x scripts/deploy.sh

# 모든 모듈 빌드 및 배포 자동화
./scripts/deploy.sh
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
docker-compose up -d
```

#### 4단계: 상태 확인

```bash
# 컨테이너 실행 상태
docker-compose ps

# 로그 확인
docker-compose logs -f backend
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
docker-compose logs -f

# 리소스 사용량
docker stats

# 컨테이너 상태
docker-compose ps
```

### 배포 완료 확인

```bash
# Backend 헬스 체크
curl https://pelmon.kro.kr/health

# Frontend 실행 (GUI)
java -jar frontend/build/libs/dashboard-0.0.1.jar

# Agent 실행 (모니터링)
java -jar agent/build/libs/agent-1.0.0.jar
```