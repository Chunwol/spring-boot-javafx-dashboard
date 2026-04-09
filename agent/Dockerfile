# Agent Dockerfile
# 멀티스테이지 빌드: 1단계는 빌드, 2단계는 실행
FROM openjdk:17-slim as builder

WORKDIR /build

# Gradle wrapper와 build.gradle 복사
COPY gradlew .
COPY gradlew.bat .
COPY gradle gradle/
COPY settings.gradle .
COPY common common/
COPY agent agent/

# Agent JAR 빌드
RUN chmod +x gradlew && ./gradlew :agent:build -x test

# 실행 스테이지
FROM openjdk:17-slim

WORKDIR /app

# 빌드 스테이지에서 생성된 JAR 복사
COPY --from=builder /build/agent/build/libs/agent-1.0.0.jar /app/agent.jar

# 에이전트는 아웃바운드 연결만 수행하므로 EXPOSE가 필요 없다.

# JVM 옵션
ENV JAVA_OPTS="-Xmx256m -Xms128m"

# Agent 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/agent.jar"]
