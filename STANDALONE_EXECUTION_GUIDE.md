# 독립 실행 가이드 (백엔드/프론트엔드)

본 프로젝트는 도커 없이 Windows에서 EXE/배치파일로 직접 실행할 수 있도록 구성되었습니다.

## 📁 디렉토리 구조

```
spring-boot-javafx-dashboard/
├── backend/
│   ├── .env                 # 백엔드 환경 설정 (개별)
│   └── ...
├── frontend/
│   ├── .env                 # 프론트엔드 환경 설정 (개별)
│   └── ...
├── backend.env              # 또는 루트에 backend.env
├── frontend.env             # 또는 루트에 frontend.env
├── run-backend.bat          # 백엔드 실행 배치파일
├── run-backend.ps1          # 백엔드 실행 PowerShell
├── run-frontend.bat         # 프론트엔드 실행 배치파일
├── run-frontend.ps1         # 프론트엔드 실행 PowerShell
├── gradlew.bat
└── ...
```

## 🚀 빠른 시작

### 1단계: 환경 설정 파일 준비

#### 방법 A: 각 모듈 폴더 내부 (권장)
```
backend/.env        # 백엔드 환경 변수
frontend/.env       # 프론트엔드 환경 변수
```

#### 방법 B: 프로젝트 루트 (선택사항)
```
backend.env         # 또는 루트에 저장
frontend.env        # 또는 루트에 저장
```

### 2단계: 환경 변수 설정

#### **backend.env (또는 backend/.env)** 예시:
```properties
# 데이터베이스
DB_URL=jdbc:sqlite:./db/mydatabase.db
DB_DRIVER=org.sqlite.JDBC

# 서버 설정
SERVER_PORT=8888
SERVER_HOST=localhost
SERVER_BASE_URL=http://localhost:8888

# JWT
JWT_SECRET=your-secret-key-change-me-in-production
JWT_EXPIRATION=86400000

# 메일 (선택사항)
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-password

# 로깅
LOG_LEVEL=INFO

# 하트비트
HEARTBEAT_TIMEOUT_MINUTES=5
HEARTBEAT_INTERVAL_SECONDS=120

# 트래픽 임계값 (강의실별 깜빡거림)
TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS=5000
TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS=1000
TRAFFIC_ALERT_THRESHOLD_PC_MBPS=300

# 파일 업로드
UPLOAD_DIR=./uploads/
MAX_FILE_SIZE=20971520

# 기본 관리자 계정
DEFAULT_ADMIN_USERNAME=admin
DEFAULT_ADMIN_PASSWORD=password
DEFAULT_ADMIN_EMAIL=admin@example.com
```

#### **frontend.env (또는 frontend/.env)** 예시:
```properties
# API 서버 연결
API_SERVER_HOST=localhost
API_SERVER_PORT=8888
API_SERVER_PROTOCOL=http
API_BASE_URL=http://localhost:8888

# WebSocket
WEBSOCKET_PROTOCOL=ws
WEBSOCKET_HOST=localhost
WEBSOCKET_PORT=8888

# UI 설정
APP_NAME=Classroom Dashboard
APP_THEME=light

# 로깅
LOG_LEVEL=INFO

# 새로고침 간격 (milliseconds)
REFRESH_INTERVAL_DASHBOARD=15000
REFRESH_INTERVAL_HEARTBEAT=120000

# 트래픽 임계값 (백엔드와 동일해야 함)
TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS=5000
TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS=1000
TRAFFIC_ALERT_THRESHOLD_PC_MBPS=300
```

### 3단계: 서버 실행

#### **배치파일 사용 (권장)**

**명령 프롬프트(CMD)에서:**
```batch
REM 백엔드 시작
run-backend.bat

REM 다른 창에서 프론트엔드 시작
run-frontend.bat
```

#### **PowerShell 사용**

```powershell
# 실행 정책 설정 (첫 실행 시 한 번만)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# 백엔드 시작
.\run-backend.ps1

# 다른 창에서 프론트엔드 시작
.\run-frontend.ps1

# 옵션: 강제 빌드
.\run-backend.ps1 -Build
.\run-frontend.ps1 -Build
```

## 🔧 수동 빌드 및 실행

원하는 경우 수동으로 빌드하고 실행할 수 있습니다:

```batch
REM 1. 백엔드 JAR 빌드
gradlew.bat :backend:build

REM 2. 백엔드 실행
java -jar backend\build\libs\backend-0.0.1-SNAPSHOT.jar

REM 3. 프론트엔드 JAR 빌드 (Fat JAR)
gradlew.bat :frontend:shadowJar

REM 4. 프론트엔드 실행
java -jar frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar
```

## 📡 필수 요구사항

- **Java 17 이상**
- **Windows 10 이상** (배치파일 기준)
- **SQLite** (데이터베이스, 자동 포함)

## 🔍 포트 확인

기본적으로:
- **백엔드**: http://localhost:8888
- **프론트엔드**: JavaFX 클라이언트 (기본 포트 없음)

포트를 변경하려면 `.env` 파일의 `SERVER_PORT`, `API_SERVER_PORT`를 수정하세요.

## 📝 로그 확인

로그는 콘솔에 출력되며, 로그 레벨은 `.env`의 `LOG_LEVEL` 항목으로 조절합니다.

```properties
LOG_LEVEL=DEBUG      # 상세 디버그 로그
LOG_LEVEL=INFO       # 기본 정보 로그
LOG_LEVEL=WARN       # 경고만
LOG_LEVEL=ERROR      # 에러만
```

## 🛑 문제 해결

### JAR 파일을 찾을 수 없음
- `.env` 파일이 올바른 경로에 있는지 확인
- 배치 스크립트가 자동으로 빌드를 시도합니다

### 포트가 이미 사용 중
```batch
REM 포트 사용 프로세스 확인
netstat -ano | findstr :8888

REM 프로세스 강제 종료 (PID 확인 후)
taskkill /PID <PID> /F
```

### Java 버전 문제
```batch
REM Java 버전 확인
java -version

REM Java 17 이상 필요
```

## 🔐 보안 주의사항

- **production 환경**에서는 `JWT_SECRET`을 반드시 변경하세요
- **MAIL_PASSWORD**는 외부 노출 금지
- `.env` 파일을 `.gitignore`에 추가하여 git에 커밋되지 않도록 하세요

## 📚 추가 정보

- [API 문서](./API_DOCUMENTATION.md)
- [아키텍처](./docs/FRONTEND_ARCHITECTURE.md)
- [개발 가이드](./docs/ENGINEERING_HANDOFF_2026-03-18.md)

---

**모든 설정이 완료되었습니다. 백엔드는 포트 8888에서, 프론트엔드는 각각의 .env 설정에 따라 실행됩니다.**
