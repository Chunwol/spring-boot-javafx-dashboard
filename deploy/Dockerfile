# Backend Dockerfile (로컬 빌드 전용)
# build-and-export.bat 이 먼저 gradlew :backend:bootJar 를 실행한 뒤 이 파일로 이미지를 생성함
FROM eclipse-temurin:25-jre

WORKDIR /app

# 로컬에서 빌드된 JAR 복사 (build context = 프로젝트 루트)
COPY backend/build/libs/backend-0.0.1-SNAPSHOT.jar /app/app.jar

RUN mkdir -p /app/uploads

VOLUME ["/app/uploads"]

EXPOSE 8888

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8888/health || exit 1

ENV SERVER_PORT=8888 \
    JAVA_OPTS="-Xmx512m -Xms256m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]
