# 배포 가이드 (deploy/)

스마트 강의실 통합 관제 **백엔드**를 Synology NAS(또는 일반 Docker 호스트)에
배포·업데이트하는 절차서입니다.

> 프론트엔드(JavaFX 데스크톱 앱)는 이 스택에 포함되지 않습니다.
> 이 tar/이미지는 **백엔드 전용**입니다.

---

## 1. 아키텍처 개요

```
[관리자 PC: JavaFX 프론트엔드]         [에이전트 800대]
              │  HTTPS                        │ HTTPS
              ▼                               ▼
   ┌─────────────────────────────────────────────────┐
   │  Synology DSM 역방향 프록시 (HTTPS 종단)          │
   │  제어판 → 로그인 포털 → 역방향 프록시            │
   │       https://pelmon.kro.kr  →  localhost:8888    │
   └───────────────────────┬─────────────────────────┘
                            ▼  (HTTP, 8888)
   ┌─────────────────────────────────────────────────┐
   │  docker compose 스택 (deploy/docker-compose.yml)  │
   │                                                   │
   │   app (classroom-dashboard:latest)  ─ 8888        │
   │     │                                             │
   │     ├─→ postgres:16-alpine   (DB)                 │
   │     └─→ redis:7-alpine       (캐시/세션)          │
   └─────────────────────────────────────────────────┘
   데이터: deploy/data/{postgres,redis,uploads}  ← 호스트 bind mount
```

- **HTTPS는 Synology DSM 역방향 프록시가 처리**합니다. 이 스택에는 Nginx 컨테이너가 **없습니다**.
- 백엔드는 평문 HTTP `8888` 포트만 노출하고, DSM이 그 앞단에서 TLS를 종단합니다.
- `deploy/nginx/` 폴더는 **Synology가 아닌 직접 구성 Nginx 환경**에서만 쓰는 참고 설정입니다(아래 7장 참고).

---

## 2. 사전 준비 (빌드 PC = Windows)

- Docker Desktop 실행 중일 것
- 프로젝트 루트에서 작업 (`D:\Qsync\spring-boot-javafx-dashboard`)
- JDK / Gradle Wrapper는 저장소에 포함 (`gradlew.bat`)

---

## 3. 최초 1회: `.env` 설정

NAS의 `deploy/` 폴더에서:

```bash
cp .env.example .env
```

`.env`를 열어 **CHANGE_ME** 항목을 전부 실제 값으로 채웁니다.
키 레퍼런스는 **6장** 참고. 특히 다음은 반드시 변경:

| 키 | 설명 |
|---|---|
| `POSTGRES_PASSWORD` | DB 비밀번호 |
| `REDIS_PASSWORD` | Redis 비밀번호 |
| `JWT_SECRET` | 32자 이상 랜덤 문자열 |
| `DEFAULT_ADMIN_PASSWORD` | 관리자 초기 비번 |
| `DEFAULT_VIEWER_PASSWORD` | 뷰어 초기 비번 |
| `AGENT_INSTALL_KEY` | 에이전트 설치 키 |
| `DOMAIN` / `EMAIL` | 도메인·연락 이메일 |

> `.env`에는 운영 시크릿이 들어갑니다. Git에 커밋 금지, NAS에서 안전 보관.

---

## 4. 빌드 — tar 이미지 생성 (빌드 PC)

### Windows (권장)

```bat
deploy\scripts\build-and-export.bat
```

이 스크립트가 자동으로:
1. `gradlew.bat :backend:bootJar -x test` — 백엔드 JAR 빌드
2. `docker build -f deploy\Dockerfile` — 이미지 생성
3. `docker save -o deploy\classroom-dashboard.tar` — **`classroom-dashboard.tar`** 출력

### Linux / macOS

`build-and-export.sh`는 **JAR을 먼저 빌드하지 않습니다.** 반드시 수동으로 먼저:

```bash
./gradlew :backend:bootJar
bash deploy/scripts/build-and-export.sh      # → deploy/classroom-dashboard.tar.gz (gzip)
```

