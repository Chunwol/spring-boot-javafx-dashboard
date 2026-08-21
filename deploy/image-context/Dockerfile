FROM eclipse-temurin:25-jre

WORKDIR /app

RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends postgresql-client curl wget fonts-nanum fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/uploads /app/backups/postgres

COPY app.jar /app/app.jar

VOLUME ["/app/uploads"]
EXPOSE 8888

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8888/actuator/health || exit 1

ENV TZ=Asia/Seoul \
    SERVER_PORT=8888 \
    JAVA_OPTS="-Duser.timezone=Asia/Seoul -Xms512m -Xmx1g"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]
