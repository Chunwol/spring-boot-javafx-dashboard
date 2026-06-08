# Backend Dockerfile (로컬 빌드 전용)
# build-and-export.bat 이 먼저 gradlew :backend:bootJar 를 실행한 뒤 이 파일로 이미지를 생성함
FROM eclipse-temurin:25-jre

WORKDIR /app

# 로컬에서 빌드된 JAR 복사 (build context = 프로젝트 루트)
COPY backend/build/libs/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# postgresql-client/curl/wget + PDF 보고서 한글 폰트(나눔고딕, OFL)
RUN apt-get update -qq && apt-get install -y --no-install-recommends postgresql-client curl wget fonts-nanum fontconfig && fc-cache -f && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /app/uploads /app/backups/postgres

VOLUME ["/app/uploads"]

EXPOSE 8888

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8888/actuator/health || exit 1

ENV SERVER_PORT=8888 \
    JAVA_OPTS="-Xmx512m -Xms256m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]
