# Classroom Dashboard

Spring Boot 백엔드, React 웹 관제 화면, Windows PC 에이전트로 구성된 강의실 통합 관제 시스템입니다. 웹 화면은 백엔드 JAR과 Docker 이미지에 포함되어 API와 같은 주소에서 제공됩니다. JavaFX 클라이언트는 폐기되었습니다.

## 구성

```text
classroom-dashboard/
├── backend/       Spring Boot 4 REST API, WebSocket, 정적 웹 배포
├── web-frontend/  React 19, TypeScript, Vite 관제 화면
├── agent/         Windows PC 에이전트와 SCMATE 연동
├── common/        공통 DTO
├── deploy/        NAS/Docker 배포 구성
├── scripts/       설치, 복구, 진단, 빌드 스크립트
└── docs/          운영 및 배포 문서
```

## 주요 기능

- 강의실·PC 상태, 트래픽, 장애, 보고서 통합 조회
- 종료·재부팅·Wake-on-LAN·스크립트·파일 배포 명령
- SCMATE 운영자 모드, 시점 저장, 보호 상태 워크플로
- 브라우저 noVNC 원격 제어와 강의실 화면 모아보기
- `ADMIN`, `USER`, `VIEWER` 역할 기반 API/화면 권한
- 아이디 기억과 HttpOnly 갱신 쿠키 기반 자동 로그인
- PostgreSQL, Redis, Flyway, Docker Compose 운영

## 요구 사항

- JDK 25
- Node.js 24 이상과 npm
- Docker Compose 또는 PostgreSQL/Redis
- Windows 에이전트 빌드 시 WiX 등 설치 패키지 도구

## 개발 실행

환경 파일을 만든 뒤 실제 비밀값을 설정합니다. `.env`는 Git에 포함되지 않습니다.

```powershell
Copy-Item .env.example .env
```

백엔드와 웹 개발 서버를 각각 실행합니다.

```powershell
.\gradlew.bat :backend:bootRun

Set-Location web-frontend
npm ci
npm run dev
```

- 백엔드와 운영형 웹: `http://localhost:8888`
- Vite 개발 서버: `http://localhost:5173`

Windows 통합 개발 실행은 `scripts\run-dev.bat`, Linux/macOS는 `scripts/run-dev.sh`를 사용할 수 있습니다.

## 검증

```powershell
Set-Location web-frontend
npm ci
npm run typecheck
npm test
npm run build

Set-Location ..
.\gradlew.bat :common:test :agent:test :backend:test -PskipWebFrontendBuild=true --no-daemon
.\gradlew.bat :backend:clean :backend:bootJar --no-daemon
```

`backend:bootJar`는 잠긴 npm 의존성으로 웹을 빌드한 뒤 Spring Boot JAR의 정적 리소스에 포함합니다.

## 운영 배포

```powershell
deploy\scripts\build-and-export.bat
```

생성된 이미지를 NAS에 올린 뒤 `app` 서비스만 재생성하고 아래 항목을 확인합니다.

```bash
curl -fsS http://127.0.0.1:8888/actuator/health
curl -fsS http://127.0.0.1:8888/ | head
docker compose ps
docker compose logs --tail=200 app
```

자동 로그인 쿠키를 HTTPS에서만 전송하려면 운영 환경에 `JWT_REFRESH_COOKIE_SECURE=true`를 설정합니다. 갱신 쿠키 기본 유효기간은 90일이며 성공한 갱신마다 회전합니다. 비밀번호는 브라우저 저장소에 저장하지 않습니다.

## 문서

- [웹 배포](docs/WEB_FRONTEND_DEPLOYMENT.md)
- [운영 배포](docs/DEPLOYMENT.md)
- [환경 변수](docs/ENV_GUIDE.md)
- [관리자 운영](docs/ADMIN_OPERATIONS_GUIDE.md)
- [에이전트 설치](docs/AGENT_INSTALL_GUIDE.md)
- [SCMATE 연동](docs/SCMATE_RX2_INTEGRATION.md)

## 보안 주의

- 운영의 `JWT_SECRET`, DB/SMTP/SCMATE/라우터 비밀번호는 반드시 환경 변수로 주입합니다.
- `VIEWER` 제한과 관리자 전용 명령은 화면 숨김이 아니라 백엔드 권한 검사를 기준으로 합니다.
- 에이전트 설치 키와 공유 비밀은 저장소에 커밋하지 않습니다.
