# 환경 설정 가이드 (.env 설정)

## 개요
이 프로젝트는 `.env` 파일을 사용하여 모든 환경 설정값을 관리합니다. 민감한 정보(DB 암호, API 키 등)는 `.env` 파일에 저장되며, 이 파일은 Git에 커밋되지 않습니다.

## 설정 단계

### 1단계: `.env` 파일 생성
```bash
# 프로젝트 루트에서 .env.example을 복사하여 .env 파일을 생성합니다
cp .env.example .env
```

### 2단계: `.env` 파일 수정
프로젝트 루트의 `.env` 파일을 열어서 다음 값들을 환경에 맞게 수정합니다.

## 주요 설정값

### 데이터베이스 설정
```
DB_URL=jdbc:sqlite:db/mydatabase.db
DB_DRIVER=org.sqlite.JDBC
DB_USERNAME=
DB_PASSWORD=
```
- **DB_URL**: SQLite 데이터베이스 파일 경로
- **DB_DRIVER**: JDBC 드라이버 클래스명
- 프로덕션 환경에서는 MySQL/PostgreSQL 사용 권장

### 서버 설정
```
SERVER_PORT=8080
SERVER_HOST=localhost
```
- **SERVER_PORT**: Spring Boot 서버가 실행될 포트
- **SERVER_HOST**: 서버 호스트명

### JWT 설정
```
JWT_SECRET=change-this-secret-key-in-production
JWT_EXPIRATION=86400000
```
- **JWT_SECRET**: JWT 서명 키 (프로덕션에서는 길고 복잡한 문자열 사용)
- **JWT_EXPIRATION**: 토큰 만료 시간 (밀리초 단위, 86400000 = 24시간)

### 이메일 설정
```
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-specific-password
MAIL_FROM=noreply@example.com
```
- Gmail 사용 시 "앱 비밀번호" 생성 필요
- [Google 앱 비밀번호 생성](https://support.google.com/accounts/answer/185833)

### 애플리케이션 설정
```
APP_NAME=Classroom Dashboard
APP_PROFILE=default
LOG_LEVEL=INFO
```
- **APP_PROFILE**: spring.profiles.active 값 (dev, test, prod)
- **LOG_LEVEL**: 로깅 레벨 (DEBUG, INFO, WARN, ERROR)

### 하트비트 설정
```
HEARTBEAT_TIMEOUT_MINUTES=5
HEARTBEAT_INTERVAL_SECONDS=120
```
- **HEARTBEAT_TIMEOUT_MINUTES**: PC 오프라인 판정 시간
- **HEARTBEAT_INTERVAL_SECONDS**: 에이전트 하트비트 간격

### 플래그 설정
```
TRAFFIC_ALERT_THRESHOLD_MBPS=100
UPLOAD_DIR=uploads/
MAX_FILE_SIZE=10485760
```
- **TRAFFIC_ALERT_THRESHOLD_MBPS**: 트래픽 알림 임계값
- **UPLOAD_DIR**: 파일 업로드 디렉토리
- **MAX_FILE_SIZE**: 최대 업로드 파일 크기 (바이트)

## 환경별 설정

### 개발 환경
```
APP_PROFILE=dev
LOG_LEVEL=DEBUG
JPA_SHOW_SQL=true
```

### 테스트 환경
```
APP_PROFILE=test
DB_URL=jdbc:sqlite:db/test.db
LOG_LEVEL=DEBUG
```

### 프로덕션 환경
```
APP_PROFILE=prod
LOG_LEVEL=WARN
JWT_SECRET=long-secure-random-string-here
DB_URL=jdbc:postgresql://db-host:5432/dashboard_db
```

## 프론트엔드 설정

프론트엔드는 다음 설정값을 사용합니다:
```
API_BASE_URL=http://localhost:8080/api/v1
WEBSOCKET_URL=ws://localhost:8080/ws
DASHBOARD_REFRESH_INTERVAL_SECONDS=5
```

## 주의사항

1. **.env 파일은 절대 Git에 커밋하지 마세요** - `.gitignore`에 자동 추가됨
2. **프로덕션 환경에서는 반드시 강력한 JWT_SECRET 사용**
3. **이메일 비밀번호는 앱 비밀번호 사용** (Gmail의 경우)
4. **데이터베이스 자격증명 변경** (기본값 사용 금지)

## 설정값 로드 순서

1. `.env` 파일의 값이 가장 높은 우선순위
2. 환경 변수 (`JAVA_OPTS` 등)
3. `application.yml`의 기본값

## 문제 해결

### `.env` 파일이 로드되지 않는 경우
- 프로젝트 루트에 `.env` 파일이 있는지 확인
- 파일명에 띄어쓰기가 없는지 확인
- 파일 인코딩이 UTF-8인지 확인

### 특정 설정값이 적용되지 않는 경우
- Spring Boot를 재시작
- `./gradlew clean build`로 클린 빌드 수행

## 참고자료
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
- [DotEnv Java 라이브러리](https://github.com/cdimascio/dotenv-java)
