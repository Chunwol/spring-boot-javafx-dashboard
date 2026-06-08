# Backend Dockerfile
# 멀티스테이지 빌드: 1단계는 빌드, 2단계는 실행
FROM eclipse-temurin:25-jdk as builder

WORKDIR /build

# Gradle wrapper와 build.gradle 복사
COPY gradlew .
COPY gradle gradle/
COPY settings-backend.gradle settings.gradle
COPY common common/
COPY backend backend/

# Backend Boot JAR 빌드
RUN chmod +x gradlew && ./gradlew :backend:bootJar -x test

# 실행 스테이지
FROM eclipse-temurin:25-jre

WORKDIR /app

# PDF 보고서 한글 렌더링용 나눔고딕 폰트 설치 (OFL 라이선스)
# ReportService가 /usr/share/fonts/truetype/nanum/NanumGothic.ttf 를 로드함
RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends fonts-nanum fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

# 빌드 스테이지에서 생성된 Boot JAR 복사
COPY --from=builder /build/backend/build/libs/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# 업로드 디렉토리 생성
RUN mkdir -p /app/uploads

# 업로드 디렉토리 볼륨
VOLUME ["/app/uploads"]

# 내부 포트 (Nginx를 통해 접근, 외부에 직접 노출 안됨)
EXPOSE 8888

# 환경 변수 기본값들
ENV SERVER_PORT=8888 \
    JAVA_OPTS="-Xmx512m -Xms256m"

# Backend 실행
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]