> ⚠️ `deploy/Dockerfile`은 `backend/build/libs/backend-0.0.1-SNAPSHOT.jar`를
> **복사만** 합니다(빌드 안 함). JAR을 먼저 안 만들면 stale/실패합니다.
>
> | 스크립트 | JAR 빌드 | 출력 파일 |
> |---|---|---|
> | `build-and-export.bat` (Win) | ✅ 자동 | `classroom-dashboard.tar` |
> | `build-and-export.sh` (Lin) | ❌ 수동 선행 필요 | `classroom-dashboard.tar.gz` |

---

## 5. NAS 배포

### 5-1. 최초 배포

```bash
# 1) deploy/ 폴더 전체를 NAS로 복사 (tar 포함)
# 2) NAS에서:
cd deploy
docker load -i classroom-dashboard.tar          # .tar.gz 면:  docker load < classroom-dashboard.tar.gz
cp .env.example .env && nano .env                # 3장 참고하여 값 채우기
docker compose up -d
docker compose ps                                # 상태 확인
docker compose logs -f app                       # 기동 로그 확인
```

### 5-2. 업데이트 (★ 자주 쓰는 절차)

> **핵심 원칙**
> - 컨테이너·데이터 **삭제 불필요**. 데이터는 `deploy/data/`에 bind mount → 안전.
> - `postgres`/`redis`는 **건드리지 않음**. `app`(백엔드)만 새 이미지로 교체.
> - **다운타임**: 백엔드만 수십 초~1분(JVM 부팅+Flyway+헬스체크 start_period 60s).
>   postgres/redis는 무중단.
> - 이 tar는 **백엔드 전용**. 프론트엔드(JavaFX) 변경은 이 절차로 반영 안 됨(별도 배포).

#### STEP 0 — 빌드 PC: 새 tar 생성

```bat
:: Windows
deploy\scripts\build-and-export.bat        :: → deploy\classroom-dashboard.tar
```

#### STEP 1 — 사전 점검 (업데이트 전 무엇이 바뀌었나)

| 바뀐 것 | NAS에 함께 복사할 파일 | 추가 조치 |
|---|---|---|
| 백엔드 코드만 | `classroom-dashboard.tar` | 없음 |
| `.env` 키 추가/변경 | `classroom-dashboard.tar` + `.env`* | 3·6장 확인 |
| `docker-compose.yml` 변경 | `classroom-dashboard.tar` + `docker-compose.yml` | — |
| DB 스키마(마이그레이션) 추가 | `classroom-dashboard.tar` | 기동 시 Flyway 자동 적용. **DB 백업 필수** |

> *`.env`는 운영 시크릿이 들어있으니, **NAS의 기존 `.env` 값과 다른지 확인 후**
> 신규 키만 추가하세요. 통째로 덮어쓰면 시크릿이 어긋날 수 있습니다.

#### STEP 2 — NAS: 백업 + 롤백용 이미지 보존

```bash
cd deploy

# (a) DB 백업 (스키마 변경 없어도 권장)
docker compose exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup_$(date +%F_%H%M).sql

# (b) 현재 가동 중 이미지를 롤백용으로 태그 보존  ← 롤백 대비 핵심
docker tag classroom-dashboard:latest classroom-dashboard:prev
```

> `build-and-export`는 항상 같은 `classroom-dashboard.tar`(태그 `:latest`)를
> 덮어씁니다. 위 `:prev` 태그를 남겨두면 문제 시 즉시 되돌릴 수 있습니다.

#### STEP 3 — 새 이미지 로드 & 적용

```bash
# 새 classroom-dashboard.tar 를 deploy/ 로 복사한 뒤:

docker load -i classroom-dashboard.tar          # ★ 생략하면 업데이트 안 됨
docker compose up -d --force-recreate app        # 백엔드만 재생성
```

#### STEP 4 — 검증 (이상 시 STEP 5 롤백)

```bash
docker compose ps                                # app = Up (healthy) 확인
docker compose logs -f app                       # 기동 로그 / Flyway 마이그레이션 / 에러
curl -s localhost:8888/health                    # 200 OK
```

