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

    @Scheduled(cron = "${app.backup.cron:0 0 1 * * *}", zone = "${app.backup.zone:Asia/Seoul}")
    public void backupDaily() {
        runBackup("scheduled");
    }

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

    private boolean isPostgresDatasource() {
        return datasourceUrl != null && datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql://");
    }

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

