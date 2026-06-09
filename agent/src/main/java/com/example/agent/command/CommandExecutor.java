package com.example.agent.command;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.update.UpdateCacheServer;
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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.filechooser.FileSystemView;

/**
 * 서버에서 전달받은 명령을 실행한다.
 * 지원 명령: SHUTDOWN, REBOOT, RUN_CMD, RUN_PS1, WOL_RELAY, COLLECT_FILES, LIST_FILES, AGENT_UPDATE
 */
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);
    private static final int OUTPUT_LIMIT = 4 * 1024;   // 4KB (shell 명령 결과용)
    private static final int OUTPUT_LIMIT_JSON = 2 * 1024 * 1024; // 2MB (LIST_FILES JSON용)
    private static final String RUN_MODE_BACKGROUND = "BACKGROUND";
    private static final String WINDOW_MODE_VISIBLE = "VISIBLE";
    private static final int LIST_FILES_DEFAULT_LIMIT = 120;
    private static final int LIST_FILES_MAX_LIMIT = 400;
    private static final int LIST_FILES_MAX_SCAN_ENTRIES = 5000;
    private static final int LIST_FILES_WARNING_PREVIEW = 20;
    private static final long LIST_FILES_SCAN_TIME_LIMIT_MS = 12_000L;
    private static final int COLLECT_UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int COLLECT_UPLOAD_MAX_RETRY = 3;
    private static final long COLLECT_UPLOAD_RETRY_BACKOFF_MS = 350L;
    private static final long AGENT_UPDATE_EXIT_DELAY_MS = 4_000L;
    private static final int AGENT_UPDATE_DOWNLOAD_BUFFER_SIZE = 64 * 1024;
    private static final long AGENT_UPDATE_DOWNLOAD_CHUNK_SIZE = 8L * 1024 * 1024;
    private static final int AGENT_UPDATE_DOWNLOAD_MAX_RETRY = 3;

    private final AgentConfig config;
    private final AgentConfigLoader configLoader;
    private final OkHttpClient httpClient;
    private final OkHttpClient downloadClient; // 대용량 파일 전용 (타임아웃 완화)
    private final ObjectMapper mapper;
    private final PendingCommandResultStore pendingResultStore;
    private final UpdateCacheServer updateCacheServer;

    public CommandExecutor(AgentConfig config,
                           AgentConfigLoader configLoader,
                           OkHttpClient httpClient,
                           ObjectMapper mapper,
                           PendingCommandResultStore pendingResultStore) {
        this(config, configLoader, httpClient, mapper, pendingResultStore, null);
    }

    public CommandExecutor(AgentConfig config,
                           AgentConfigLoader configLoader,
                           OkHttpClient httpClient,
                           ObjectMapper mapper,
                           PendingCommandResultStore pendingResultStore,
                           UpdateCacheServer updateCacheServer) {
        this.config = config;
        this.configLoader = configLoader;
        this.httpClient = httpClient;
        this.downloadClient = httpClient.newBuilder()
                .readTimeout(10, TimeUnit.MINUTES)
                .callTimeout(30, TimeUnit.MINUTES)
                .build();
        this.mapper = mapper;
        this.pendingResultStore = pendingResultStore;
        this.updateCacheServer = updateCacheServer;
    }

    /**
     * 명령 맵을 받아 처리하고 결과를 서버에 보고한다.
     * @param cmdNode 하트비트 응답의 command 객체
     */
    public void execute(JsonNode cmdNode) {
        if (cmdNode == null || cmdNode.isNull()) return;

        long taskId = cmdNode.has("taskId") ? cmdNode.get("taskId").asLong() : 0;
        String type = cmdNode.has("type") ? cmdNode.get("type").asText("") : "";
        if (type.isEmpty()) {
            log.warn("명령에 type 필드 없음. 무시.");
            return;
        }
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
                case "COLLECT_FILES" -> executeCollectFiles(taskId, payload);
                case "LIST_FILES" -> executeListFiles(payload);
                case "AGENT_UPDATE" -> executeAgentUpdate(taskId, payload);
                default -> new CommandResult(0, "지원하지 않는 명령: " + type, "");
            };
            // LIST_FILES JSON은 4KB 제한 대신 2MB 허용
            int limit = "LIST_FILES".equals(type) ? OUTPUT_LIMIT_JSON : OUTPUT_LIMIT;
            persistAndSendResult(taskId, result, limit);
        } catch (Throwable t) {
            // AWTError(headless), Error 하위 포함 — 결과 보고 누락 방지
            String maskedMsg = SensitiveDataMasker.maskExceptionMessage(t.getMessage());
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
        persistAndSendResult(taskId, result, OUTPUT_LIMIT);
    }

    private void persistAndSendResult(long taskId, CommandResult result, int stdoutLimit) {
        CommandResult sanitized = new CommandResult(
                result.exitCode(),
                truncateTo(result.stdout(), stdoutLimit),
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
        boolean transferMode = p.has("transferMode") && p.get("transferMode").asBoolean(false);
        String sourceType = p.has("sourceType") ? p.get("sourceType").asText("file") : "file";
        boolean zipped = p.has("zipped") && p.get("zipped").asBoolean(false);
        boolean archiveTransfer = transferMode && (zipped || isArchiveTransferSourceType(sourceType));
        String displayNameFromPayload = p.has("displayName") ? p.get("displayName").asText("") : "";
        String destinationMode = p.has("destinationMode") ? p.get("destinationMode").asText("DOWNLOADS") : "DOWNLOADS";
        String destinationPath = p.has("destinationPath") ? p.get("destinationPath").asText("") : "";

        String source = scriptUrl.trim();
        String ext = (source.toLowerCase().endsWith(".zip") || archiveTransfer) ? ".zip" : ".ps1";
        Path tmpDir = background
                ? Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "pcagent-bg-scripts"))
                : Files.createTempDirectory("pcagent-ps1-");

        // MEDIUM FIX: Restrict temporary directory permissions on Unix systems
        restrictTempDirPermissions(tmpDir);

        Path tmpFile = background
                ? tmpDir.resolve("script-" + taskId + "-" + System.currentTimeMillis() + ext)
                : tmpDir.resolve("script-" + taskId + ext);
        Path stagedArchive = null;

        try {
            // 다운로드 (스트리밍 방식 — 30GB+ 대용량 파일 지원)
            Path localPath = resolveLocalScriptPath(source);
            if (localPath != null) {
                if (!Files.exists(localPath)) {
                    return new CommandResult(1, "", "로컬 스크립트 파일 없음: " + SensitiveDataMasker.maskPath(localPath.toString()));
                }
                Files.copy(localPath, tmpFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                String url = resolveRemoteScriptUrl(source);
                log.debug("PS1 다운로드 taskId={} url={}", taskId, SensitiveDataMasker.maskPath(url));
                boolean[] cancelled = {false};
                FileTransferProgressDialog dialog = new FileTransferProgressDialog(
                        tmpFile.getFileName().toString(),
                        -1L,
                        () -> cancelled[0] = true
                );
                UiThreadUtils.runOnSwingThread(dialog::show);

                Request dlReq = new Request.Builder()
                        .url(url)
                        .header("X-Agent-Token", config.getAgentToken())
                        .get()
                        .build();
                try (Response resp = downloadClient.newCall(dlReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        dialog.close();
                        return new CommandResult(1, "", "스크립트 다운로드 실패: HTTP " + resp.code());
                    }
                    long contentLength = resp.body().contentLength();
                    dialog.setStatus("전송중입니다...");
                    dialog.setEta("계산중...");

                    // 디스크에 직접 스트리밍 + SHA-256 동시 계산
                    String serverHash = resp.header("X-Script-SHA256");
                    MessageDigest md = (serverHash != null && !serverHash.isBlank())
                            ? MessageDigest.getInstance("SHA-256") : null;
                    long downloaded = 0;
                    byte[] buf = new byte[64 * 1024]; // 64KB 버퍼 (대용량 전송 최적화)
                    int r;
                    long start = System.currentTimeMillis();
                    try (InputStream in = resp.body().byteStream();
                         OutputStream out = Files.newOutputStream(tmpFile)) {
                        while ((r = in.read(buf)) > 0) {
                            if (cancelled[0]) {
                                dialog.close();
                                return new CommandResult(2, "", "사용자 취소");
                            }
                            out.write(buf, 0, r);
                            if (md != null) md.update(buf, 0, r);
                            downloaded += r;
                            double progress = contentLength > 0 ? (double) downloaded / contentLength : -1;
                            long elapsed = System.currentTimeMillis() - start;
                            double speed = elapsed > 0 ? (downloaded * 1000.0 / elapsed) : 0;
                            long remain = (speed > 0 && contentLength > 0)
                                    ? (long) ((contentLength - downloaded) / speed)
                                    : -1;
                            dialog.setProgress(progress);
                            dialog.setEta(remain > 0 ? remain + "초" : "-");
                        }
                    }

                    // SHA-256 검증
                    if (md != null) {
                        String localHash = HexFormat.of().formatHex(md.digest());
                        if (!localHash.equalsIgnoreCase(serverHash)) {
                            dialog.close();
                            return new CommandResult(1, "", "스크립트 무결성 검증 실패");
                        }
                    }
                    dialog.close();
                }
            }

            String displayName = (displayNameFromPayload == null || displayNameFromPayload.isBlank())
                    ? tmpFile.getFileName().toString()
                    : displayNameFromPayload;

            if (transferMode) {
                Path targetBaseDir = resolveTransferDestination(destinationMode, destinationPath);
                Files.createDirectories(targetBaseDir);

                if (archiveTransfer || tmpFile.toString().toLowerCase().endsWith(".zip")) {
                    Path archiveTempDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "pcagent-transfer-archive"));
                    restrictTempDirPermissions(archiveTempDir);
                    stagedArchive = Files.createTempFile(archiveTempDir, "task-" + taskId + "-", ".zip");
                    Files.copy(tmpFile, stagedArchive, StandardCopyOption.REPLACE_EXISTING);

                    String folderName = normalizeArchiveFolderName(displayName);
                    Path targetFolder = targetBaseDir.resolve(folderName);
                    Files.createDirectories(targetFolder);
                    ZipUtil.unzip(stagedArchive, targetFolder);

                    if (runAfterTransfer) {
                        Path toRun = findFirstRunnableFile(targetFolder);
                        if (toRun != null) {
                            return executeShellOrPs1(toRun);
                        }
                    }
                    return new CommandResult(0, "압축 전송 완료: " + targetFolder, "");
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
                ProcessOutputReader stdoutReader = new ProcessOutputReader(proc.getInputStream(), OUTPUT_LIMIT, "stdout");
                ProcessOutputReader stderrReader = new ProcessOutputReader(proc.getErrorStream(), OUTPUT_LIMIT, "stderr");
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
            // 압축 아티팩트는 항상 삭제
            if (stagedArchive != null) {
                try { Files.deleteIfExists(stagedArchive); } catch (IOException ignored) {}
            }

            // 임시 파일 정리 (예외 발생 시에도 보장)
            // 압축 파일은 백그라운드 여부와 무관하게 항상 제거한다.
            boolean keepForBackgroundScript = background && !transferMode
                    && !tmpFile.toString().toLowerCase(Locale.ROOT).endsWith(".zip");
            if (!keepForBackgroundScript) {
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
            ProcessOutputReader stdoutReader = new ProcessOutputReader(proc.getInputStream(), OUTPUT_LIMIT, "stdout");
            ProcessOutputReader stderrReader = new ProcessOutputReader(proc.getErrorStream(), OUTPUT_LIMIT, "stderr");
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
        if (parts.length != 6) {
            throw new IllegalArgumentException("유효하지 않은 MAC 주소 형식: " + mac);
        }
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

    // ─── 파일 수집 (Agent -> Server -> Admin Download) ───────────────────

    private CommandResult executeCollectFiles(long taskId, String payloadJson) throws Exception {
        JsonNode payload = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        List<String> requestedPaths = extractCollectPaths(payload);
        if (requestedPaths.isEmpty()) {
            return new CommandResult(1, "", "수집 경로(paths) 누락");
        }

        String outputName = payload.has("outputName") ? payload.get("outputName").asText("") : "";
        if (outputName == null || outputName.isBlank()) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            outputName = "collected-" + taskId + "-" + ts + ".zip";
        }
        outputName = sanitizeName(outputName);
        if (!outputName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            outputName += ".zip";
        }

        Path tmpZip = Files.createTempFile("pcagent-collect-" + taskId + "-", ".zip");
        try {
            CollectZipResult zipResult = zipRequestedPaths(tmpZip, requestedPaths);
            int zippedCount = zipResult.zippedCount;
            if (zippedCount <= 0) {
                return new CommandResult(1, "", "선택한 경로에서 수집 가능한 파일이 없습니다.\n" + formatCollectIssues(zipResult.issues));
            }

            JsonNode uploadResp = uploadCollectedArtifact(tmpZip, outputName, taskId);
            String downloadUrl = uploadResp.path("downloadUrl").asText("");
            String id = uploadResp.path("id").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append("파일 수집 완료: ").append(zippedCount).append("개 파일");
            if (!id.isBlank()) {
                sb.append(" (artifactId=").append(id).append(")");
            }
            if (!downloadUrl.isBlank()) {
                sb.append("\n다운로드: ").append(downloadUrl);
            }
            if (!zipResult.issues.isEmpty()) {
                sb.append("\n제외 항목: ").append(zipResult.issues.size()).append("건");
            }
            return new CommandResult(0, sb.toString(), formatCollectIssues(zipResult.issues));
        } finally {
            try {
                Files.deleteIfExists(tmpZip);
            } catch (Exception ignored) {}
        }
    }

    private List<String> extractCollectPaths(JsonNode payload) {
        Set<String> unique = new LinkedHashSet<>();
        JsonNode pathsNode = payload.get("paths");
        if (pathsNode != null && pathsNode.isArray()) {
            for (JsonNode n : pathsNode) {
                if (n != null && !n.isNull()) {
                    String p = n.asText("").trim();
                    if (!p.isBlank()) {
                        unique.add(p);
                    }
                }
            }
        }
        if (unique.isEmpty() && payload.has("path")) {
            String one = payload.get("path").asText("").trim();
            if (!one.isBlank()) {
                unique.add(one);
            }
        }
        return new ArrayList<>(unique);
    }

    private CollectZipResult zipRequestedPaths(Path zipPath, List<String> requestedPaths) throws IOException {
        int[] count = {0};
        List<String> issues = new ArrayList<>();
        String interactiveHome = resolveInteractiveUserHome();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (int i = 0; i < requestedPaths.size(); i++) {
                String raw = expandHomePath(requestedPaths.get(i), interactiveHome);
                Path p;
                try {
                    p = Path.of(raw);
                } catch (Exception ex) {
                    log.warn("수집 경로 파싱 실패: {}", SensitiveDataMasker.maskPath(raw));
                    issues.add(buildCollectIssue(raw, "경로 파싱 실패"));
                    continue;
                }
                if (!Files.exists(p)) {
                    log.warn("수집 경로 없음: {}", SensitiveDataMasker.maskPath(raw));
                    issues.add(buildCollectIssue(raw, "경로 없음"));
                    continue;
                }
                if (!Files.isReadable(p)) {
                    log.warn("수집 경로 읽기 권한 없음: {}", SensitiveDataMasker.maskPath(raw));
                    issues.add(buildCollectIssue(raw, "읽기 권한 없음"));
                    continue;
                }

                String baseName = p.getFileName() != null ? p.getFileName().toString() : ("item-" + (i + 1));
                String rootPrefix = String.format("item-%02d-%s", i + 1, sanitizeName(baseName));

                if (Files.isRegularFile(p)) {
                    try {
                        addFileToZip(zos, p, rootPrefix);
                        count[0]++;
                    } catch (IOException e) {
                        issues.add(buildCollectIssue(raw, "파일 읽기 실패: " + safeIoMessage(e)));
                    }
                    continue;
                }

                if (Files.isDirectory(p)) {
                    try (var stream = Files.walk(p)) {
                        stream.filter(Files::isRegularFile).forEach(f -> {
                            if (!Files.isReadable(f)) {
                                issues.add(buildCollectIssue(f.toString(), "읽기 권한 없음"));
                                return;
                            }
                            Path rel = p.relativize(f);
                            String entryName = rootPrefix + "/" + rel.toString().replace('\\', '/');
                            try {
                                addFileToZip(zos, f, entryName);
                                count[0]++;
                            } catch (IOException e) {
                                issues.add(buildCollectIssue(f.toString(), "파일 읽기 실패: " + safeIoMessage(e)));
                            }
                        });
                    } catch (IOException e) {
                        issues.add(buildCollectIssue(raw, "폴더 스캔 실패: " + safeIoMessage(e)));
                    }
                    continue;
                }
                issues.add(buildCollectIssue(raw, "지원하지 않는 경로 타입"));
            }
        }
        return new CollectZipResult(count[0], issues);
    }

    private static void addFileToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        ZipEntry entry = new ZipEntry(normalized);
        zos.putNextEntry(entry);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private String buildCollectIssue(String path, String reason) {
        return reason + " :: " + SensitiveDataMasker.maskPath(path);
    }

    private String safeIoMessage(IOException e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return e == null ? "입출력 오류" : e.getClass().getSimpleName();
        }
        return SensitiveDataMasker.maskExceptionMessage(e.getMessage());
    }

    private String formatCollectIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("수집 제외 상세:");
        int limit = Math.min(issues.size(), 12);
        for (int i = 0; i < limit; i++) {
            sb.append("\n - ").append(issues.get(i));
        }
        if (issues.size() > limit) {
            sb.append("\n - ... ").append(issues.size() - limit).append("건 추가");
        }
        return sb.toString();
    }

    private static class CollectZipResult {
        private final int zippedCount;
        private final List<String> issues;

        private CollectZipResult(int zippedCount, List<String> issues) {
            this.zippedCount = zippedCount;
            this.issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private JsonNode uploadCollectedArtifact(Path zipPath, String originalFilename, long taskId) throws IOException {
        long fileSize = Files.size(zipPath);
        if (fileSize <= 0) {
            throw new IOException("수집 파일이 비어 있습니다.");
        }

        try {
            return uploadCollectedArtifactInChunks(zipPath, originalFilename, taskId, fileSize);
        } catch (ChunkUploadException ex) {
            if (ex.isFallbackAllowed()) {
                log.warn("청크 업로드 엔드포인트를 사용할 수 없어 단일 업로드로 폴백합니다. taskId={} status={}",
                        taskId, ex.statusCode());
                return uploadCollectedArtifactSingle(zipPath, originalFilename, taskId);
            }
            throw ex;
        }
    }

    private JsonNode uploadCollectedArtifactInChunks(Path zipPath,
                                                     String originalFilename,
                                                     long taskId,
                                                     long fileSize) throws IOException {
        String uploadId = buildChunkUploadId(taskId);
        long estimatedChunks = (fileSize + COLLECT_UPLOAD_CHUNK_SIZE - 1L) / COLLECT_UPLOAD_CHUNK_SIZE;
        if (estimatedChunks <= 0 || estimatedChunks > Integer.MAX_VALUE) {
            throw new IOException("청크 수 계산 실패: " + estimatedChunks);
        }
        int totalChunks = (int) estimatedChunks;

        byte[] buffer = new byte[COLLECT_UPLOAD_CHUNK_SIZE];
        int chunkIndex = 0;
        try (InputStream in = Files.newInputStream(zipPath)) {
            while (chunkIndex < totalChunks) {
                int read = readChunk(in, buffer);
                if (read <= 0) {
                    break;
                }
                byte[] chunkBytes = read == buffer.length ? Arrays.copyOf(buffer, buffer.length) : Arrays.copyOf(buffer, read);
                final String chunkHash;
                try {
                    chunkHash = sha256(chunkBytes).toLowerCase(Locale.ROOT);
                } catch (Exception e) {
                    throw new IOException("청크 해시 계산 실패: " + e.getMessage(), e);
                }
                uploadChunkWithRetry(uploadId, chunkIndex, totalChunks, chunkHash, chunkBytes);
                chunkIndex++;
            }
        }

        if (chunkIndex != totalChunks) {
            throw new IOException("청크 업로드 중 파일 길이가 예상과 다릅니다. uploaded=" + chunkIndex + ", expected=" + totalChunks);
        }

        return assembleUploadedChunks(uploadId, originalFilename, totalChunks, taskId);
    }

    private int readChunk(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
            if (offset >= buffer.length) {
                break;
            }
            if (in.available() <= 0) {
                break;
            }
        }
        return offset;
    }

    private void uploadChunkWithRetry(String uploadId,
                                      int chunkIndex,
                                      int totalChunks,
                                      String chunkHash,
                                      byte[] chunkBytes) throws IOException {
        for (int attempt = 1; attempt <= COLLECT_UPLOAD_MAX_RETRY; attempt++) {
            try {
                uploadCollectedChunk(uploadId, chunkIndex, totalChunks, chunkHash, chunkBytes);
                return;
            } catch (ChunkUploadException e) {
                if (!e.retryable() || attempt >= COLLECT_UPLOAD_MAX_RETRY) {
                    throw e;
                }
                long backoff = COLLECT_UPLOAD_RETRY_BACKOFF_MS * attempt;
                log.warn("청크 업로드 재시도 taskId? uploadId={} chunk={}/{} attempt={} reason={}",
                        uploadId, chunkIndex + 1, totalChunks, attempt, e.getMessage());
                sleepQuietly(backoff);
            } catch (IOException e) {
                if (attempt >= COLLECT_UPLOAD_MAX_RETRY) {
                    throw e;
                }
                long backoff = COLLECT_UPLOAD_RETRY_BACKOFF_MS * attempt;
                sleepQuietly(backoff);
            }
        }
    }

    private JsonNode uploadCollectedChunk(String uploadId,
                                          int chunkIndex,
                                          int totalChunks,
                                          String chunkHash,
                                          byte[] chunkBytes) throws IOException {
        String url = config.getServerUrl() + "/api/v1/agent/file-chunks"
                + "?uploadId=" + urlEncode(uploadId)
                + "&chunkIndex=" + chunkIndex
                + "&totalChunks=" + totalChunks
                + "&chunkHash=" + urlEncode(chunkHash);

        RequestBody fileBody = RequestBody.create(chunkBytes, MediaType.parse("application/octet-stream"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "chunk-" + chunkIndex + ".part", fileBody)
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("X-Agent-Token", config.getAgentToken())
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String bodyText = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw toChunkUploadException("청크 업로드 실패", resp.code(), bodyText);
            }
            if (bodyText == null || bodyText.isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(bodyText);
        } catch (ChunkUploadException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("청크 업로드 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private JsonNode assembleUploadedChunks(String uploadId,
                                            String originalFilename,
                                            int totalChunks,
                                            long taskId) throws IOException {
        String url = config.getServerUrl() + "/api/v1/agent/file-chunks/assemble"
                + "?uploadId=" + urlEncode(uploadId)
                + "&originalFilename=" + urlEncode(originalFilename)
                + "&totalChunks=" + totalChunks
                + "&taskId=" + taskId;

        Request req = new Request.Builder()
                .url(url)
                .header("X-Agent-Token", config.getAgentToken())
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            String bodyText = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw toChunkUploadException("청크 조립 실패", resp.code(), bodyText);
            }
            if (bodyText == null || bodyText.isBlank()) {
                throw new IOException("청크 조립 응답이 비어 있습니다.");
            }
            return mapper.readTree(bodyText);
        } catch (ChunkUploadException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("청크 조립 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private JsonNode uploadCollectedArtifactSingle(Path zipPath, String originalFilename, long taskId) throws IOException {
        String url = config.getServerUrl() + "/api/v1/agent/uploads";
        RequestBody fileBody = RequestBody.create(zipPath.toFile(), MediaType.parse("application/zip"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", originalFilename, fileBody)
                .addFormDataPart("originalFilename", originalFilename)
                .addFormDataPart("taskId", String.valueOf(taskId))
                .build();

        Request req = new Request.Builder()
                .url(url)
                .header("X-Agent-Token", config.getAgentToken())
                .post(body)
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String bodyText = resp.body() != null ? resp.body().string() : "";
                throw new IOException("수집 파일 업로드 실패: HTTP " + resp.code() + " " + truncate(bodyText));
            }
            return mapper.readTree(resp.body().string());
        }
    }

    private ChunkUploadException toChunkUploadException(String phase, int statusCode, String bodyText) {
        boolean retryable = statusCode >= 500 || statusCode == 429;
        String message = phase + ": HTTP " + statusCode;
        if (bodyText != null && !bodyText.isBlank()) {
            try {
                JsonNode error = mapper.readTree(bodyText);
                if (error.has("retryable")) {
                    retryable = error.get("retryable").asBoolean(retryable);
                }
                String errorCode = error.path("code").asText("");
                String errorMessage = error.path("message").asText("");
                if (!errorMessage.isBlank()) {
                    message += " " + errorMessage;
                } else if (!errorCode.isBlank()) {
                    message += " " + errorCode;
                }
            } catch (Exception ignored) {
                message += " " + truncate(bodyText);
            }
        }
        return new ChunkUploadException(message, statusCode, retryable);
    }

    private String buildChunkUploadId(long taskId) {
        String random = UUID.randomUUID().toString().replace("-", "");
        String suffix = random.length() > 12 ? random.substring(0, 12) : random;
        return "collect_" + taskId + "_" + System.currentTimeMillis() + "_" + suffix;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CommandResult executeAgentUpdate(long taskId, String payloadJson) {
        try {
            JsonNode payload = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
            String sourceMode = payload.has("sourceMode") ? payload.get("sourceMode").asText("SERVER_ONLY") : "SERVER_ONLY";
            String targetVersion = payload.has("targetVersion") ? payload.get("targetVersion").asText("") : "";
            String expectedSha256 = payload.has("sha256") ? payload.get("sha256").asText("") : "";
            String peerToken = payload.has("peerToken") ? payload.get("peerToken").asText("") : "";
            String downloadUrl = payload.has("downloadUrl") ? payload.get("downloadUrl").asText("") : "";
            if (downloadUrl == null || downloadUrl.isBlank()) {
                return new CommandResult(1, "", "AGENT_UPDATE payload missing downloadUrl");
            }

            Set<String> candidateSet = new LinkedHashSet<>();
            if ("HYBRID_PEER".equalsIgnoreCase(sourceMode) && payload.has("peerCandidates") && payload.get("peerCandidates").isArray()) {
                for (JsonNode candidate : payload.get("peerCandidates")) {
                    if (candidate == null || candidate.isNull() || !candidate.has("url")) {
                        continue;
                    }
                    String url = candidate.get("url").asText("");
                    if (url != null && !url.isBlank()) {
                        candidateSet.add(url.trim());
                    }
                }
            }
            candidateSet.add(resolveRemoteScriptUrl(downloadUrl.trim()));
            List<String> candidates = new ArrayList<>(candidateSet);

            Path selfPath = resolveAgentBinaryPath();
            if (selfPath == null || !Files.exists(selfPath)) {
                return new CommandResult(1, "", "현재 실행 중인 에이전트 바이너리 경로를 확인할 수 없습니다.");
            }
            Path parentDir = selfPath.getParent() != null
                    ? selfPath.getParent()
                    : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path updateDir = Files.createDirectories(parentDir.resolve("agent-update"));

            // 파일 확장자는 downloadUrl 기준 결정 (.msi 우선 → jpackage 산출물).
            // 현재 셀프 바이너리 경로 확장자는 jpackage 런처 EXE라 .msi와 무관.
            String urlLower = downloadUrl.toLowerCase(Locale.ROOT);
            boolean isMsi = urlLower.endsWith(".msi");
            String ext;
            if (isMsi) {
                ext = ".msi";
            } else {
                String fileName = selfPath.getFileName() == null ? "pcagent.jar" : selfPath.getFileName().toString();
                ext = fileName.toLowerCase(Locale.ROOT).endsWith(".exe") ? ".exe" : ".jar";
            }
            long now = System.currentTimeMillis();
            Path stagedFile = updateDir.resolve("agent-next-" + taskId + "-" + now + ext);
            Path backupFile = updateDir.resolve("agent-backup-" + taskId + "-" + now + ext);

            downloadAgentUpdateArtifact(candidates, stagedFile, peerToken);
            String actualSha256 = sha256(stagedFile);

            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    Files.deleteIfExists(stagedFile);
                    return new CommandResult(1, "", "업데이트 파일 무결성 검증 실패");
                }
            }

            if (updateCacheServer != null) {
                try {
                    // 첫 AGENT_UPDATE 명령 수신 시 캐시 서버 on-demand 시작
                    // (평소엔 꺼두고 필요할 때만 켜서 CPU 스파이크 방지)
                    updateCacheServer.start();
                    String cacheToken = peerToken == null ? "" : peerToken;
                    updateCacheServer.registerArtifact(actualSha256, stagedFile, cacheToken);
                } catch (Exception cacheEx) {
                    log.warn("업데이트 캐시 등록 실패 taskId={}: {}", taskId, cacheEx.getMessage());
                }
            }

            if (isMsi) {
                // MSI 흐름: msiexec가 파일 교체/롤백 자동 처리 → swap 스크립트 불필요
                if (!scheduleMsiInstall(stagedFile)) {
                    return new CommandResult(1, "", "MSI 설치 프로세스 시작 실패");
                }
            } else {
                // 레거시 jar/exe 흐름 (백업 후 swap)
                Files.copy(selfPath, backupFile, StandardCopyOption.REPLACE_EXISTING);
                if (!scheduleAgentBinarySwap(selfPath, stagedFile, backupFile)) {
                    return new CommandResult(1, "", "업데이트 교체 프로세스 시작 실패");
                }
            }

            scheduleSelfExit(AGENT_UPDATE_EXIT_DELAY_MS);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "staged");
            result.put("taskId", taskId);
            result.put("targetVersion", targetVersion);
            result.put("sourceMode", sourceMode);
            result.put("downloadCandidates", candidates.size());
            result.put("sha256", actualSha256);
            result.put("peerCacheEnabled", updateCacheServer != null);
            result.put("rebootRequired", true);
            result.put("message", "업데이트 교체를 예약했습니다. 작업 저장 후 재부팅하여 버전 반영을 확인하세요.");
            return new CommandResult(0, mapper.writeValueAsString(result), "");
        } catch (Exception e) {
            return new CommandResult(1, "", "AGENT_UPDATE 실패: " + SensitiveDataMasker.maskExceptionMessage(e.getMessage()));
        }
    }

    private void downloadAgentUpdateArtifact(List<String> candidates, Path destination, String peerToken) throws IOException {
        IOException lastError = null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String url = candidate.trim();
            Path partial = destination.resolveSibling(destination.getFileName().toString() + ".part");
            try {
                Files.deleteIfExists(partial);
                downloadCandidateInChunks(url, partial, peerToken);
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException ex) {
                lastError = ex;
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException ignored) {
                }
            }
        }
        throw lastError == null
                ? new IOException("다운로드 가능한 업데이트 소스가 없습니다.")
                : new IOException("업데이트 파일 다운로드 실패: " + lastError.getMessage(), lastError);
    }

    private void downloadCandidateInChunks(String url, Path partial, String peerToken) throws IOException {
        log.info("업데이트 파일 청크 다운로드 시작 source={} chunkSize={}MB",
                SensitiveDataMasker.maskUrl(url), AGENT_UPDATE_DOWNLOAD_CHUNK_SIZE / 1024 / 1024);
        long nextOffset = 0L;
        long totalSize = -1L;
        while (totalSize < 0 || nextOffset < totalSize) {
            long end = totalSize > 0
                    ? Math.min(totalSize - 1, nextOffset + AGENT_UPDATE_DOWNLOAD_CHUNK_SIZE - 1)
                    : nextOffset + AGENT_UPDATE_DOWNLOAD_CHUNK_SIZE - 1;
            RangeDownloadResult result = downloadRangeWithRetry(url, partial, nextOffset, end, peerToken);
            if (result.fullResponse) {
                log.info("업데이트 파일 다운로드 완료 source={} size={} bytes mode=single-stream-fallback",
                        SensitiveDataMasker.maskUrl(url), result.nextOffset);
                return;
            }
            if (result.totalSize <= 0 || result.nextOffset <= nextOffset) {
                throw new IOException("잘못된 청크 다운로드 응답");
            }
            totalSize = result.totalSize;
            nextOffset = result.nextOffset;
        }
        log.info("업데이트 파일 청크 다운로드 완료 source={} size={} bytes",
                SensitiveDataMasker.maskUrl(url), totalSize);
    }

    private RangeDownloadResult downloadRangeWithRetry(String url,
                                                       Path partial,
                                                       long start,
                                                       long end,
                                                       String peerToken) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= AGENT_UPDATE_DOWNLOAD_MAX_RETRY; attempt++) {
            try {
                return downloadRange(url, partial, start, end, peerToken);
            } catch (IOException ex) {
                lastError = ex;
                if (attempt < AGENT_UPDATE_DOWNLOAD_MAX_RETRY) {
                    try {
                        Thread.sleep(COLLECT_UPLOAD_RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("청크 다운로드 재시도 중 인터럽트", interrupted);
                    }
                }
            }
        }
        throw lastError == null ? new IOException("청크 다운로드 실패") : lastError;
    }

    private RangeDownloadResult downloadRange(String url,
                                              Path partial,
                                              long start,
                                              long end,
                                              String peerToken) throws IOException {
        Request request = buildUpdateDownloadRequest(url, peerToken)
                .header("Range", "bytes=" + start + "-" + end)
                .build();
        try (Response response = downloadClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("HTTP " + response.code() + " empty body");
            }
            if (response.code() == 200 && start == 0) {
                long written = writeResponseBody(partial, 0, body);
                return new RangeDownloadResult(written, written, true);
            }
            if (response.code() != 206) {
                throw new IOException("HTTP " + response.code());
            }
            ContentRange contentRange = ContentRange.parse(response.header("Content-Range"));
            if (contentRange == null || contentRange.start != start || contentRange.end < contentRange.start) {
                throw new IOException("잘못된 Content-Range 응답");
            }
            long written = writeResponseBody(partial, start, body);
            long expectedLength = contentRange.end - contentRange.start + 1;
            if (written != expectedLength) {
                throw new IOException("청크 크기 불일치 expected=" + expectedLength + " actual=" + written);
            }
            return new RangeDownloadResult(contentRange.end + 1, contentRange.totalSize, false);
        }
    }

    private Request.Builder buildUpdateDownloadRequest(String url, String peerToken) {
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        if (isServerHostedUrl(url) && config.getAgentToken() != null && !config.getAgentToken().isBlank()) {
            requestBuilder.header("X-Agent-Token", config.getAgentToken());
        }
        if (peerToken != null && !peerToken.isBlank()) {
            requestBuilder.header("X-Agent-Update-Token", peerToken);
        }
        return requestBuilder;
    }

    private long writeResponseBody(Path target, long offset, ResponseBody body) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long written = 0L;
        byte[] buffer = new byte[AGENT_UPDATE_DOWNLOAD_BUFFER_SIZE];
        try (InputStream in = body.byteStream();
             SeekableByteChannel channel = Files.newByteChannel(target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE)) {
            channel.position(offset);
            int read;
            while ((read = in.read(buffer)) > 0) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                while (byteBuffer.hasRemaining()) {
                    channel.write(byteBuffer);
                }
                written += read;
            }
        }
        return written;
    }

    private static final class RangeDownloadResult {
        private final long nextOffset;
        private final long totalSize;
        private final boolean fullResponse;

        private RangeDownloadResult(long nextOffset, long totalSize, boolean fullResponse) {
            this.nextOffset = nextOffset;
            this.totalSize = totalSize;
            this.fullResponse = fullResponse;
        }
    }

    private static final class ContentRange {
        private final long start;
        private final long end;
        private final long totalSize;

        private ContentRange(long start, long end, long totalSize) {
            this.start = start;
            this.end = end;
            this.totalSize = totalSize;
        }

        private static ContentRange parse(String header) {
            if (header == null || !header.startsWith("bytes ")) {
                return null;
            }
            try {
                String value = header.substring("bytes ".length()).trim();
                int dash = value.indexOf('-');
                int slash = value.indexOf('/');
                if (dash < 0 || slash < 0 || slash <= dash) {
                    return null;
                }
                long start = Long.parseLong(value.substring(0, dash));
                long end = Long.parseLong(value.substring(dash + 1, slash));
                long total = Long.parseLong(value.substring(slash + 1));
                return new ContentRange(start, end, total);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private boolean isServerHostedUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI u = URI.create(url);
            URI server = URI.create(config.getServerUrl());
            String hostA = u.getHost();
            String hostB = server.getHost();
            int portA = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            int portB = server.getPort() > 0 ? server.getPort() : ("https".equalsIgnoreCase(server.getScheme()) ? 443 : 80);
            return hostA != null && hostB != null
                    && hostA.equalsIgnoreCase(hostB)
                    && portA == portB;
        } catch (Exception ex) {
            return false;
        }
    }

    private Path resolveAgentBinaryPath() {
        try {
            Path codeSource = Path.of(CommandExecutor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(codeSource)) {
                String lower = codeSource.toString().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar") || lower.endsWith(".exe")) {
                    return codeSource.toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
        }
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            try {
                return Path.of(command).toAbsolutePath().normalize();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * MSI 파일을 detached PowerShell로 설치한다.
     * 현재 프로세스가 종료되면 msiexec가 --win-upgrade-uuid 기반으로 자동 업그레이드 수행 →
     * 완료 후 schtasks /Run으로 새 에이전트 실행.
     */
    private boolean scheduleMsiInstall(Path msiFile) {
        try {
            Path script = Files.createTempFile("pcagent-msi-install-", ".ps1");
            String body = """
                    param([Parameter(Mandatory=$true)][string]$Msi)
                    Start-Sleep -Seconds 6
                    $p = Start-Process msiexec.exe -ArgumentList '/i', $Msi, '/quiet', '/norestart' -PassThru -Wait
                    if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) {
                      Start-Process schtasks.exe -ArgumentList '/Run', '/TN', 'PCAgent' -Wait
                    }
                    Remove-Item -LiteralPath $Msi -ErrorAction SilentlyContinue
                    """;
            Files.writeString(script, body, StandardCharsets.UTF_8);
            script.toFile().deleteOnExit();
            new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden",
                    "-File", script.toAbsolutePath().toString(),
                    msiFile.toAbsolutePath().toString()
            ).start();
            return true;
        } catch (Exception ex) {
            log.warn("MSI 설치 프로세스 시작 실패: {}", ex.getMessage());
            return false;
        }
    }

    private boolean scheduleAgentBinarySwap(Path targetBinary, Path stagedBinary, Path backupBinary) {
                try {
                        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                        ProcessBuilder pb;
                        if (osName.contains("win")) {
                                Path script = Files.createTempFile("pcagent-update-swap-", ".ps1");
                                String body = """
                                                param(
                                                    [string]$Target,
                                                    [string]$Next,
                                                    [string]$Backup,
                                                    [int]$VerifySeconds = 30
                                                )

                                                Start-Sleep -Seconds 8

                                                try {
                                                    Copy-Item -LiteralPath $Next -Destination $Target -Force
                                                } catch {
                                                    Write-Output \"COPY_FAILED: $_\"
                                                    exit 2
                                                }

                                                $java = $null
                                                if ($Target.ToLower().EndsWith('.jar')) {
                                                    try { $java = (Get-Command java -ErrorAction SilentlyContinue).Source } catch {}
                                                    if (-not $java) { Write-Output 'JAVA_NOT_FOUND'; exit 3 }
                                                }

                                                try {
                                                    if ($Target.ToLower().EndsWith('.jar')) {
                                                        $proc = Start-Process -FilePath $java -ArgumentList '-jar', $Target -WorkingDirectory (Split-Path $Target) -PassThru
                                                    } else {
                                                        $proc = Start-Process -FilePath $Target -WorkingDirectory (Split-Path $Target) -PassThru
                                                    }
                                                } catch {
                                                    Write-Output \"START_FAILED: $_\"
                                                }

                                                Start-Sleep -Seconds $VerifySeconds

                                                if ($proc -eq $null -or $proc.HasExited) {
                                                    Write-Output 'NEW_PROCESS_FAILED'
                                                    try { Copy-Item -LiteralPath $Backup -Destination $Target -Force } catch { Write-Output 'RESTORE_FAILED: ' + $_ }
                                                    try {
                                                        if ($Target.ToLower().EndsWith('.jar')) {
                                                            Start-Process -FilePath $java -ArgumentList '-jar', $Target -WorkingDirectory (Split-Path $Target) -PassThru
                                                        } else {
                                                            Start-Process -FilePath $Target -WorkingDirectory (Split-Path $Target) -PassThru
                                                        }
                                                    } catch {}
                                                    exit 1
                                                }

                                                Remove-Item -LiteralPath $Next -Force -ErrorAction SilentlyContinue
                                                Write-Output 'UPDATE_SUCCESS'
                                                exit 0
                                                """;

                                Files.writeString(script, body, StandardCharsets.UTF_8);
                                script.toFile().deleteOnExit();

                                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                                script.toAbsolutePath().toString(),
                                                targetBinary.toAbsolutePath().toString(),
                                                stagedBinary.toAbsolutePath().toString(),
                                                backupBinary.toAbsolutePath().toString(),
                                                "30");
                        } else {
                                Path script = Files.createTempFile("pcagent-update-swap-", ".sh");
                                String body = "#!/bin/sh\n"
                                                + "sleep 8\n"
                                                + "cp -f \"$2\" \"$1\" || { echo \"copy_failed\"; exit 2; }\n"
                                                + "if echo \"$1\" | grep -qi '\\ .jar$' ; then\n"
                                                + "  JAVA=$(command -v java || true)\n"
                                                + "  if [ -z \"$JAVA\" ]; then echo 'JAVA_NOT_FOUND'; exit 3; fi\n"
                                                + "  \"$JAVA\" -jar \"$1\" &\n"
                                                + "  PID=$!\n"
                                                + "else\n"
                                                + "  \"$1\" &\n"
                                                + "  PID=$!\n"
                                                + "fi\n"
                                                + "sleep 30\n"
                                                + "if ! kill -0 $PID 2>/dev/null; then\n"
                                                + "  echo 'new_process_failed'\n"
                                                + "  cp -f \"$3\" \"$1\" || echo 'restore_failed'\n"
                                                + "  if echo \"$1\" | grep -qi '\\ .jar$' ; then\n"
                                                + "    \"$JAVA\" -jar \"$1\" &\n"
                                                + "  else\n"
                                                + "    \"$1\" &\n"
                                                + "  fi\n"
                                                + "  exit 1\n"
                                                + "fi\n"
                                                + "rm -f \"$2\"\n"
                                                + "echo 'update_success'\n"
                                                + "exit 0\n";

                                Files.writeString(script, body, StandardCharsets.UTF_8);
                                try {
                                        script.toFile().setExecutable(true);
                                } catch (Exception ignored) {}
                                pb = new ProcessBuilder("sh", script.toAbsolutePath().toString(),
                                                targetBinary.toAbsolutePath().toString(),
                                                stagedBinary.toAbsolutePath().toString(),
                                                backupBinary.toAbsolutePath().toString(),
                                                "30");
                        }
                        pb.start();
                        return true;
                } catch (Exception ex) {
                        log.warn("업데이트 교체 프로세스 시작 실패: {}", ex.getMessage());
                        return false;
                }
    }

    private String escapeForPowerShell(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String escapeForShell(String value) {
        return value == null ? "" : value.replace("'", "'\"'\"'");
    }

    private void scheduleSelfExit(long delayMs) {
        Thread exitThread = new Thread(() -> {
            sleepQuietly(delayMs);
            System.exit(0);
        }, "agent-update-exit");
        exitThread.setDaemon(true);
        exitThread.start();
    }

    private String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private CommandResult executeListFiles(String payloadJson) {
        try {
            JsonNode payload = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
            String requestedPath = payload.has("path") ? payload.get("path").asText("").trim() : "";
            int offset = parsePagingInt(payload, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = parsePagingInt(payload, "limit", LIST_FILES_DEFAULT_LIMIT, 1, LIST_FILES_MAX_LIMIT);
            boolean includeHidden = payload.has("includeHidden") && payload.get("includeHidden").asBoolean(false);

            Path current = resolveBrowsePath(requestedPath);
            if (current == null) {
                List<Map<String, Object>> drives = new ArrayList<>();
                FileSystemView fsView = FileSystemView.getFileSystemView();
                File[] roots = File.listRoots();
                if (roots != null) {
                    for (File root : roots) {
                        if (root == null) {
                            continue;
                        }
                        drives.add(buildDriveEntry(root, fsView));
                    }
                }
                drives.sort((a, b) -> {
                    boolean usbA = Boolean.TRUE.equals(a.get("usb"));
                    boolean usbB = Boolean.TRUE.equals(b.get("usb"));
                    if (usbA != usbB) {
                        return usbA ? -1 : 1;
                    }
                    String na = String.valueOf(a.getOrDefault("displayName", a.getOrDefault("name", "")));
                    String nb = String.valueOf(b.getOrDefault("displayName", b.getOrDefault("name", "")));
                    return na.compareToIgnoreCase(nb);
                });
                int from = Math.min(Math.max(0, offset), drives.size());
                int to = Math.min(drives.size(), from + limit);
                List<Map<String, Object>> page = new ArrayList<>(drives.subList(from, to));
                boolean hasMore = to < drives.size();
                Map<String, Object> rootResp = new java.util.LinkedHashMap<>();
                rootResp.put("ok", true);
                rootResp.put("currentPath", "");
                rootResp.put("parentPath", "");
                rootResp.put("driveCount", drives.size());
                rootResp.put("offset", from);
                rootResp.put("limit", limit);
                rootResp.put("returnedEntries", page.size());
                rootResp.put("totalEntries", drives.size());
                rootResp.put("hasMore", hasMore);
                rootResp.put("nextOffset", hasMore ? to : drives.size());
                rootResp.put("entries", page);
                return new CommandResult(0, mapper.writeValueAsString(rootResp), "");
            }

            List<Map<String, Object>> entries = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int skippedEntries = 0;
            int scannedEntries = 0;
            boolean scanLimitReached = false;
            boolean timeLimitReached = false;
            long scanStartMs = System.currentTimeMillis();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                for (Path child : stream) {
                    scannedEntries++;
                    if (scannedEntries > LIST_FILES_MAX_SCAN_ENTRIES) {
                        scanLimitReached = true;
                        break;
                    }
                    if ((System.currentTimeMillis() - scanStartMs) > LIST_FILES_SCAN_TIME_LIMIT_MS) {
                        timeLimitReached = true;
                        break;
                    }
                    try {
                        if (!includeHidden) {
                            try {
                                if (Files.isHidden(child)) {
                                    continue;
                                }
                            } catch (IOException e) {
                                appendWarning(warnings, "숨김 여부 확인 실패: " + child.getFileName());
                                skippedEntries++;
                                continue;
                            }
                        }
                        boolean isDir = Files.isDirectory(child);
                        String type = isDir ? "dir" : "file";
                        long size = 0L;
                        if (!isDir) {
                            try {
                                size = Files.size(child);
                            } catch (Exception e) {
                                appendWarning(warnings, "파일 크기 조회 실패: " + child.getFileName());
                                skippedEntries++;
                                continue;
                            }
                        }
                        Map<String, Object> e = new java.util.LinkedHashMap<>();
                        e.put("name", child.getFileName() == null ? child.toString() : child.getFileName().toString());
                        e.put("type", type);
                        e.put("size", size);
                        entries.add(e);
                    } catch (Exception e) {
                        appendWarning(warnings, "항목 읽기 실패: " + child.getFileName());
                        skippedEntries++;
                    }
                }
            }

            entries.sort((a, b) -> {
                String ta = String.valueOf(a.getOrDefault("type", "file"));
                String tb = String.valueOf(b.getOrDefault("type", "file"));
                if (!ta.equals(tb)) {
                    if ("dir".equals(ta)) return -1;
                    if ("dir".equals(tb)) return 1;
                }
                String na = String.valueOf(a.getOrDefault("name", ""));
                String nb = String.valueOf(b.getOrDefault("name", ""));
                return na.compareToIgnoreCase(nb);
            });

            int from = Math.min(Math.max(0, offset), entries.size());
            int to = Math.min(entries.size(), from + limit);
            List<Map<String, Object>> page = new ArrayList<>(entries.subList(from, to));
            boolean hasMore = to < entries.size();
            boolean partial = scanLimitReached || timeLimitReached || skippedEntries > 0;
            List<String> warningsPreview = warnings.size() > LIST_FILES_WARNING_PREVIEW
                    ? new ArrayList<>(warnings.subList(0, LIST_FILES_WARNING_PREVIEW))
                    : warnings;

            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("currentPath", current.toString());
            Path parent = current.getParent();
            resp.put("parentPath", parent == null ? "" : parent.toString());
            resp.put("offset", from);
            resp.put("limit", limit);
            resp.put("returnedEntries", page.size());
            resp.put("totalEntries", entries.size());
            resp.put("scannedEntries", scannedEntries);
            resp.put("skippedEntries", skippedEntries);
            resp.put("hasMore", hasMore);
            resp.put("nextOffset", hasMore ? to : entries.size());
            resp.put("truncated", hasMore);
            resp.put("partial", partial);
            resp.put("scanLimitReached", scanLimitReached);
            resp.put("timeLimitReached", timeLimitReached);
            resp.put("warningsCount", warnings.size());
            resp.put("warningsPreview", warningsPreview);
            resp.put("entries", page);
            return new CommandResult(0, mapper.writeValueAsString(resp), "");
        } catch (Exception e) {
            return new CommandResult(1, "", "파일 목록 조회 실패: " + SensitiveDataMasker.maskExceptionMessage(e.getMessage()));
        }
    }

    private Path resolveBrowsePath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) {
            return null;
        }
        try {
            // "C:" is a relative path in Java — normalize to "C:\" for drive roots.
            String req = requestedPath.trim();
            if (req.matches("[A-Za-z]:")) {
                req = req + "\\";
            }
            Path p = Path.of(req).normalize();
            if (!Files.exists(p) || !Files.isDirectory(p)) {
                return null;
            }
            return p;
        } catch (Exception ex) {
            return null;
        }
    }

    private int parsePagingInt(JsonNode payload, String field, int defaultValue, int min, int max) {
        if (payload == null || !payload.has(field) || payload.get(field).isNull()) {
            return defaultValue;
        }
        try {
            int value = payload.get(field).asInt(defaultValue);
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void appendWarning(List<String> warnings, String message) {
        if (warnings == null || message == null || message.isBlank()) {
            return;
        }
        if (warnings.size() >= 200) {
            return;
        }
        warnings.add(message);
    }

    private Map<String, Object> buildDriveEntry(File root, FileSystemView fsView) {
        String path = root.getPath();
        String displayName = safeSystemDisplayName(fsView, root, path);
        String driveType = detectDriveType(fsView, root, path, displayName);
        boolean mounted = root.exists();
        long totalBytes = readTotalSpace(root);
        long freeBytes = readFreeSpace(root);

        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("name", path);
        e.put("displayName", displayName);
        e.put("label", extractDriveLabel(displayName, path));
        e.put("type", "drive");
        e.put("driveType", driveType);
        e.put("usb", "USB".equals(driveType));
        e.put("mounted", mounted);
        e.put("size", 0L);
        e.put("totalBytes", totalBytes);
        e.put("freeBytes", freeBytes);
        return e;
    }

    private String safeSystemDisplayName(FileSystemView fsView, File root, String fallback) {
        if (fsView == null || root == null) {
            return fallback;
        }
        try {
            String value = fsView.getSystemDisplayName(root);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private String detectDriveType(FileSystemView fsView, File root, String path, String displayName) {
        String desc = "";
        try {
            if (fsView != null) {
                desc = fsView.getSystemTypeDescription(root);
            }
        } catch (Exception ignored) {
        }
        String combined = ((desc == null ? "" : desc) + " "
                + (displayName == null ? "" : displayName) + " "
                + (path == null ? "" : path)).toLowerCase(Locale.ROOT);
        if (combined.contains("usb")
                || combined.contains("removable")
                || combined.contains("이동식")) {
            return "USB";
        }
        if (combined.contains("network")
                || combined.contains("네트워크")) {
            return "NETWORK";
        }
        return "FIXED";
    }

    private String extractDriveLabel(String displayName, String path) {
        if (displayName == null || displayName.isBlank()) {
            return path;
        }
        String v = displayName.trim();
        if (v.equalsIgnoreCase(path)) {
            return path;
        }
        return v;
    }

    private long readTotalSpace(File root) {
        if (root == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Files.getFileStore(root.toPath()).getTotalSpace());
        } catch (Exception ignored) {
        }
        try {
            return Math.max(0L, root.getTotalSpace());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long readFreeSpace(File root) {
        if (root == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Files.getFileStore(root.toPath()).getUsableSpace());
        } catch (Exception ignored) {
        }
        try {
            return Math.max(0L, root.getUsableSpace());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    // ─── 유틸 ────────────────────────────────────────────────────────────

    private static Path resolveTransferDestination(String destinationMode, String destinationPath) {
        String userHome = resolveInteractiveUserHome();
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

    /**
     * SYSTEM/Session-0 환경에서 실제 로그인 사용자의 홈 디렉토리를 반환한다.
     * 로그인 사용자를 찾지 못하면 java.home 기반 fallback을 사용한다.
     */
    /**
     * ~, %USERPROFILE%, %HOMEPATH% 를 실제 로그인 사용자 홈 디렉토리로 치환한다.
     * SYSTEM 계정으로 실행 중일 때 C:\Windows\system32\... 로 잘못 해석되는 것을 방지한다.
     */
    private static String expandHomePath(String raw, String userHome) {
        if (raw == null) return raw;
        String p = raw;
        if (p.startsWith("~/") || p.startsWith("~\\") || p.equals("~")) {
            p = userHome + p.substring(1);
        }
        p = p.replace("%USERPROFILE%", userHome)
             .replace("%userprofile%", userHome)
             .replace("%HOMEPATH%", userHome)
             .replace("%homepath%", userHome);
        return p;
    }

    private static String resolveInteractiveUserHome() {
        String javaHome = System.getProperty("user.home", ".");

        // SYSTEM 계정이 아니면 그대로 반환
        String userName = System.getProperty("user.name", "");
        if (!"SYSTEM".equalsIgnoreCase(userName)) {
            return javaHome;
        }

        // Windows: 로그인된 사용자 목록에서 첫 번째 일반 사용자 홈 탐색
        try {
            Process proc = new ProcessBuilder("query", "user").redirectErrorStream(true).start();
            if (proc.waitFor(5, TimeUnit.SECONDS)) {
                String output = new String(proc.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : output.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(">") || trimmed.startsWith("USERNAME")) continue;
                    // 첫 컬럼이 사용자명
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length > 0) {
                        String user = parts[0].replaceAll("[^\\w]", "");
                        if (!user.isBlank()) {
                            Path candidate = Path.of("C:\\Users", user);
                            if (Files.isDirectory(candidate)) {
                                return candidate.toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // fallback: C:\Users 에서 첫 번째 실사용자 디렉토리
        try {
            Path usersDir = Path.of("C:\\Users");
            if (Files.isDirectory(usersDir)) {
                try (var stream = Files.list(usersDir)) {
                    return stream
                            .filter(Files::isDirectory)
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                return !n.equalsIgnoreCase("Public")
                                        && !n.equalsIgnoreCase("Default")
                                        && !n.equalsIgnoreCase("Default User")
                                        && !n.equalsIgnoreCase("All Users")
                                        && !n.equalsIgnoreCase("desktop.ini");
                            })
                            .findFirst()
                            .map(Path::toString)
                            .orElse(javaHome);
                }
            }
        } catch (Exception ignored) {
        }
        return javaHome;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "transfer";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean isArchiveTransferSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return false;
        }
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        return "folder".equals(normalized)
                || "multi-transfer".equals(normalized)
                || "archive".equals(normalized)
                || "zip".equals(normalized);
    }

    private static String normalizeArchiveFolderName(String displayName) {
        String fallback = "transfer-folder";
        String value = sanitizeName(displayName == null || displayName.isBlank() ? fallback : displayName);
        if (value.toLowerCase(Locale.ROOT).endsWith(".zip") && value.length() > 4) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.isBlank()) {
            return fallback;
        }
        return value;
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
        return truncateTo(s, OUTPUT_LIMIT);
    }

    private static String truncateTo(String s, int limit) {
        if (s == null) return "";
        return s.length() <= limit ? s : s.substring(0, limit);
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

    private static class ChunkUploadException extends IOException {
        private final int statusCode;
        private final boolean retryable;

        private ChunkUploadException(String message, int statusCode, boolean retryable) {
            super(message);
            this.statusCode = statusCode;
            this.retryable = retryable;
        }

        private int statusCode() {
            return statusCode;
        }

        private boolean retryable() {
            return retryable;
        }

        private boolean isFallbackAllowed() {
            return statusCode == 401 || statusCode == 403 || statusCode == 404 || statusCode == 405 || statusCode == 501;
        }
    }

    /** 명령 실행 결과 레코드 */
    public record CommandResult(int exitCode, String stdout, String stderr) {}
}
