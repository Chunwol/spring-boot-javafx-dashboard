# Backend Dockerfile
# 멀티스테이지 빌드: 1단계는 빌드, 2단계는 실행
FROM eclipse-temurin:25-jdk as builder

WORKDIR /build

# Gradle wrapper와 build.gradle 복사
COPY gradlew .
COPY gradle gradle/
COPY settings.gradle .
COPY common common/
COPY backend backend/

# Backend Boot JAR 빌드
RUN chmod +x gradlew && ./gradlew :backend:bootJar -x test

# 실행 스테이지
FROM eclipse-temurin:25-jre

WORKDIR /app

# 빌드 스테이지에서 생성된 Boot JAR 복사
COPY --from=builder /build/backend/build/libs/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# 데이터베이스 및 업로드 디렉토리 생성
RUN mkdir -p /app/db /app/uploads

# 데이터베이스 및 업로드 디렉토리 볼륨
VOLUME ["/app/db", "/app/uploads"]

# 내부 포트 (Nginx를 통해 접근, 외부에 직접 노출 안됨)
EXPOSE 8888

# 헬스체크
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8888/health || exit 1

# 환경 변수 기본값들
ENV SERVER_PORT=8888 \
    JAVA_OPTS="-Xmx512m -Xms256m"

# Backend 실행
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]
