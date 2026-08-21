# 운영 배포 가이드

운영 구성은 PostgreSQL 16, Redis 7, Spring Boot 백엔드와 백엔드 JAR에 포함된 React 웹 화면입니다. SQLite는 `backend/src/test` 테스트에서만 사용합니다.

## 배포 준비

1. Docker 및 Docker Compose를 설치합니다.
2. `deploy/.env.example`을 `deploy/.env`로 복사합니다.
3. 모든 `CHANGE_ME` 값을 교체하고 `REMOTE_CONTROL_ALLOWED_CIDRS`를 실제 강의실 대역으로 제한합니다. 웹 화면 모니터링은 `REMOTE_CONTROL_MAX_SESSIONS=64`를 사용합니다.
4. 공유기 WOL을 사용할 때만 `ROUTER_WOL_ENABLED=true`와 `ROUTER_WOL_USERNAME`, `ROUTER_WOL_PASSWORD`를 설정합니다. 현재 WAN 연결 구성을 유지하는 공유기는 검증된 WAN WOL 패치를 적용한 뒤 해당 `ROUTER_WOL_*_URL`만 설정합니다. 미적용·점검 중인 공유기와 6호관 설치 전 경로는 URL을 비워 둡니다.
5. 외부에는 HTTPS `443`만 공개하고, 앱 포트 `8888`은 NAS/서버의 로컬 역방향 프록시에서만 접근합니다.

4GB NAS의 JVM은 `JAVA_HEAP_MIN=512m`, `JAVA_HEAP_MAX=1g`를 사용합니다. VNC 릴레이는 영상을 디코딩하지 않으므로 화면 모니터링 때문에 힙을 2~3GB로 올리지 않습니다. PostgreSQL, Redis, DSM이 같은 장비에서 실행되는 상태에서 `-Xmx3g`는 사용하지 않습니다.

## 이미지 생성

Windows:

```bat
deploy\scripts\build-and-export.bat
```

Linux/macOS:

```bash
bash deploy/scripts/build-and-export.sh
```

두 스크립트는 웹 테스트·빌드와 백엔드 테스트를 통과해야 이미지를 생성합니다.

## NAS 배포

```bash
cd deploy
docker load -i classroom-dashboard.tar
docker compose config --quiet
docker compose up -d
docker compose ps
curl -fsS http://127.0.0.1:8888/actuator/health
```

PostgreSQL, Redis, 업로드 파일, 자동 DB 백업은 `deploy/data/` 아래에 영속 보관됩니다. 일반 재배포에는 `docker compose down -v`를 사용하지 않습니다.

## 업데이트와 롤백

업데이트 전 백업:

```bash
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > backup_$(date +%F_%H%M).sql
docker tag classroom-dashboard:latest classroom-dashboard:prev
```

새 이미지 적용:

```bash
docker load -i classroom-dashboard.tar
docker compose up -d --no-deps --force-recreate app
```

롤백:

```bash
docker tag classroom-dashboard:prev classroom-dashboard:latest
docker compose up -d --no-deps --force-recreate app
```

상세 NAS 역방향 프록시 및 운영 절차는 [`deploy/README.md`](../deploy/README.md)를 참고합니다.
