package com.example.agent.command;

import com.example.agent.config.AgentConfig;
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
import java.util.concurrent.TimeUnit;

/**
 * 서버에서 전달받은 명령을 실행한다.
 * 지원 명령: SHUTDOWN, REBOOT, RUN_CMD, RUN_PS1, WOL_RELAY
 */
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);
    private static final int OUTPUT_LIMIT = 4 * 1024; // 4KB

    private final AgentConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public CommandExecutor(AgentConfig config, OkHttpClient httpClient, ObjectMapper mapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.mapper = mapper;
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
            sendResult(taskId, result);
        } catch (Exception e) {
            log.error("명령 실행 실패 taskId={}: {}", taskId, e.getMessage());
            sendResult(taskId, new CommandResult(1, "", e.getMessage()));
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
            httpClient.newCall(req).execute().close();
        } catch (Exception e) {
            log.warn("ACK 전송 실패 taskId={}: {}", taskId, e.getMessage());
        }
    }

    // ─── Result ─────────────────────────────────────────────────────────

    private void sendResult(long taskId, CommandResult result) {
        try {
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "exitCode", result.exitCode(),
                    "stdout",   truncate(result.stdout()),
                    "stderr",   truncate(result.stderr())
            ));
            Request req = new Request.Builder()
                    .url(config.getServerUrl() + "/api/v1/agent/commands/" + taskId + "/result")
                    .header("X-Agent-Token", config.getAgentToken())
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                log.info("결과 보고 taskId={} HTTP={}", taskId, resp.code());
            }
        } catch (Exception e) {
            log.error("결과 보고 실패 taskId={}: {}", taskId, e.getMessage());
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

        // 절대 URL이 아닌 경우 서버 base URL 붙임
        String url = scriptUrl.startsWith("http") ? scriptUrl
                : config.getServerUrl() + scriptUrl;

        Path tmpDir = Files.createTempDirectory("pcagent-ps1-");
        Path tmpFile = tmpDir.resolve("script-" + taskId + ".ps1");

        try {
            // 다운로드
            byte[] scriptBytes;
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
            Files.write(tmpFile, scriptBytes);

            // payload에서 args 추출
            JsonNode p = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
            String args = p.has("args") ? p.get("args").asText() : "";

            Process proc = new ProcessBuilder(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-Scope", "Process",
                    "-File", tmpFile.toAbsolutePath().toString(),
                    args
            ).redirectErrorStream(false).start();

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
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            try { Files.deleteIfExists(tmpDir); } catch (IOException ignored) {}
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

    /** 명령 실행 결과 레코드 */
    public record CommandResult(int exitCode, String stdout, String stderr) {}
}
