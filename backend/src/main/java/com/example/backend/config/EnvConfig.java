package com.example.backend.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * .env 파일에서 환경 변수를 로드하는 설정 클래스
 * Spring Boot 애플리케이션 시작 시 .env 파일의 모든 값을 시스템 속성으로 로드합니다.
 */
public class EnvConfig implements EnvironmentPostProcessor {

    private static final Log log = new DeferredLog();
    private static final Pattern HHMM_PATTERN = Pattern.compile("^(?<hour>[01]\\d|2[0-3]):(?<minute>[0-5]\\d)$");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // backend/.env 파일 경로 우선 시도
        String[] searchPaths = {
            "backend/.env",    // 프로젝트 루트에서 backend 폴더
            "backend.env",      // 프로젝트 루트에 backend.env 파일
            ".env"              // 호환성: 기본 .env 파일
        };
        
        Dotenv dotenv = null;
        for (String path : searchPaths) {
            File file = new File(path);
            if (file.exists()) {
                String dir = file.getParent() != null ? file.getParent() : ".";
                String filename = file.getName();
                dotenv = Dotenv.configure()
                        .directory(dir)
                        .filename(filename)
                        .ignoreIfMissing()
                        .load();
                log.info("Loaded backend env from: " + file.getAbsolutePath());
                break;
            }
        }

        Map<String, Object> envProperties = new HashMap<>();
        if (dotenv != null) {
            // .env의 모든 값을 Map으로 변환
            envProperties.putAll(dotenv.entries()
                    .stream()
                    .collect(Collectors.toMap(
                            DotenvEntry::getKey,
                            DotenvEntry::getValue,
                            (existing, replacement) -> replacement
                    )));

            // .env는 실행 환경 변수보다 우선순위를 낮게 둔다 (운영 환경에서 손쉬운 오버라이드 지원).
            MapPropertySource envPropertySource = new MapPropertySource("backend-dotenv", envProperties);
            environment.getPropertySources().addLast(envPropertySource);
        } else {
            log.warn("No backend env file found. Using defaults.");
        }

        Map<String, Object> aliasProperties = new HashMap<>();

        // DB_USERNAME이 비어 있으면 POSTGRES_USER를 자동 사용한다.
        putIfHasText(aliasProperties, "DB_USERNAME",
                firstNonBlank(
                        environment.getProperty("DB_USERNAME"),
                        environment.getProperty("POSTGRES_USER"),
                        asString(envProperties.get("DB_USERNAME")),
                        asString(envProperties.get("POSTGRES_USER"))
                ));

        // DB_PASSWORD가 비어 있으면 POSTGRES_PASSWORD 또는 PGPASSWORD를 자동 사용한다.
        putIfHasText(aliasProperties, "DB_PASSWORD",
                firstNonBlank(
                        environment.getProperty("DB_PASSWORD"),
                        environment.getProperty("POSTGRES_PASSWORD"),
                        environment.getProperty("PGPASSWORD"),
                        asString(envProperties.get("DB_PASSWORD")),
                        asString(envProperties.get("POSTGRES_PASSWORD"))
                ));

        // JDBC 표준 환경 변수도 DB_URL로 호환한다.
        putIfHasText(aliasProperties, "DB_URL",
                firstNonBlank(
                        environment.getProperty("DB_URL"),
                        environment.getProperty("JDBC_DATABASE_URL"),
                        asString(envProperties.get("DB_URL"))
                ));

        // DB_BACKUP_TIME(HH:mm)이 설정되어 있으면 DB_BACKUP_CRON으로 자동 변환한다.
        // 우선순위: DB_BACKUP_TIME(HH:mm 변환) > DB_BACKUP_CRON(직접 지정)
        String backupTime = firstNonBlank(
                environment.getProperty("DB_BACKUP_TIME"),
                asString(envProperties.get("DB_BACKUP_TIME"))
        );
        String backupCron = toDailyCronFromTime(backupTime);
        if (!hasText(backupCron)) {
            if (hasText(backupTime)) {
                log.warn("Invalid DB_BACKUP_TIME format: " + backupTime + " (expected HH:mm)");
            }
            backupCron = firstNonBlank(
                    environment.getProperty("DB_BACKUP_CRON"),
                    asString(envProperties.get("DB_BACKUP_CRON"))
            );
        }
        putIfHasText(aliasProperties, "DB_BACKUP_CRON", backupCron);

        if (!aliasProperties.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("backend-env-aliases", aliasProperties));
        }
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String toDailyCronFromTime(String hhmm) {
        if (!hasText(hhmm)) {
            return null;
        }
        Matcher matcher = HHMM_PATTERN.matcher(hhmm.trim());
        if (!matcher.matches()) {
            return null;
        }
        String hour = matcher.group("hour");
        String minute = matcher.group("minute");
        return "0 " + minute + " " + hour + " * * *";
    }
}
