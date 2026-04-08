# 독립 실행 가이드 (백엔드/프론트엔드)

본 프로젝트는 도커 없이 Windows/Mac/Linux에서 직접 실행할 수 있도록 구성되었습니다.

## 📁 디렉토리 구조

```
spring-boot-javafx-dashboard/
├── scripts/
│   ├── run-dev.sh               # 개발모드 (Mac/Linux/Git Bash)
│   ├── run-dev.bat              # 개발모드 (Windows)
│   ├── run-backend-only.sh      # 백엔드만 (Mac/Linux)
│   ├── run-frontend-only.sh     # 프론트엔드만 (Mac/Linux)
│   ├── run-backend.bat          # 백엔드만 (Windows)
│   ├── run-frontend-dev.bat     # 프론트엔드만 (Windows)
│   └── ...
├── backend/
│   └── .env                     # 백엔드 환경 설정
├── frontend/
│   └── .env                     # 프론트엔드 환경 설정
├── gradlew                      # Mac/Linux/Git Bash 용 gradle wrapper
├── gradlew.bat                  # Windows 용 gradle wrapper
└── ...
```

## 🚀 빠른 시작

### Windows
```batch
REM 프로젝트 루트에서:
scripts\run-dev.bat
```

### Mac / Linux / Git Bash
```bash
# 프로젝트 루트에서:
scripts/run-dev.sh
```

**또는 개별 실행:**

#### Windows
```batch
REM 백엔드만
scripts\run-backend-only.bat

REM 다른 창에서 프론트엔드만
scripts\run-frontend-dev.bat
```

#### Mac / Linux / Git Bash
```bash
# 백엔드만
scripts/run-backend-only.sh

# 다른 터미널에서 프론트엔드만
scripts/run-frontend-only.sh
```

---

## 1️⃣ 환경 설정 파일 준비

### 방법 A: 각 모듈 폴더 내부 (권장)
```
backend/.env        # 백엔드 환경 변수
frontend/.env       # 프론트엔드 환경 변수
```

### 방법 B: 프로젝트 루트 (선택사항)
```
backend.env         # 또는 루트에 저장
frontend.env        # 또는 루트에 저장
```

## 2️⃣ 환경 변수 설정

### **backend/.env (또는 backend.env)** 예시:
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
LOG_LEVEL=DEBUG

# Rate Limiting (개발환경 - 충분히 큼)
RATELIMIT_REQUESTS_PER_MINUTE=10000
RATELIMIT_REQUESTS_PER_HOUR=300000

# 하트비트
HEARTBEAT_TIMEOUT_MINUTES=5
HEARTBEAT_INTERVAL_SECONDS=120

# 파일 업로드
UPLOAD_DIR=./uploads/
MAX_FILE_SIZE=20971520

# 기본 관리자 계정
DEFAULT_ADMIN_USERNAME=admin
DEFAULT_ADMIN_PASSWORD=password
DEFAULT_ADMIN_EMAIL=admin@example.com
```

### **frontend/.env (또는 frontend.env)** 예시:
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
LOG_LEVEL=DEBUG

# 새로고침 간격 (milliseconds)
REFRESH_INTERVAL_DASHBOARD=30000
REFRESH_INTERVAL_HEARTBEAT=120000
```

## 3️⃣ 서버 실행

### **통합 개발모드 (권장) - 전체 빌드 한 번에**

**Windows:**
```batch
scripts\run-dev.bat
```
- 새 cmd 창 2개 자동 오픈 (백엔드, 프론트엔드)
- 빌드 → 백엔드 시작 → 프론트엔드 시작
- Ctrl+C로 정지

**Mac/Linux/Git Bash:**
```bash
scripts/run-dev.sh
```
- 같은 터미널에서 둘 다 실행
- Ctrl+C로 정지

### **개별 실행 - 백엔드와 프론트엔드 따로**

**Windows:**
```batch
REM 터미널 1: 백엔드 시작
scripts\run-backend-only.bat

REM 터미널 2: 프론트엔드 시작
scripts\run-frontend-dev.bat
```

