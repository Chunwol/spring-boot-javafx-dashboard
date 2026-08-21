# 환경 변수 가이드

운영에서는 `deploy/.env.example`을 `deploy/.env`로 복사해 사용합니다. PostgreSQL이 유일한 운영 데이터베이스이며 SQLite는 백엔드 테스트 전용입니다.

| 변수 | 예시 | 설명 |
|---|---|---|
| `DOMAIN` | `pelmon.kro.kr` | 외부 HTTPS 도메인 |
| `BACKEND_HOST_PORT` | `8888` | 로컬 역방향 프록시 대상 포트 |
| `CORS_ALLOWED_ORIGINS` | `https://pelmon.kro.kr` | 허용 웹 출처 |
| `POSTGRES_DB` | `dashboard_db` | PostgreSQL 데이터베이스 |
| `POSTGRES_USER` | `dashboard` | PostgreSQL 사용자 |
| `POSTGRES_PASSWORD` | 강한 임의 문자열 | PostgreSQL 비밀번호 |
| `REDIS_PASSWORD` | 강한 임의 문자열 | Redis 비밀번호 |
| `JWT_SECRET` | 32자 이상 임의 문자열 | JWT 서명 키 |
| `JWT_EXPIRATION` | `86400000` | 접근 토큰 유효기간(ms) |
| `JWT_REFRESH_EXPIRATION` | `7776000000` | 자동 로그인 갱신 쿠키 유효기간(ms, 기본 90일) |
| `JWT_REFRESH_COOKIE_SECURE` | HTTPS 운영 `true`, 로컬 HTTP `false` | 갱신 쿠키의 Secure 속성 |
| `AGENT_INSTALL_KEY` | `INST-...` | 에이전트 최초 등록 키 |
| `COMMAND_TIMEOUT_SECONDS` | `600` | 대용량 업데이트·파일 명령의 ACK 이후 결과 대기 시간. 실행 중 명령의 조기 재전달 방지 |
| `COMMAND_QUEUE_TTL_SECONDS` | `300` | 에이전트에 전달되지 않은 모든 명령의 최대 대기 시간. 만료 후 `CANCELLED` 처리 |
| `TRAFFIC_ALERT_THRESHOLD_GLOBAL_MBPS` | `2000` | 전체 네트워크 기준 바(Mbps) |
| `TRAFFIC_ALERT_THRESHOLD_CLASSROOM_MBPS` | `200` | 강의실별 기준 바(Mbps) |
| `TRAFFIC_ALERT_THRESHOLD_PC_MBPS` | `50` | PC별 기준 바(Mbps) |
| `REMOTE_CONTROL_ALLOWED_CIDRS` | `223.194.166.0/23,223.194.168.0/23,172.16.13.0/24,172.16.204.0/24` | VNC 릴레이 대상 운영 강의실 대역 |
| `REMOTE_CONTROL_MAX_SESSIONS` | `64` | 화면 모니터링 50대와 단일 원격 제어 여유를 포함한 동시 VNC 릴레이 상한 |
| `JAVA_HEAP_MIN` | `512m` | NAS 백엔드 JVM 시작 힙 |
| `JAVA_HEAP_MAX` | `1g` | 4GB NAS에서 PostgreSQL, Redis, DSM 메모리를 남기는 JVM 최대 힙 |
| `ROUTER_WOL_ENABLED` | `true` | 같은 `/24` 온라인 에이전트가 없을 때 ipTIME WOL 폴백 사용 |
| `ROUTER_WOL_USERNAME` | 별도 관리 계정 | ipTIME 공통 관리 계정. Git 커밋 금지 |
| `ROUTER_WOL_PASSWORD` | 강한 임의 문자열 | ipTIME 공통 관리 암호. Git 커밋 금지 |
| `ROUTER_WOL_CONNECT_TIMEOUT` | `2s` | 꺼진 공유기 연결 대기 제한 |
| `ROUTER_WOL_REQUEST_TIMEOUT` | `4s` | 로그인 및 WOL API 응답 대기 제한 |
| `ROUTER_WOL_166_PRIMARY_URL` 등 | `http://주소:8080` | 검증된 WAN WOL 패치가 적용된 공유기만 설정. 공란은 해당 경로 비활성화 |
| `ROUTER_WOL_169_PRIMARY_URL` | `http://223.194.169.250:8080` | 315호용 공유기. 기존 `223.194.168.250` 장비의 WAN 주소 변경 후 사용 |
| `ROUTER_WOL_6_PRIMARY_URL` | `http://주소:8080` | 6호관 공유기 설치 후 설정. 설치 전에는 공란 유지 |
| `DEFAULT_ADMIN_PASSWORD` | 강한 임의 문자열 | 초기 관리자 비밀번호 |
| `DEFAULT_VIEWER_PASSWORD` | 강한 임의 문자열 | 초기 보기 전용 계정 비밀번호 |
| `DB_BACKUP_ENABLED` | `true` | 자동 PostgreSQL 백업 |
| `DB_BACKUP_KEEP_DAYS` | `14` | 백업 보존 일수 |
| `UPLOAD_DIR` | `/app/uploads/` | 컨테이너 업로드 저장 경로 |

배포 전 `CHANGE_ME`가 남아 있지 않은지 확인합니다. `.env`는 Git에 커밋하지 않으며, 변경 후 `docker compose up -d --no-deps --force-recreate app`으로 적용합니다.

WOL은 대상과 같은 `/24`의 온라인 PCAgent를 먼저 사용합니다. 릴레이 에이전트가 없을 때만 설정된 ipTIME 공유기를 순서대로 시도하며, 첫 공유기가 꺼져 있으면 같은 대역의 두 번째 공유기로 넘어갑니다. 현재처럼 공인망이 WAN 포트에 연결되고 대상 PC가 WAN 대역에 있는 구성은 검증된 WAN WOL 패치가 적용된 공유기만 URL에 등록합니다. 미적용·점검 중인 공유기 URL을 공란으로 두면 해당 경로를 건너뛰고 정상 실패 응답을 반환합니다.