확인 체크리스트:
- `docker compose ps`에서 `app` 상태가 `Up (healthy)` (최대 ~60s 소요)
- 로그에 `Started Application`, Flyway `Successfully applied`/`up to date`
- 로그에 stacktrace·`Migration ... failed`·DB/Redis 인증 오류 없음
- 프론트엔드 로그인 + 에이전트 하트비트 정상 수신

#### STEP 5 — 롤백 (검증 실패 시)

```bash
docker tag classroom-dashboard:prev classroom-dashboard:latest
docker compose up -d --force-recreate app
# DB 스키마가 새 마이그레이션으로 바뀌었으면 STEP 2(a) 백업도 복구 (8장)
```

> **왜 `--force-recreate app`?**
> `image: classroom-dashboard:latest` 태그가 고정이라, `docker load`가
> `:latest` 이미지 ID를 교체합니다. `up -d`가 변경을 감지해 `app`만
> 재생성하지만, 일부 Compose 버전이 이미지 변경을 못 잡는 드문 케이스를
> 막기 위해 `--force-recreate app`을 붙입니다.

> ❌ **하지 말 것**
> - tar만 복사하고 `docker load` 생략 → 옛 이미지 그대로, 업데이트 안 됨
> - `docker compose down -v` → 불필요하고 위험한 습관
> - `docker compose up -d` 전에 `down` → 다운타임만 늘어남(불필요)
> - `.env` 통째 덮어쓰기 → NAS 운영 시크릿 손상 위험 (신규 키만 추가)

---

## 6. `docker-compose.yml` 구성 & `.env` 키 레퍼런스

### 서비스

| 서비스 | 이미지 | 포트 | 역할 |
|---|---|---|---|
| `app` | `classroom-dashboard:latest` | `8888:8888` | Spring Boot 백엔드 |
| `postgres` | `postgres:16-alpine` | (내부) | 메인 DB |
| `redis` | `redis:7-alpine` | (내부) | 캐시/세션 |

- 모두 `restart: unless-stopped`, `dashboard` 브리지 네트워크
- `app`은 `postgres`/`redis`가 **healthy**가 될 때까지 대기 후 기동
- `app` 헬스체크: `GET /health` (start_period 60s)
- `env_file: .env` → **`.env`의 모든 키가 컨테이너 환경변수로 주입됨**

### `.env` 키 전체

| 그룹 | 키 | 비고 |
|---|---|---|
| 도메인/SSL | `DOMAIN`, `EMAIL`, `ZEROSSL_EAB_KID`, `ZEROSSL_EAB_HMAC_KEY` | ZeroSSL은 직접 Nginx 환경에서만 필요 |
| PostgreSQL | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | |
| Redis | `REDIS_PASSWORD` | 비우면 무인증 |
| JWT | `JWT_SECRET`(32자+), `JWT_EXPIRATION`(ms, 86400000=24h) | |
| 기본 계정 | `DEFAULT_ADMIN_*`, `DEFAULT_VIEWER_*`, `DEFAULT_USER_*` | `*_SYNC_ON_STARTUP=true`면 기동 시 동기화 |
| 권한 매핑 | `ADMIN_USERS`, `VIEWER_USERS` | 쉼표 다중, 비우면 DEFAULT_*_USERNAME 사용 |
| Agent | `AGENT_INSTALL_KEY` | 에이전트 설치 키 |
| Mail | `MAIL_HOST/PORT/USERNAME/PASSWORD`, `ALERT_EMAIL` | `ALERT_EMAIL` 비우면 이메일 알림 비활성 |
| App | `APP_NAME`, `UPLOAD_DIR`(/app/uploads/), `MAX_FILE_SIZE`(byte), `LOG_LEVEL` | |
| Nginx | `NGINX_ACCEL_REDIRECT`(false), `NGINX_INTERNAL_LOCATION` | **7장 필독** |
| Flyway | `FLYWAY_ENABLED`(true), `FLYWAY_LOCATIONS` | 끄면 스키마 자동적용 안 됨 |
| JPA | `JPA_DDL_AUTO`(validate), `JPA_SHOW_SQL`(false) | 운영은 validate/false 고정 |

