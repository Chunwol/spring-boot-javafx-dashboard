# Spring Boot JavaFX Dashboard

이 프로젝트는 Spring Boot를 사용한 백엔드 REST API와 JavaFX를 사용한 프론트엔드 대시보드를 포함합니다. SQLite 데이터베이스를 사용하여 데이터를 저장하며, 이메일 알림 시스템과 스케줄러 기능을 제공합니다.

## 프로젝트 구조

```
spring-boot-javafx-dashboard
├── backend                # 백엔드 모듈
│   ├── src
│   │   └── main
│   │       ├── java
│   │       │   └── com
│   │       │       └── example
│   │       │           └── backend
│   │       │               ├── Application.java
│   │       │               ├── controller
│   │       │               │   └── ApiController.java
│   │       │               ├── service
│   │       │               │   ├── MailService.java
│   │       │               │   └── SchedulerService.java
│   │       │               ├── repository
│   │       │               │   └── UserRepository.java
│   │       │               └── model
│   │       │                   └── User.java
│   │       └── resources
│   │           ├── application.yml
│   │           └── db
│   │               └── schema.sql
│   └── build.gradle
├── frontend               # 프론트엔드 모듈
│   ├── src
│   │   └── main
│   │       ├── java
│   │       │   └── com
│   │       │       └── example
│   │       │           └── frontend
│   │       │               ├── DashboardApp.java
│   │       │               └── controller
│   │       │                   └── DashboardController.java
│   │       └── resources
│   │           └── fxml
│   │               └── dashboard.fxml
│   └── build.gradle
├── common                 # 공통 모듈
│   ├── src
│   │   └── main
│   │       └── java
│   │           └── com
│   │               └── example
│   │                   └── dto
│   │                       └── UserDto.java
│   └── build.gradle
├── scripts                # 초기화 스크립트
│   └── init-sql
│       └── init-db.sql
├── gradle                 # Gradle Wrapper
│   └── wrapper
│       └── gradle-wrapper.properties
├── gradlew                # Gradle Wrapper 실행 스크립트 (Unix)
├── gradlew.bat           # Gradle Wrapper 실행 스크립트 (Windows)
├── settings.gradle        # Gradle 멀티 모듈 설정
├── build.gradle           # 루트 프로젝트의 Gradle 빌드 설정
├── .gitignore             # Git 무시 파일
└── README.md              # 이 문서
```

## 기능

- **백엔드**: Spring Boot를 사용하여 REST API를 구축하고, SQLite 데이터베이스와 통신합니다.
- **프론트엔드**: JavaFX를 사용하여 대시보드 UI를 구현합니다.
- **이메일 알림**: Spring Mail을 통해 사용자에게 이메일 알림을 전송합니다.
- **스케줄러**: Spring Scheduler를 사용하여 주기적으로 작업을 수행합니다.

## 설정

### 빠른 시작
```bash
# 1. .env 파일 생성
cp .env.example .env

# 2. .env 파일을 환경에 맞게 수정
# (자세한 설정 방법은 ENV_GUIDE.md 참고)

# 3. 백엔드 실행
./gradlew :backend:bootRun

# 4. 프론트엔드 실행 (다른 터미널에서)
./gradlew :frontend:run
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
SERVER_PORT=8080

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
   ```
   cd backend
   ./gradlew bootRun
   ```

2. 프론트엔드 애플리케이션을 실행합니다:
   ```
   cd frontend
   ./gradlew run
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