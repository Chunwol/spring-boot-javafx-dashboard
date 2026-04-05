package com.example.agent.command;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.file.*;
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
                    yield executeShell(cmd);
                }
                case "RUN_PS1"    -> executePs1(taskId, scriptUrl, payload);
                case "WOL_RELAY"  -> executeWolRelay(payload);
                default -> new CommandResult(0, "지원하지 않는 명령: " + type, "");
            };
            persistAndSendResult(taskId, result);
        } catch (Exception e) {
            log.error("명령 실행 실패 taskId={}: {}", taskId, e.getMessage());
            persistAndSendResult(taskId, new CommandResult(1, "", e.getMessage()));
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
            boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new CommandResult(124, "", "명령 실행 타임아웃 (30초)");
            }
            String stdout = new String(proc.getInputStream().readNBytes(OUTPUT_LIMIT));
            String stderr = new String(proc.getErrorStream().readNBytes(OUTPUT_LIMIT));
            return new CommandResult(proc.exitValue(), stdout, stderr);
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

        Path tmpDir = background
            ? Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "pcagent-bg-scripts"))
            : Files.createTempDirectory("pcagent-ps1-");
        Path tmpFile = background
            ? tmpDir.resolve("script-" + taskId + "-" + System.currentTimeMillis() + ".ps1")
            : tmpDir.resolve("script-" + taskId + ".ps1");

        try {
            // 다운로드
            byte[] scriptBytes;
            String source = scriptUrl.trim();
            Path localPath = resolveLocalScriptPath(source);
            if (localPath != null) {
                if (!Files.exists(localPath)) {
                    return new CommandResult(1, "", "로컬 스크립트 파일 없음: " + localPath);
                }
                scriptBytes = Files.readAllBytes(localPath);
            } else {
                String url = resolveRemoteScriptUrl(source);
                Request dlReq = new Request.Builder()
                        .url(url)
                        .header("X-Agent-Token", config.getAgentToken())
                        .get()
                        .build();
                try (Response resp = httpClient.newCall(dlReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        return new CommandResult(1, "", "스크립트 다운로드 실패: HTTP " + resp.code());
                    }
                    scriptBytes = resp.body().bytes();
                    // SHA-256 검증 (서버가 X-Script-SHA256 헤더로 전달)
                    String serverHash = resp.header("X-Script-SHA256");
                    if (serverHash != null && !serverHash.isBlank()) {
                        String localHash = sha256(scriptBytes);
                        if (!localHash.equalsIgnoreCase(serverHash)) {
                            return new CommandResult(1, "", "스크립트 무결성 검증 실패");
                        }
                    }
                }
            }
            Files.write(tmpFile, scriptBytes);

            String args = p.has("args") ? p.get("args").asText() : "";

            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-WindowStyle");
            command.add(visibleWindow ? "Normal" : "Hidden");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(tmpFile.toAbsolutePath().toString());
            if (args != null && !args.isBlank()) {
                command.add(args);
            }

            Process proc = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            if (background) {
                return new CommandResult(0, "백그라운드 실행 시작", "");
            }

            try {
                boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
                if (!finished) {
                    proc.destroyForcibly();
                    return new CommandResult(124, "", "PS1 실행 타임아웃 (5분)");
                }
                String stdout = new String(proc.getInputStream().readNBytes(OUTPUT_LIMIT));
                String stderr = new String(proc.getErrorStream().readNBytes(OUTPUT_LIMIT));
                return new CommandResult(proc.exitValue(), stdout, stderr);
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

    /** 명령 실행 결과 레코드 */
    public record CommandResult(int exitCode, String stdout, String stderr) {}
}