> `docker-compose.yml`이나 `.env`를 수정했다면, tar뿐 아니라 **그 파일도 NAS에 복사**한 뒤
> `docker compose up -d`(또는 `--force-recreate app`) 해야 반영됩니다.

---

## 7. Nginx / HTTPS / `NGINX_ACCEL_REDIRECT`

### 7-1. HTTPS (Synology)

- **Synology DSM이 HTTPS를 처리**합니다. 이 스택에 Nginx·certbot 컨테이너 없음.
- 설정: DSM **제어판 → 로그인 포털 → 고급 → 역방향 프록시**
  - 원본: `https://<도메인>` (443)
  - 대상: `http://localhost:8888`
  - WebSocket 헤더 전달 활성화(Upgrade/Connection) — 실시간 대시보드용
- 백엔드는 `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`로 프록시의
  `X-Forwarded-Proto/For`를 신뢰합니다.

### 7-2. `deploy/nginx/` 폴더

`deploy/nginx/conf.d/default.conf.template`은 **Synology가 아닌**
직접 구성 Nginx 환경 전용 참고 파일입니다(HTTP→HTTPS 리다이렉트 +
ZeroSSL/certbot 챌린지). **Synology 배포에서는 사용하지 않습니다.**

### 7-3. `NGINX_ACCEL_REDIRECT` (반드시 `false`)

대용량 파일(스크립트/수집 결과) 다운로드를 Nginx에 위임하는
X-Accel-Redirect 최적화 스위치입니다.

```
application.yml:  accel-redirect-enabled: ${NGINX_ACCEL_REDIRECT:false}
ScriptController:  true  → 헤더만 반환, Nginx가 직접 파일 서빙
                   false → Spring Boot가 직접 스트리밍(기본/안전 폴백)
```

| 값 | 동작 | 조건 |
|---|---|---|
| `false` (기본) | Spring Boot 직접 스트리밍 | 추가 설정 불필요. **Synology는 이것만 가능** |
| `true` | Nginx가 sendfile로 직접 서빙(JVM 힙 우회, 800대 동시 다운로드 대비) | Nginx에 internal location + uploads 볼륨 필요 |

> ⚠️ **Synology DSM 역방향 프록시로는 `internal;` location 블록을 만들 수 없습니다.**
> `NGINX_ACCEL_REDIRECT=true`로 켜면 **모든 파일 다운로드가 깨집니다**(Nginx가 404).
> 그래서 `deploy/.env`에 `false`로 명시 고정해 두었습니다.

**비-Synology(직접 Nginx) 환경에서 켜는 법** — 3개 세트 동시 적용:

1. `deploy/.env`:
   ```bash
   NGINX_ACCEL_REDIRECT=true
   NGINX_INTERNAL_LOCATION=/internal-uploads/
   ```
2. Nginx server 블록:
   ```nginx
   location /internal-uploads/ {
       internal;
       alias /app/uploads/;     # UPLOAD_DIR 과 동일 경로
   }
   ```
3. Nginx 컨테이너에 백엔드 `uploads` 볼륨(`deploy/data/uploads`) 마운트

하나라도 빠지면 다운로드가 깨지므로, 확신 없으면 **`false` 유지**.

---

## 8. 데이터 영속성 & 백업

모든 상태 데이터는 **호스트 bind mount**(`deploy/data/`)에 저장 → 컨테이너를
재생성/삭제해도 안 날아갑니다.

| 데이터 | 호스트 경로 | 컨테이너 경로 |
|---|---|---|
| PostgreSQL | `deploy/data/postgres` | `/var/lib/postgresql/data` |
| Redis(AOF) | `deploy/data/redis` | `/data` |
| 업로드 파일 | `deploy/data/uploads` | `/app/uploads` |

### DB 마이그레이션 (Flyway)

