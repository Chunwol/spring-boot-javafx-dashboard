#!/bin/sh
set -eu

EXPECTED_SHA="${1:?expected JAR SHA-256 is required}"
UPLOAD='/tmp/classroom-dashboard-app.jar'
DEPLOY_DIR='/volume1/컴퓨터공학부_실습실/docker/deploy'
DOCKER=$(command -v docker || true)
if [ -z "$DOCKER" ] && [ -x /usr/local/bin/docker ]; then
    DOCKER='/usr/local/bin/docker'
fi
test -n "$DOCKER"

compose() {
    if command -v docker-compose >/dev/null 2>&1; then
        docker-compose "$@"
    elif [ -x /usr/local/bin/docker-compose ]; then
        /usr/local/bin/docker-compose "$@"
    else
        "$DOCKER" compose "$@"
    fi
}

cd "$DEPLOY_DIR"
test -f "$UPLOAD"
ACTUAL_SHA=$(sha256sum "$UPLOAD" | awk '{print $1}')
if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    printf 'JAR hash mismatch: expected=%s actual=%s\n' "$EXPECTED_SHA" "$ACTUAL_SHA" >&2
    exit 2
fi

APP_BEFORE=$($DOCKER inspect -f '{{.Id}}' deploy_app_1)
POSTGRES_BEFORE=$($DOCKER inspect -f '{{.Id}}' deploy_postgres_1)
REDIS_BEFORE=$($DOCKER inspect -f '{{.Id}}' deploy_redis_1)
OLD_IMAGE=$($DOCKER inspect -f '{{.Image}}' deploy_app_1)
STAMP=$(date '+%Y%m%d-%H%M%S')
ROLLBACK_TAG="classroom-dashboard:rollback-github-$STAMP"
NEW_TAG="classroom-dashboard:github-$STAMP"
JAR_BACKUP="backups/app.jar.pre-github-$STAMP"
DB_BACKUP="data/backups/postgres/pre-github-$STAMP.dump"

mkdir -p backups data/backups/postgres
$DOCKER tag "$OLD_IMAGE" "$ROLLBACK_TAG"
cp -p app.jar "$JAR_BACKUP"
$DOCKER exec deploy_postgres_1 sh -c \
    'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    > "$DB_BACKUP"
test -s "$DB_BACKUP"
mv -f "$UPLOAD" app.jar

restore_previous() {
    cp -pf "$JAR_BACKUP" app.jar || true
    $DOCKER tag "$ROLLBACK_TAG" classroom-dashboard:latest || true
    compose up -d --no-deps --force-recreate app || true
}

if ! $DOCKER build --build-arg "BASE_IMAGE=$ROLLBACK_TAG" -t "$NEW_TAG" -f Dockerfile.jar-overlay .; then
    restore_previous
    exit 3
fi

NEW_IMAGE=$($DOCKER image inspect -f '{{.Id}}' "$NEW_TAG")
$DOCKER tag "$NEW_TAG" classroom-dashboard:latest
if ! compose up -d --no-deps --force-recreate app; then
    restore_previous
    exit 4
fi

HEALTH='starting'
ATTEMPT=0
while [ "$ATTEMPT" -lt 60 ]; do
    HEALTH=$($DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' deploy_app_1 2>/dev/null || true)
    [ "$HEALTH" = 'healthy' ] && break
    ATTEMPT=$((ATTEMPT + 1))
    sleep 2
done

if [ "$HEALTH" != 'healthy' ]; then
    $DOCKER logs --tail 160 deploy_app_1 >&2 || true
    restore_previous
    exit 5
fi

APP_AFTER=$($DOCKER inspect -f '{{.Id}}' deploy_app_1)
APP_IMAGE_AFTER=$($DOCKER inspect -f '{{.Image}}' deploy_app_1)
POSTGRES_AFTER=$($DOCKER inspect -f '{{.Id}}' deploy_postgres_1)
REDIS_AFTER=$($DOCKER inspect -f '{{.Id}}' deploy_redis_1)
if [ "$APP_IMAGE_AFTER" != "$NEW_IMAGE" ] || \
   [ "$POSTGRES_BEFORE" != "$POSTGRES_AFTER" ] || \
   [ "$REDIS_BEFORE" != "$REDIS_AFTER" ]; then
    restore_previous
    exit 6
fi

curl -fsS http://127.0.0.1:8888/actuator/health
printf '\njar_sha=%s\napp_before=%s\napp_after=%s\napp_health=%s\npostgres_unchanged=true\nredis_unchanged=true\nrollback_tag=%s\njar_backup=%s\ndb_backup=%s\n' \
    "$ACTUAL_SHA" "$APP_BEFORE" "$APP_AFTER" "$HEALTH" "$ROLLBACK_TAG" "$JAR_BACKUP" "$DB_BACKUP"
