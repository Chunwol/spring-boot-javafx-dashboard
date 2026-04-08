package com.example.agent.command;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.agent.util.ZipUtil;

import com.example.agent.ui.FileTransferProgressDialog;
import com.example.agent.util.ProcessOutputReader;
import com.example.agent.util.SensitiveDataMasker;
import com.example.agent.util.UiThreadUtils;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 서버에서 전달받은 명령을 실행한다.
 * 지원 명령: SHUTDOWN, REBOOT, RUN_CMD, RUN_PS1, WOL_RELAY
 */
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);
    private static final int OUTPUT_LIMIT = 4 * 1024; // 4KB
    private static final String RUN_MODE_BACKGROUND = "BACKGROUND";
    private static final String WINDOW_MODE_VISIBLE = "VISIBLE";

    private final AgentConfig config;
    private final AgentConfigLoader configLoader;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final PendingCommandResultStore pendingResultStore;

    public CommandExecutor(AgentConfig config,
                           AgentConfigLoader configLoader,
                           OkHttpClient httpClient,
                           ObjectMapper mapper,
                           PendingCommandResultStore pendingResultStore) {
        this.config = config;
        this.configLoader = configLoader;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.pendingResultStore = pendingResultStore;
    }

    /**
     * 명령 맵을 받아 처리하고 결과를 서버에 보고한다.
     * @param cmdNode 하트비트 응답의 command 객체
     */
    public void execute(JsonNode cmdNode) {
        if (cmdNode == null || cmdNode.isNull()) return;

        long taskId = cmdNode.get("taskId").asLong();
        String type = cmdNode.get("type").asText();
        String payload = cmdNode.has("payload") && !cmdNode.get("payload").isNull()
                ? cmdNode.get("payload").asText() : "{}";
        String scriptUrl = cmdNode.has("scriptUrl") && !cmdNode.get("scriptUrl").isNull()
                ? cmdNode.get("scriptUrl").asText() : null;

        log.info("명령 수신 taskId={} type={}", taskId, type);
        sendAck(taskId);

        try {
            CommandResult result = switch (type) {
                case "SHUTDOWN"   -> executeShell("shutdown /s /t 10 /c \"원격 종료 명령\"");
                case "REBOOT"     -> executeShell("shutdown /r /t 10");
                case "RUN_CMD"    -> {
                    JsonNode p = mapper.readTree(payload);
                    String cmd = p.has("cmd") ? p.get("cmd").asText() : payload;
                    // MEDIUM FIX#9: Mask sensitive command details in logs
                    log.debug("명령 실행 taskId={} cmd={}", taskId, SensitiveDataMasker.maskCommand(cmd));
                    yield executeShell(cmd);
                }
                case "RUN_PS1"    -> {
                    // Log masked URL to prevent path/token disclosure
                    log.debug("PS1 실행 taskId={} scriptUrl={}", taskId, SensitiveDataMasker.maskPath(scriptUrl));
                    yield executePs1(taskId, scriptUrl, payload);
                }
                case "WOL_RELAY"  -> executeWolRelay(payload);
                default -> new CommandResult(0, "지원하지 않는 명령: " + type, "");
            };
            persistAndSendResult(taskId, result);
        } catch (Exception e) {
            // MEDIUM FIX#9: Mask sensitive data in error messages
            String maskedMsg = SensitiveDataMasker.maskExceptionMessage(e.getMessage());
            log.error("명령 실행 실패 taskId={}: {}", taskId, maskedMsg);
            persistAndSendResult(taskId, new CommandResult(1, "", maskedMsg));
        }
    }

    public void flushPendingResults() {
        if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
            return;
        }
        List<PendingCommandResultStore.PendingCommandResult> pendingResults = pendingResultStore.findAll();
        for (PendingCommandResultStore.PendingCommandResult pending : pendingResults) {
            boolean delivered = sendStoredResult(pending);
            if (!delivered) {
                break;
            }
        }
    }

    // ─── ACK ────────────────────────────────────────────────────────────

    private void sendAck(long taskId) {
        try {
            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/agent/commands/" + taskId + "/ack")
                    .header("X-Agent-Token", config.getAgentToken())
                    .post(RequestBody.create("{}", MediaType.parse("application/json")))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("ACK 전송 응답 비정상 taskId={} HTTP={}", taskId, resp.code());
                    if (resp.code() == 401 || resp.code() == 403) {
                        handleAuthFailure("ACK", taskId, resp.code());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ACK 전송 실패 taskId={}: {}", taskId, e.getMessage());
        }
    }

    // ─── Result ─────────────────────────────────────────────────────────

    private void persistAndSendResult(long taskId, CommandResult result) {
        CommandResult sanitized = new CommandResult(
                result.exitCode(),
                truncate(result.stdout()),
                truncate(result.stderr())
        );
        pendingResultStore.upsert(taskId, sanitized);
        sendStoredResult(new PendingCommandResultStore.PendingCommandResult(
                taskId,
                sanitized.exitCode(),
                sanitized.stdout(),
                sanitized.stderr(),
                java.time.LocalDateTime.now()
        ));
    }

    private boolean sendStoredResult(PendingCommandResultStore.PendingCommandResult pending) {
        long taskId = pending.taskId();
        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "exitCode", pending.exitCode(),
                    "stdout", pending.stdout(),
                    "stderr", pending.stderr()
            ));
            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/agent/commands/" + taskId + "/result")
                    .header("X-Agent-Token", config.getAgentToken())
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    log.info("결과 보고 taskId={} HTTP={}", taskId, resp.code());
                    pendingResultStore.remove(taskId);
                    return true;
                } else {
                    log.warn("결과 보고 실패 taskId={} HTTP={}", taskId, resp.code());
                    if (resp.code() == 401 || resp.code() == 403) {
                        handleAuthFailure("RESULT", taskId, resp.code());
                    }
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("결과 보고 실패 taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }

    // ─── SHELL ──────────────────────────────────────────────────────────

    private CommandResult executeShell(String command) throws IOException, InterruptedException {
        Process proc = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", command});
        try {
            // Process output streams in separate threads to prevent deadlock
            ProcessOutputReader stdoutReader = new ProcessOutputReader(proc.getInputStream(), OUTPUT_LIMIT);
            ProcessOutputReader stderrReader = new ProcessOutputReader(proc.getErrorStream(), OUTPUT_LIMIT);
            stdoutReader.start();
            stderrReader.start();

            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new CommandResult(124, "", "명령 실행 타임아웃 (30초)");
            }

            // Wait for stream readers to complete
            try {
                stdoutReader.waitForCompletion(1000);
                stderrReader.waitForCompletion(1000);
            } catch (InterruptedException ignored) {}

            return new CommandResult(proc.exitValue(), stdoutReader.getOutput(), stderrReader.getOutput());
        } finally {
            proc.destroyForcibly();
        }
    }

    // ─── PS1 ─────────────────────────────────────────────────────────────

    private CommandResult executePs1(long taskId, String scriptUrl, String payloadJson)
            throws Exception {
        if (scriptUrl == null || scriptUrl.isBlank()) {
            return new CommandResult(1, "", "scriptUrl 누락");
        }

        JsonNode p = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        String runMode = p.has("runMode") ? p.get("runMode").asText("FOREGROUND") : "FOREGROUND";
        boolean background = RUN_MODE_BACKGROUND.equalsIgnoreCase(runMode);
        String windowMode = p.has("windowMode") ? p.get("windowMode").asText("HIDDEN") : "HIDDEN";
        boolean visibleWindow = WINDOW_MODE_VISIBLE.equalsIgnoreCase(windowMode);
        boolean runAfterTransfer = p.has("runAfterTransfer") && p.get("runAfterTransfer").asBoolean(false);

        String source = scriptUrl.trim();
        String ext = source.toLowerCase().endsWith(".zip") ? ".zip" : ".ps1";
        Path tmpDir = background
                ? Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "pcagent-bg-scripts"))
                : Files.createTempDirectory("pcagent-ps1-");

        // MEDIUM FIX: Restrict temporary directory permissions on Unix systems
        restrictTempDirPermissions(tmpDir);

        Path tmpFile = background
                ? tmpDir.resolve("script-" + taskId + "-" + System.currentTimeMillis() + ext)
                : tmpDir.resolve("script-" + taskId + ext);

        try {
            // 다운로드
            byte[] scriptBytes;
            Path localPath = resolveLocalScriptPath(source);
            if (localPath != null) {
                if (!Files.exists(localPath)) {
                    // MEDIUM FIX#9: Mask file path to hide directory structure
                    return new CommandResult(1, "", "로컬 스크립트 파일 없음: " + SensitiveDataMasker.maskPath(localPath.toString()));
                }
                scriptBytes = Files.readAllBytes(localPath);
            } else {
                String url = resolveRemoteScriptUrl(source);
                // MEDIUM FIX#9: Log masked URL path (without revealing full URL)
                log.debug("PS1 다운로드 taskId={} url={}", taskId, SensitiveDataMasker.maskPath(url));
                boolean[] cancelled = {false};
                FileTransferProgressDialog dialog = new FileTransferProgressDialog(
                        tmpFile.getFileName().toString(),
                        -1L,
                        () -> cancelled[0] = true
                );
                // MEDIUM FIX#8: Ensure UI operation on proper thread
                UiThreadUtils.runOnSwingThread(dialog::show);
                long[] downloaded = {0};
                long contentLength = -1;

                Request dlReq = new Request.Builder()
                        .url(url)
                        .header("X-Agent-Token", config.getAgentToken())
                        .get()
                        .build();
                try (Response resp = httpClient.newCall(dlReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        dialog.close();
                        return new CommandResult(1, "", "스크립트 다운로드 실패: HTTP " + resp.code());
                    }
                    contentLength = resp.body().contentLength();
                    dialog.setStatus("전송중입니다...");
                    dialog.setEta("계산중...");

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    long start = System.currentTimeMillis();
                    try (InputStream in = resp.body().byteStream()) {
                        while ((r = in.read(buf)) > 0) {
                            if (cancelled[0]) {
                                dialog.close();
                                baos.close();  // CRITICAL FIX: Explicitly close stream on cancel
                                return new CommandResult(2, "", "사용자 취소");
                            }
                            baos.write(buf, 0, r);
                            downloaded[0] += r;
                            double progress = contentLength > 0 ? (double) downloaded[0] / contentLength : -1;
                            long elapsed = System.currentTimeMillis() - start;
                            double speed = elapsed > 0 ? (downloaded[0] * 1000.0 / elapsed) : 0;
                            long remain = (speed > 0 && contentLength > 0)
                                    ? (long) ((contentLength - downloaded[0]) / speed)
                                    : -1;
                            dialog.setProgress(progress);
                            dialog.setEta(remain > 0 ? remain + "초" : "-");
                        }
                    }
                    scriptBytes = baos.toByteArray();
                    baos.close();  // Ensure close on normal completion

                    // SHA-256 검증 (서버가 X-Script-SHA256 헤더로 전달)
                    String serverHash = resp.header("X-Script-SHA256");
                    if (serverHash != null && !serverHash.isBlank()) {
                        String localHash = sha256(scriptBytes);
                        if (!localHash.equalsIgnoreCase(serverHash)) {
                            dialog.close();
                            return new CommandResult(1, "", "스크립트 무결성 검증 실패");
                        }
                    }
                    dialog.close();
                }
            }

            Files.write(tmpFile, scriptBytes);

            boolean transferMode = p.has("transferMode") && p.get("transferMode").asBoolean(false);
            String sourceType = p.has("sourceType") ? p.get("sourceType").asText("file") : "file";
            String displayName = p.has("displayName") ? p.get("displayName").asText(tmpFile.getFileName().toString()) : tmpFile.getFileName().toString();
            String destinationMode = p.has("destinationMode") ? p.get("destinationMode").asText("DOWNLOADS") : "DOWNLOADS";
            String destinationPath = p.has("destinationPath") ? p.get("destinationPath").asText("") : "";

            if (transferMode) {
                Path targetBaseDir = resolveTransferDestination(destinationMode, destinationPath);
                Files.createDirectories(targetBaseDir);

                if ("folder".equalsIgnoreCase(sourceType) || tmpFile.toString().toLowerCase().endsWith(".zip")) {
                    String folderName = sanitizeName(displayName == null || displayName.isBlank() ? "transfer-folder" : displayName);
                    Path targetFolder = targetBaseDir.resolve(folderName);
                    Files.createDirectories(targetFolder);
                    ZipUtil.unzip(tmpFile, targetFolder);

                    if (runAfterTransfer) {
                        Path toRun = findFirstRunnableFile(targetFolder);
                        if (toRun != null) {
                            return executeShellOrPs1(toRun);
                        }
                    }
                    return new CommandResult(0, "폴더 전송 완료: " + targetFolder, "");
                }

                String targetName = sanitizeName(displayName == null || displayName.isBlank() ? tmpFile.getFileName().toString() : displayName);
                Path targetFile = targetBaseDir.resolve(targetName);
                Files.copy(tmpFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                if (runAfterTransfer) {
                    return executeShellOrPs1(targetFile);
                }
                return new CommandResult(0, "파일 전송 완료: " + targetFile, "");
            }

            // 기존 스크립트 실행 경로
            if (tmpFile.toString().toLowerCase().endsWith(".zip")) {
                Path unzipDir = tmpFile.getParent().resolve("unzipped-" + taskId);
                ZipUtil.unzip(tmpFile, unzipDir);
                if (runAfterTransfer) {
                    try {
                        Path toRun = findFirstRunnableFile(unzipDir);
                        if (toRun != null) {
                            return executeShellOrPs1(toRun);
                        } else {
                            return new CommandResult(0, "압축 해제 완료(실행파일 없음)", "");
                        }
                    } catch (Exception e) {
                        return new CommandResult(1, "", "압축 해제 후 실행 실패: " + e.getMessage());
                    }
                } else {
                    return new CommandResult(0, "압축 해제 완료", "");
                }
            }

            List<String> args = parseScriptArgs(p.get("args"));

            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-WindowStyle");
            command.add(visibleWindow ? "Normal" : "Hidden");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(tmpFile.toAbsolutePath().toString());
            if (!args.isEmpty()) {
                command.addAll(args);
            }

            Process proc = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            if (background) {
                // Background process - don't wait for completion
                // Process will continue running independently
                log.info("PS1 백그라운드 실행 시작: {}", tmpFile.getFileName());
                return new CommandResult(0, "백그라운드 실행 시작", "");
            }

            try {
                // Foreground: read output streams asynchronously
                ProcessOutputReader stdoutReader = new ProcessOutputReader(proc.getInputStream(), OUTPUT_LIMIT);
                ProcessOutputReader stderrReader = new ProcessOutputReader(proc.getErrorStream(), OUTPUT_LIMIT);
                stdoutReader.start();
                stderrReader.start();

                boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
                if (!finished) {
                    proc.destroyForcibly();
                    return new CommandResult(124, "", "PS1 실행 타임아웃 (5분)");
                }

                // Wait for stream readers
                try {
                    stdoutReader.waitForCompletion(1000);
                    stderrReader.waitForCompletion(1000);
                } catch (InterruptedException ignored) {}

                return new CommandResult(proc.exitValue(), stdoutReader.getOutput(), stderrReader.getOutput());
            } finally {
                proc.destroyForcibly();
            }
        } finally {
            // 임시 파일 정리 (예외 발생 시에도 보장)
            if (!background) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (IOException ignored) {}
            }
        }
    }

    // zip 해제 후 실행 분기용
    private CommandResult executeShellOrPs1(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase();

        java.util.List<String> command = new java.util.ArrayList<>();
        if (name.endsWith(".ps1")) {
            command.addAll(java.util.Arrays.asList(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    file.toAbsolutePath().toString()
            ));
        } else if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            command.addAll(java.util.Arrays.asList("cmd.exe", "/c", file.toAbsolutePath().toString()));
        } else if (name.endsWith(".exe")) {
            command.add(file.toAbsolutePath().toString());
        } else {
            return new CommandResult(0, "실행파일 아님: " + file, "");
        }

        Process proc = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

        try {
            // Read output streams asynchronously
            ProcessOutputReader stdoutReader = new ProcessOutputReader(proc.getInputStream(), OUTPUT_LIMIT);
            ProcessOutputReader stderrReader = new ProcessOutputReader(proc.getErrorStream(), OUTPUT_LIMIT);
            stdoutReader.start();
            stderrReader.start();

            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                return new CommandResult(124, "", "실행 타임아웃 (5분)");
            }

            // Wait for stream readers
            try {
                stdoutReader.waitForCompletion(1000);
                stderrReader.waitForCompletion(1000);
            } catch (InterruptedException ignored) {}

            return new CommandResult(proc.exitValue(), stdoutReader.getOutput(), stderrReader.getOutput());
        } finally {
            proc.destroyForcibly();
        }
    }

    // ─── WOL RELAY ───────────────────────────────────────────────────────

    private CommandResult executeWolRelay(String payloadJson) throws Exception {
        JsonNode p = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        String broadcastIp = p.has("broadcastIp") ? p.get("broadcastIp").asText() : "255.255.255.255";
        String targetMacsStr = p.has("targetMacs") ? p.get("targetMacs").asText() : "";

        int sent = 0;
        for (String mac : targetMacsStr.split(",")) {
            mac = mac.trim();
            if (mac.length() != 17) continue;
            byte[] macBytes = parseMac(mac);
            sendMagicPacket(macBytes, broadcastIp);
            sent++;
            Thread.sleep(50);
        }
        return new CommandResult(0, "WOL 매직 패킷 " + sent + "개 전송 완료", "");
    }

    private static byte[] parseMac(String mac) {
        String[] parts = mac.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    private static void sendMagicPacket(byte[] macBytes, String broadcastIp) throws Exception {
        byte[] magic = new byte[102];
        for (int i = 0; i < 6; i++) magic[i] = (byte) 0xFF;
        for (int i = 1; i <= 16; i++) System.arraycopy(macBytes, 0, magic, i * 6, 6);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket pkt = new DatagramPacket(
                    magic, magic.length, InetAddress.getByName(broadcastIp), 9);
            socket.send(pkt);
        }
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────

    private static Path resolveTransferDestination(String destinationMode, String destinationPath) {
        String userHome = System.getProperty("user.home", ".");
        String mode = destinationMode == null ? "DOWNLOADS" : destinationMode.toUpperCase();
        return switch (mode) {
            case "DESKTOP" -> Path.of(userHome, "Desktop");
            case "CUSTOM" -> {
                if (destinationPath == null || destinationPath.isBlank()) {
                    yield Path.of(userHome, "Downloads");
                }
                yield Path.of(destinationPath);
            }
            default -> Path.of(userHome, "Downloads");
        };
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "transfer";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static Path findFirstRunnableFile(Path root) {
        try {
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.endsWith(".ps1") || n.endsWith(".bat") || n.endsWith(".cmd") || n.endsWith(".exe");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= OUTPUT_LIMIT ? s : s.substring(0, OUTPUT_LIMIT);
    }

    private void handleAuthFailure(String channel, long taskId, int statusCode) {
        log.warn("{} 인증 실패(taskId={}, HTTP={}) -> 토큰 초기화 후 자동 재등록 유도", channel, taskId, statusCode);
        try {
            configLoader.saveToken(config, "");
        } catch (Exception e) {
            log.warn("토큰 초기화 실패 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private String resolveRemoteScriptUrl(String scriptUrl) {
        if (scriptUrl.startsWith("http://") || scriptUrl.startsWith("https://")) {
            return scriptUrl;
        }
        String base = config.getServerUrl().endsWith("/")
                ? config.getServerUrl().substring(0, config.getServerUrl().length() - 1)
                : config.getServerUrl();
        String path = scriptUrl.startsWith("/") ? scriptUrl : "/" + scriptUrl;
        return base + path;
    }

    private Path resolveLocalScriptPath(String scriptUrl) {
        String s = scriptUrl.trim();
        // Windows 절대경로 (예: D:\\temp\\a.ps1)
        if (s.matches("^[A-Za-z]:\\\\.*")) {
            return Paths.get(s);
        }
        // UNC 경로 (예: \\\\server\\share\\a.ps1)
        if (s.startsWith("\\\\")) {
            return Paths.get(s);
        }
        // file URI
        if (s.startsWith("file:/")) {
            try {
                return Paths.get(URI.create(s));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> parseScriptArgs(JsonNode argsNode) {
        if (argsNode == null || argsNode.isNull()) {
            return List.of();
        }
        if (argsNode.isArray()) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (JsonNode n : argsNode) {
                if (n != null && !n.isNull()) {
                    out.add(n.asText());
                }
            }
            return out;
        }

        String raw = argsNode.asText("").trim();
        if (raw.isBlank()) {
            return List.of();
        }

        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Restrict temporary directory permissions to owner only on Unix systems.
     * On Windows, Files.createTempDirectory() already creates with restricted attributes.
     */
    private static void restrictTempDirPermissions(Path dir) {
        try {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (!osName.contains("win")) {
                // Unix/Linux: Set permissions to rwx------ (0700)
                java.util.Set<PosixFilePermission> perms = java.util.Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                );
                Files.setPosixFilePermissions(dir, perms);
                log.debug("임시 디렉토리 권한 설정: {} -> rwx------", dir);
            }
        } catch (IOException e) {
            log.warn("임시 디렉토리 권한 설정 실패: {} - {}", dir, e.getMessage());
        }
    }

    /** 명령 실행 결과 레코드 */
    public record CommandResult(int exitCode, String stdout, String stderr) {}
}