`FLYWAY_ENABLED=true`이면 **앱 기동 시 자동으로** 마이그레이션이 적용됩니다.
별도 수동 작업 불필요. 단, 업데이트 전 백업 권장.

### 백업 (업데이트 전 권장)

```bash
# DB 덤프
docker compose exec -T postgres \
  pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup_$(date +%F).sql

# 업로드 파일
tar czf uploads_$(date +%F).tar.gz data/uploads
```
> Windows 빌드 PC에는 `scripts/db-backup.ps1`도 있습니다.

### 복구

```bash
cat backup_YYYY-MM-DD.sql | docker compose exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

---

## 9. 운영 명령어

```bash
docker compose ps                       # 상태
docker compose logs -f app              # 백엔드 로그 추적
docker compose logs --tail=200 app      # 최근 200줄
docker compose restart app              # 백엔드만 재시작(이미지 변경 없을 때)
docker compose stop                     # 전체 정지(데이터 보존)
docker compose start                    # 다시 시작
docker compose down                     # 컨테이너 제거(데이터 보존, -v 금지!)
docker image prune -f                   # 누적된 옛 <none> 이미지 정리(안전)
curl -s localhost:8888/health           # 헬스체크
```

---

## 10. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| 업데이트했는데 코드가 그대로 | `docker load` 생략. tar 복사만으론 안 됨 → `docker load -i ...` 후 `up -d --force-recreate app` |
| `app`이 계속 재시작 | `docker compose logs app` 확인. 대개 `.env`의 DB/Redis 비번 불일치 또는 `JWT_SECRET` 누락 |
| `app`이 postgres 못 찾음 | postgres healthcheck 미통과. `docker compose logs postgres` 확인 |
| 파일 다운로드 깨짐 | `NGINX_ACCEL_REDIRECT=true`인데 Nginx internal location 없음 → `false`로 되돌리고 `up -d --force-recreate app` |
| 외부 접속 안 됨(내부는 OK) | Synology 역방향 프록시 대상이 `http://localhost:8888`인지, WebSocket 헤더 전달 켰는지 확인 |
| 마이그레이션 실패로 기동 중단 | DB 백업 복구 후, 문제 마이그레이션 확인. `JPA_DDL_AUTO`는 항상 `validate` 유지 |
| 디스크 부족 | `docker image prune -f`, 오래된 `backup_*.sql`/`uploads_*.tar.gz` 정리 |

### 롤백

업데이트 STEP 2(b)에서 `:prev` 태그를 남겨두었다면 즉시 복구:

```bash
docker tag classroom-dashboard:prev classroom-dashboard:latest
docker compose up -d --force-recreate app
```

`:prev`가 없고 이전 버전 tar만 보관한 경우:

```bash
docker load -i classroom-dashboard-<이전버전>.tar
docker tag classroom-dashboard:<이전> classroom-dashboard:latest
docker compose up -d --force-recreate app
```

DB 스키마가 새 마이그레이션으로 바뀐 경우, 8장의 DB 백업 복구도 함께 수행.

---

## 11. 요약 (업데이트 한 장 요약)

```bash
# ── 빌드 PC (Windows) ──────────────────────────────
deploy\scripts\build-and-export.bat          # → deploy\classroom-dashboard.tar

# ── NAS ────────────────────────────────────────────
#   classroom-dashboard.tar 를 deploy/ 로 복사 (.env/compose 변경 시 함께 복사)
cd deploy
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup_$(date +%F_%H%M).sql
docker tag classroom-dashboard:latest classroom-dashboard:prev   # 롤백 대비
docker load -i classroom-dashboard.tar
docker compose up -d --force-recreate app
docker compose ps && docker compose logs -f app                  # Up (healthy) 확인

# ── 문제 시 롤백 ───────────────────────────────────
docker tag classroom-dashboard:prev classroom-dashboard:latest
docker compose up -d --force-recreate app
```

> 컨테이너·데이터 **삭제 불필요**. 데이터는 `deploy/data/`에 bind mount되어 안전합니다.
> `postgres`/`redis`는 무중단, 다운타임은 백엔드만 수십 초~1분.