**Mac/Linux/Git Bash:**
```bash
# 터미널 1: 백엔드 시작
scripts/run-backend-only.sh

# 터미널 2: 프론트엔드 시작
scripts/run-frontend-only.sh
```

## 🔧 수동 빌드 및 실행

```bash
# 1. 전체 빌드 (테스트 제외)
./gradlew clean build -x test

# 2. 백엔드만 빌드
./gradlew :backend:build

# 3. 프론트엔드만 빌드
./gradlew :frontend:build

# 4. 백엔드만 실행 (bootRun)
./gradlew :backend:bootRun

# 5. 프론트엔드만 실행
./gradlew :frontend:run
```

(Windows에서는 `gradlew` 대신 `gradlew.bat` 사용)

## 📡 필수 요구사항

- **Java 17 이상**
- **Windows 10+, Mac, Linux** (동일하게 지원)
- **SQLite** (자동 포함)

## 🔍 포트 확인

기본적으로:
- **백엔드**: http://localhost:8888
- **프론트엔드**: JavaFX 클라이언트 (기본 포트 없음)

포트를 변경하려면 backend/.env의 `SERVER_PORT`, frontend/.env의 `API_SERVER_PORT`를 수정하세요.

## 📝 로그 확인

### 통합 개발모드 실행 시
```
backend.log         # 백엔드 로그
frontend.log        # 프론트엔드 로그
```

로그 레벨은 각 `.env`의 `LOG_LEVEL` 항목으로 조절합니다:
```properties
LOG_LEVEL=DEBUG      # 상세 디버그 로그 (개발모드 추천)
LOG_LEVEL=INFO       # 기본 정보 로그
LOG_LEVEL=WARN       # 경고만
LOG_LEVEL=ERROR      # 에러만
```

## ⚠️ Rate Limit 문제

**증상**: IP 대역 목록 조회 시 "Rate limit exceeded" 에러

**해결책**: backend/.env에서 다음을 설정
```properties
RATELIMIT_REQUESTS_PER_MINUTE=10000
RATELIMIT_REQUESTS_PER_HOUR=300000
```

/ip-ranges 엔드포인트는 개발모드에서 자동으로 제외되므로 문제없습니다.

## 🛑 문제 해결

### JAR 파일을 찾을 수 없음
- `.env` 파일이 올바른 경로에 있는지 확인
- 스크립트가 자동으로 빌드를 시도합니다

### 포트가 이미 사용 중

**Windows:**
```batch
REM 포트 사용 프로세스 확인
netstat -ano | findstr :8888

REM 프로세스 강제 종료 (PID 확인 후)
taskkill /PID <PID> /F
```

**Mac/Linux:**
```bash
# 포트 사용 프로세스 확인
lsof -i :8888

# 프로세스 강제 종료 (PID 확인 후)
kill -9 <PID>
```

### Java 버전 문제
```bash
# Java 버전 확인
java -version

# Java 17 이상 필요
```

### UI가 나타나지 않음 (Mac)
Mac에서 JavaFX 창이 안 뜨는 경우:
```bash
# XQuartz 설치 필요한 경우
brew install xquartz

# 다시 시도
scripts/run-dev.sh
```

## 🔐 보안 주의사항

- **production 환경**에서는 `JWT_SECRET`을 반드시 변경하세요
- **MAIL_PASSWORD**는 외부 노출 금지
- `.env` 파일을 `.gitignore`에 추가하여 git에 커밋되지 않도록 하세요
- Rate Limit 값을 합리적인 수준으로 유지하세요 (production: 600 req/min)

## 📚 추가 정보

- [API 문서](./API_DOCUMENTATION.md)
- [배포 가이드](./DEPLOYMENT.md)
- [환경 설정 가이드](./ENV_GUIDE.md)

---

**모든 설정이 완료되었습니다!**
- Windows: `scripts\run-dev.bat`
- Mac/Linux: `scripts/run-dev.sh`

