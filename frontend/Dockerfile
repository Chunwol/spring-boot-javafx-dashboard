# Frontend JavaFX Dockerfile
# 멀티스테이지 빌드: 1단계는 빌드, 2단계는 실행
FROM eclipse-temurin:25-jdk as builder

WORKDIR /build

# Gradle wrapper와 build.gradle 복사
COPY gradlew .
COPY gradlew.bat .
COPY gradle gradle/
COPY settings.gradle .
COPY common common/
COPY frontend frontend/

# Frontend JAR 빌드 (shadowJar 사용)
RUN chmod +x gradlew && ./gradlew :frontend:shadowJar -x test

# 실행 스테이지
FROM eclipse-temurin:25-jre

WORKDIR /app

# 빌드 스테이지에서 생성된 shadowJar 복사
COPY --from=builder /build/frontend/build/libs/dashboard-0.0.1.jar /app/dashboard.jar

# JavaFX를 위한 디스플레이 포트 설정 (호스트 머신의 X11 연결 필요)
ENV DISPLAY=:0

# JVM 옵션 (JavaFX 모듈 명시)
ENV JAVA_OPTS="-Xmx512m -Xms256m --add-modules javafx.controls,javafx.graphics,javafx.fxml,javafx.swing,javafx.web"

# 헬스체크 (대시보드 프로세스 확인)
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ps aux | grep dashboard.jar | grep -v grep || exit 1

# Frontend 실행
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/dashboard.jar"]
