package com.example.backend.service.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 데이터베이스 정기 백업 스케줄러 서비스.
 *
 * <p>동작 흐름:
 * <ol>
 *   <li>스케줄(기본 매일 01:00 KST)에 따라 {@link #backupDaily()} 호출</li>
 *   <li>{@code pg_try_advisory_lock}으로 다중 인스턴스 환경에서 중복 실행 방지</li>
 *   <li>{@code pg_dump} 외부 프로세스를 실행해 {@code .dump} 파일 생성</li>
 *   <li>설정된 보관 기간({@code app.backup.keep-days}) 이전 파일을 자동 삭제</li>
 * </ol>
 *
 * <p>설정 프로퍼티: {@code app.backup.*} (application.yml 참고)
 *
 * <p><b>보안 주의</b>: {@code PGPASSWORD} 환경 변수로 비밀번호를 자식 프로세스에 전달한다.
 * JVM 내 메모리에만 존재하며 명령행 인수로 노출되지 않는다. 프로세스 격리 환경에서 사용 권장.
 */
@Service
public class DatabaseBackupSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupSchedulerService.class);
    private static final Pattern PG_JDBC_PATTERN = Pattern.compile(
            "^jdbc:postgresql://(?<host>[^:/?]+)(:(?<port>\\d+))?/(?<db>[^?\\s]+).*$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.backup.enabled:true}")
    private boolean backupEnabled;

    @Value("${app.backup.dir:./backups/postgres}")
    private String backupDir;

    @Value("${app.backup.keep-days:14}")
    private int keepDays;

    @Value("${app.backup.pg-dump-path:pg_dump}")
    private String pgDumpPath;

    @Value("${app.backup.timeout-minutes:30}")
    private long backupTimeoutMinutes;

    @Value("${app.backup.lock-key:94503177}")
    private long advisoryLockKey;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    public DatabaseBackupSchedulerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 설정된 cron 일정에 따라 자동으로 백업을 실행한다.
     * 기본: 매일 01:00 (Asia/Seoul)
     */
    @Scheduled(cron = "${app.backup.cron:0 0 1 * * *}", zone = "${app.backup.zone:Asia/Seoul}")
    public void backupDaily() {
        runBackup("scheduled");
    }

    /**
     * 백업을 즉시 실행한다.
     *
     * <p>{@code synchronized} 키워드로 동일 JVM 내 동시 실행을 방지한다.
     * 멀티 인스턴스 환경에서는 PostgreSQL advisory lock으로 추가 보호한다.
     *
     * <p><b>보안 주의</b>: DB 비밀번호를 {@code PGPASSWORD} 환경 변수로 자식 프로세스에 전달한다.
     * {@code pg_dump}는 이 값을 표준 입력/명령행 대신 환경 변수로 읽는다.
     * 환경 변수 방식은 ps 명령으로 비밀번호가 노출되는 위험을 방지하지만,
     * 공유 호스트 환경에서는 {@code /proc/[pid]/environ} 접근 위험이 있으므로 컨테이너 격리 환경을 권장한다.
     *
     * @param trigger 실행 출처 식별자 (예: "scheduled", "manual")
     */
    public synchronized void runBackup(String trigger) {
        if (!backupEnabled) {
            return;
        }
        if (!isPostgresDatasource()) {
            log.debug("[backup] skip: datasource is not PostgreSQL url={}", datasourceUrl);
            return;
        }

        Boolean lockAcquired = null;
        try {
            lockAcquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)",
                    Boolean.class,
                    advisoryLockKey
            );
            if (!Boolean.TRUE.equals(lockAcquired)) {
                log.info("[backup] skip: advisory lock busy (another node/process is backing up)");
                return;
            }

            JdbcInfo jdbc = parseJdbc(datasourceUrl);
            Path backupBaseDir = resolveBackupDir(backupDir);
            Files.createDirectories(backupBaseDir);

            String filename = jdbc.database + "_" + LocalDateTime.now().format(TS) + ".dump";
            Path backupFile = backupBaseDir.resolve(filename);

            ProcessBuilder pb = new ProcessBuilder(
                    pgDumpPath,
                    "-h", jdbc.host,
                    "-p", String.valueOf(jdbc.port),
                    "-U", datasourceUsername,
                    "-d", jdbc.database,
                    "-F", "c",
                    "--no-owner",
                    "--no-privileges",
                    "-f", backupFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            pb.environment().put("PGPASSWORD", datasourcePassword == null ? "" : datasourcePassword);

            log.info("[backup] start trigger={} path={}", trigger, backupFile.toAbsolutePath());
            Process process = pb.start();
            String processLog = readProcessLog(process);
            boolean finished = process.waitFor(Math.max(1, backupTimeoutMinutes), TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("pg_dump timed out");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IllegalStateException("pg_dump exit=" + exit + " log=" + processLog);
            }

            pruneOldBackups(backupBaseDir, Math.max(1, keepDays));
            log.info("[backup] completed trigger={} file={}", trigger, backupFile.toAbsolutePath());
        } catch (Exception e) {
            log.error("[backup] failed trigger={} reason={}", trigger, e.getMessage(), e);
        } finally {
            if (Boolean.TRUE.equals(lockAcquired)) {
                try {
                    jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, advisoryLockKey);
                } catch (Exception unlockEx) {
                    log.warn("[backup] advisory unlock failed: {}", unlockEx.getMessage());
                }
            }
        }
    }

    /**
     * 현재 datasource URL이 PostgreSQL인지 확인한다.
     * pg_dump는 PostgreSQL 전용이므로 다른 DB 타입에서는 백업을 건너뛴다.
     *
     * @return {@code true}: PostgreSQL JDBC URL
     */
    private boolean isPostgresDatasource() {
        return datasourceUrl != null && datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql://");
    }

    /**
     * PostgreSQL JDBC URL을 파싱하여 호스트/포트/데이터베이스 정보를 추출한다.
     *
     * @param jdbcUrl PostgreSQL JDBC URL
     * @return 파싱된 {@link JdbcInfo}
     * @throws IllegalArgumentException 지원하지 않는 URL 형식인 경우
     */
    private JdbcInfo parseJdbc(String jdbcUrl) {
        Matcher m = PG_JDBC_PATTERN.matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unsupported PostgreSQL JDBC URL: " + jdbcUrl);
        }
        String host = m.group("host");
        String portGroup = m.group("port");
        int port = (portGroup == null || portGroup.isBlank()) ? 5432 : Integer.parseInt(portGroup);
        String database = m.group("db");
        return new JdbcInfo(host, port, database);
    }

    /**
     * 설정된 백업 디렉토리 경로를 절대 경로로 변환한다.
     * 상대 경로인 경우 JVM 실행 디렉토리 기준으로 해석한다.
     *
     * @param configured 설정값 (null/blank 허용)
     * @return 절대 정규화 경로
     */
    private Path resolveBackupDir(String configured) {
        if (configured == null || configured.isBlank()) {
            return Paths.get(".").toAbsolutePath().normalize().resolve("backups").resolve("postgres");
        }
        Path path = Paths.get(configured);
        if (path.isAbsolute()) {
            return path;
        }
        return Paths.get(".").toAbsolutePath().normalize().resolve(path).normalize();
    }

    /**
     * 백업 디렉토리에서 보관 기간({@code keepDaysValue})이 지난 {@code .dump} 파일을 삭제한다.
     * 삭제 실패 시 개별 파일은 경고 로그만 출력하고 나머지 파일 처리를 계속한다.
     *
     * @param dir          백업 파일이 저장된 디렉토리
     * @param keepDaysValue 파일 보관 일수 (최소 1일)
     */
    private void pruneOldBackups(Path dir, int keepDaysValue) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(keepDaysValue);
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".dump"))
                    .forEach(p -> {
                        try {
                            LocalDateTime lastMod = LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(p).toInstant(),
                                    java.time.ZoneId.systemDefault()
                            );
                            if (lastMod.isBefore(cutoff)) {
                                Files.deleteIfExists(p);
                            }
                        } catch (Exception ex) {
                            log.warn("[backup] prune failed file={} reason={}", p, ex.getMessage());
                        }
                    });
        } catch (Exception ex) {
            log.warn("[backup] list/prune failed dir={} reason={}", dir, ex.getMessage());
        }
    }

    /**
     * 프로세스의 표준 출력(stderr 포함)을 읽어 문자열로 반환한다.
     * 최대 4000자까지만 기록하여 메모리 과다 사용을 방지한다.
     *
     * @param process 실행 중인 프로세스
     * @return 프로세스 출력 문자열 (최대 4000자)
     */
    private String readProcessLog(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() < 4000) {
                    sb.append(line).append(System.lineSeparator());
                }
            }
        } catch (Exception ignored) {
            // ignore: best-effort logging only
        }
        return sb.toString();
    }

    private record JdbcInfo(String host, int port, String database) {
    }
}

