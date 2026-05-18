package com.example.agent.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * GitHub Releases API 기반 OTA 자가 업데이트.
 *
 * config.json 에 githubOwner / githubRepo 가 설정된 경우에만 동작한다.
 * 새 버전 감지 → 다운로드 → SHA256 검증(sha256.txt 제공 시) → 백업 → 교체 → 재시작.
 * AgentMain에서 10번째 하트비트마다 checkAndUpdate()를 호출한다.
 */
public class OtaUpdater {

    private static final Logger log = LoggerFactory.getLogger(OtaUpdater.class);
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final String githubOwner;
    private final String githubRepo;
    private final String currentVersion;

    public OtaUpdater(OkHttpClient httpClient, ObjectMapper mapper,
                      String githubOwner, String githubRepo) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.githubOwner = githubOwner != null ? githubOwner : "";
        this.githubRepo = githubRepo != null ? githubRepo : "";
        this.currentVersion = resolveCurrentVersion();
    }

    OtaUpdater(OkHttpClient httpClient, ObjectMapper mapper,
               String githubOwner, String githubRepo,
               String currentVersionOverride) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.githubOwner = githubOwner != null ? githubOwner : "";
        this.githubRepo = githubRepo != null ? githubRepo : "";
        this.currentVersion = sanitizeVersion(currentVersionOverride);
    }

    /**
     * 업데이트 확인 및 적용. 새 버전이 없으면 즉시 반환.
     */
    public void checkAndUpdate() {
        if (githubOwner.isBlank() || githubRepo.isBlank()) {
            log.debug("OTA: githubOwner/githubRepo 미설정 → 건너뜀");
            return;
        }

        try {
            ReleaseInfo release = fetchLatestRelease();
            if (release == null) {
                log.debug("OTA: 릴리즈 정보 조회 실패");
                return;
            }

            if (!isNewer(release.tagName, currentVersion)) {
                log.info("OTA: 현재 버전 {} 이 최신입니다.", currentVersion);
                return;
            }

            log.info("OTA: 새 버전 {} 발견 (현재 {}), 업데이트 시작...", release.tagName, currentVersion);

            if (release.downloadUrl == null) {
                log.warn("OTA: 다운로드 URL 없음 → 업데이트 취소");
                return;
            }

            Path selfPath = getSelfPath();
            Path parentDir = selfPath.getParent();
            Path newFile = parentDir.resolve("agent_new" + getExtension(release.downloadUrl));

            log.info("OTA: {} → {}", release.downloadUrl, newFile);
            downloadFile(release.downloadUrl, newFile);

            // SHA256 검증 (sha256 제공 시)
            if (release.sha256Url != null) {
                String expectedHash = fetchTextContent(release.sha256Url).trim().split("\\s+")[0];
                String actualHash = sha256Hex(newFile);
                if (!expectedHash.equalsIgnoreCase(actualHash)) {
                    log.error("OTA SHA256 불일치: expected={} actual={}", expectedHash, actualHash);
                    Files.deleteIfExists(newFile);
                    return;
                }
                log.info("OTA: SHA256 검증 통과");
            }

            // 현재 실행 파일 백업
            Path backup = parentDir.resolve("agent.bak");
            Files.copy(selfPath, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("OTA: 백업 완료 → {}", backup);

            // 교체 후 재시작 (지연 실행)
            // Windows: 파일 교체만 하면 워치독(agent-watchdog.ps1)이 자동으로 재시작해 줌
            // Linux: systemctl restart
            // ProcessBuilder 사용으로 명령 인젝션 방지 (경로를 개별 인자로 전달)
            String osName = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (osName.contains("win")) {
                // Windows: 임시 스크립트 파일 + 인자 전달로 경로 문자열 인젝션/이스케이프 문제를 제거한다.
                pb = buildWindowsSwapProcess(newFile, selfPath);
            } else {
                pb = new ProcessBuilder(
                        "sh", "-c",
                        "sleep 3 && mv -- \"$1\" \"$2\" && systemctl restart pcagent",
                        "ota-update",
                        newFile.toAbsolutePath().toString(),
                        selfPath.toAbsolutePath().toString()
                );
            }

            // 업데이트 프로세스를 백그라운드로 시작하고 즉시 종료
            // (지연 시작이므로 현재 프로세스 종료 후에 파일 교체됨)
            pb.inheritIO();
            pb.start();
            log.info("OTA: 업데이트 프로세스 시작됨. 현재 에이전트 종료 중...");
            System.exit(0);

        } catch (Exception e) {
            log.warn("OTA 업데이트 중 오류 (무시하고 계속): {}", e.getMessage());
        }
    }

    // ── 내부 메서드 ───────────────────────────────────────────────────────

    private ReleaseInfo fetchLatestRelease() throws IOException {
        String url = "https://api.github.com/repos/" + githubOwner + "/" + githubRepo + "/releases/latest";
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PCAgent/" + currentVersion)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JsonNode root = mapper.readTree(resp.body().string());

            String tag = root.path("tag_name").asText(null);
            if (tag == null) return null;

            ReleaseInfo info = new ReleaseInfo();
            info.tagName = tag;

            // assets에서 .jar/.exe 파일 URL 및 .sha256 URL 찾기
            for (JsonNode asset : root.path("assets")) {
                String name = asset.path("name").asText("");
                String dlUrl = asset.path("browser_download_url").asText(null);
                if (dlUrl == null) continue;

                if (name.endsWith(".jar") || name.endsWith(".exe")) {
                    if (info.downloadUrl == null) info.downloadUrl = dlUrl;
                } else if (name.endsWith(".sha256") || name.endsWith(".sha256.txt")) {
                    info.sha256Url = dlUrl;
                }
            }
            return info;
        }
    }

    private void downloadFile(String url, Path dest) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "PCAgent/" + currentVersion)
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null)
                throw new IOException("다운로드 실패: HTTP " + resp.code());
            try (InputStream is = resp.body().byteStream();
                 OutputStream os = Files.newOutputStream(dest)) {
                is.transferTo(os);
            }
        }
    }

    private String fetchTextContent(String url) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "PCAgent/" + currentVersion).build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return "";
            return resp.body().string();
        }
    }

    private ProcessBuilder buildWindowsSwapProcess(Path source, Path target) throws IOException {
        Path script = Files.createTempFile("pcagent-ota-swap-", ".ps1");
        String scriptBody = """
                param(
                  [Parameter(Mandatory=$true)][string]$Source,
                  [Parameter(Mandatory=$true)][string]$Target
                )
                Start-Sleep -Seconds 3
                Move-Item -LiteralPath $Source -Destination $Target -Force
                """;
        Files.writeString(script, scriptBody, StandardCharsets.UTF_8);
        script.toFile().deleteOnExit();

        return new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toAbsolutePath().toString(),
                source.toAbsolutePath().toString(),
                target.toAbsolutePath().toString()
        );
    }

    private String resolveCurrentVersion() {
        String systemValue = System.getProperty("pcagent.version");
        if (systemValue != null && !systemValue.isBlank()) {
            return sanitizeVersion(systemValue);
        }

        String envValue = System.getenv("AGENT_VERSION");
        if (envValue != null && !envValue.isBlank()) {
            return sanitizeVersion(envValue);
        }

        try {
            Package pkg = OtaUpdater.class.getPackage();
            if (pkg != null) {
                String implementationVersion = pkg.getImplementationVersion();
                if (implementationVersion != null && !implementationVersion.isBlank()) {
                    return sanitizeVersion(implementationVersion);
                }
            }
        } catch (Exception ignored) {
        }

        return "dev";
    }

    static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "dev";
        }
        return version.trim();
    }

    String currentVersion() {
        return currentVersion;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Semantic version 비교: latest > current 이면 true */
    static boolean isNewer(String latest, String current) {
        String l = latest.replaceFirst("^[vV]", "");
        String c = current.replaceFirst("^[vV]", "");
        String[] lp = l.split("\\.");
        String[] cp = c.split("\\.");
        int len = Math.max(lp.length, cp.length);
        for (int i = 0; i < len; i++) {
            int lv = i < lp.length ? parseIntSafe(lp[i]) : 0;
            int cv = i < cp.length ? parseIntSafe(cp[i]) : 0;
            if (lv > cv) return true;
            if (lv < cv) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String getExtension(String url) {
        if (url.endsWith(".exe")) return ".exe";
        return ".jar";
    }

    private static Path getSelfPath() throws Exception {
        // GraalVM native image / JVM 공통 방법
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd != null) return Path.of(cmd);
        // fallback: JAR mode
        return Path.of(OtaUpdater.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
    }

    private static class ReleaseInfo {
        String tagName;
        String downloadUrl;
        String sha256Url;
    }
}
