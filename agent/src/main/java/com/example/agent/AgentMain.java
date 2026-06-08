package com.example.agent;

import com.example.agent.config.AgentConfig;
import com.example.agent.config.AgentConfigLoader;
import com.example.agent.config.AgentConfigValidator;
import com.example.agent.hardware.HardwareCollector;
import com.example.agent.heartbeat.HeartbeatSender;
import com.example.agent.update.UpdateCacheServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * PC 관제 에이전트 엔트리포인트.
 *
 * 실행 흐름:
 *   1. config.json 로드
 *   2. 인터넷 연결 확인
 *   3. 서버 health check
 *   3. agentToken이 없으면 자동 등록 (POST /api/v1/agent/register)
 *   4. 하트비트 스케줄러 시작 (heartbeatIntervalSec ± jitter)
 *   5. 하트비트 응답에서 명령 수신 → CommandExecutor에 위임
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);
    private static final int SERVER_INITIAL_RETRY = 5;
    private static final long SERVER_RETRY_SLEEP_MS = 10_000L;
    private static final long SERVER_BACKOFF_MS = 10 * 60 * 1000L;
    private static final long INTERNET_RETRY_MS = 30_000L;
    private static final String MAIN_AUTOSTART_TASK = "PCAgent";
    private static final String LEGACY_WATCHDOG_TASK = "\\PCAgent\\Main";
    private static final String LEGACY_AUTORUN_REG_KEY = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String LEGACY_AUTORUN_REG_VALUE = "PCAgent";
    private static AgentConfig agentConfig; // OTA, 인터넷 체크에서 사용
    private static final String AGENT_VERSION = resolveAppVersion();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("에이전트 시작 중 인터럽트 발생");
        } catch (Throwable t) {
            log.error("에이전트 시작 중 치명적 오류가 발생했습니다.", t);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws InterruptedException {
        // MSI WiX 커스텀 액션: 설치 직후 SYSTEM 계정으로 초기 설정 수행 후 종료
        if (args.length > 0 && "--first-install".equals(args[0])) {
            handleFirstInstall();
            return;
        }

        log.info("=== PC 관제 에이전트 v{} 시작 ===", AGENT_VERSION);

        // 중복 실행 방지: 이미 실행 중이면 조용히 종료
        if (!acquireSingleInstanceLock()) {
            log.info("에이전트가 이미 실행 중입니다. 종료합니다.");
            return;
        }

        // ProgramData 초기화 (설치 후 첫 실행 시)
        ensureProgramDataSetup();
        log.info("시작 단계: ProgramData 준비 완료");

        AgentConfigLoader configLoader = new AgentConfigLoader();
        AgentConfig config = configLoader.load();
            AgentConfigValidator configValidator = new AgentConfigValidator();
            if (!configValidator.validateAndLog(config)) {
                log.error("설정 검증 실패로 에이전트를 시작할 수 없습니다. config.json 또는 환경변수를 확인하세요.");
                System.exit(2);
                return;
            }
        agentConfig = config;

        log.info("서버: {}", config.getServerUrl());
        log.info("config 경로: {}", configLoader.getConfigPath());

        // 시작 시마다 절전/최대절전 비활성화 재적용
        // (설치 후 전원 설정이 변경되거나 윈도우 업데이트로 초기화될 수 있음)
        preventSleep();

        // 방화벽 규칙 보장 (이미 있으면 netsh가 무시)
        ensureFirewallRules();

        // 자동 시작 schtasks 등록 (Windows Service 대체)
        // 관리자 권한 첫 실행 시 1회 등록되며, 이후 부팅마다 SYSTEM으로 자동 실행됨.
        ensureAutostart();
        log.info("시작 단계: 자동 시작 등록 확인 완료");

        // 시작 시 24시간 이상 된 백그라운드 스크립트 임시 파일 정리
        cleanupOldBgScripts();

        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        // TLS 고정(pin): AGENT_TLS_PIN_SHA256=sha256/xxxxxxxx... 형식
        if (config.getTlsPinSha256() != null && !config.getTlsPinSha256().isBlank()) {
            try {
                URI uri = URI.create(config.getServerUrl());
                String host = uri.getHost();
                if (host != null && !host.isBlank()) {
                    CertificatePinner pinner = new CertificatePinner.Builder()
                            .add(host, config.getTlsPinSha256().trim())
                            .build();
                    httpBuilder.certificatePinner(pinner);
                    log.info("TLS pinning enabled for host={}", host);
                }
            } catch (Exception e) {
                log.warn("TLS pinning 설정 무시: {}", e.getMessage());
            }
        }

        OkHttpClient httpClient = httpBuilder.build();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HardwareCollector hardware = new HardwareCollector(config.getServerUrl());
        log.info("시작 단계: 하드웨어 수집기 초기화 완료");
        // P2P 캐시 서버: 시작 시 띄우지 않음 (on-demand 방식)
        // AGENT_UPDATE 명령 수신 시 CommandExecutor가 필요할 때만 start() 호출
        // → 평소엔 jdk.httpserver Dispatcher 스레드 없음 → 1초 CPU 스파이크 없음
        Path updateCacheDir = configLoader.getConfigPath().getParent().resolve("update-cache");
        final UpdateCacheServer updateCacheServer =
                new UpdateCacheServer(updateCacheDir, Math.max(1, config.getUpdatePeerPort()));
        HeartbeatSender heartbeat = new HeartbeatSender(config, configLoader, hardware, httpClient, mapper, updateCacheServer);
        log.info("시작 단계: 하트비트 송신자 초기화 완료");

        // OTA 업데이트는 백엔드의 AGENT_UPDATE 명령 기반 (CommandExecutor.executeAgentUpdate).
        // 관리자가 관제 화면에서 MSI 업로드 → 백엔드가 모든 에이전트에 명령 push → 에이전트가 다운로드+설치.

        waitUntilServerReachable(heartbeat);

        // 토큰이 없으면 자동 등록
        if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
            log.info("에이전트 토큰 없음 → 자동 등록 시도...");
            boolean registered = false;
            for (int i = 1; i <= 3; i++) {
                if (heartbeat.register()) {
                    registered = true;
                    break;
                }
                log.warn("등록 실패 {}/3. 30초 후 재시도...", i);
                Thread.sleep(30_000);
            }
            if (!registered) {
                log.error("에이전트 등록 실패. 설치키를 확인하세요.");
                System.exit(2);
            }
        }

        // 하트비트 스케줄러
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-scheduler");
            t.setDaemon(false);
            return t;
        });

        // 시작 직후 첫 하트비트를 즉시 보내 ONLINE 상태를 빠르게 반영한다.
        try {
            heartbeat.send();
            log.info("초기 하트비트 전송 완료.");
        } catch (Exception e) {
            log.warn("초기 하트비트 전송 실패: {}", e.getMessage());
        }

        scheduleNextHeartbeat(scheduler, heartbeat, config);

        // 종료 훅
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("에이전트 종료 시작...");
            heartbeat.sendOfflineNotice("AGENT_SHUTDOWN");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("스케줄러 10초 내 종료 불가. 강제 종료 중...");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("스케줄러 종료 중 중단됨");
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            // MEDIUM FIX: Graceful shutdown of command executor
            heartbeat.gracefulShutdown();
            if (updateCacheServer != null) {
                updateCacheServer.stop();
            }
            log.info("에이전트 종료 완료.");
        }));

        log.info("하트비트 스케줄러 시작 (간격: {}s ±{}s)",
                config.getHeartbeatIntervalSec(), config.getJitterSec());

        // 메인 스레드 유지
        Thread.currentThread().join();
    }

    /**
     * 파일 잠금으로 단일 인스턴스를 보장한다.
     * C:\ProgramData\PCAgent\agent.lock 을 배타적으로 잠그는 데 성공하면 true.
     * 이미 다른 인스턴스가 잠금을 보유 중이면 false.
     * 프로세스 종료 시 잠금이 OS에 의해 자동 해제되므로 별도 해제 불필요.
     */
    @SuppressWarnings("resource") // lock/channel: 프로세스 수명 동안 유지
    private static boolean acquireSingleInstanceLock() {
        try {
            Path lockFile = Path.of("C:\\ProgramData\\PCAgent\\agent.lock");
            Files.createDirectories(lockFile.getParent());
            FileChannel fc = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = fc.tryLock();
            if (lock == null) {
                fc.close();
                return false;
            }
            // lock/channel을 GC되지 않게 보유 — 프로세스 종료 시 OS가 자동 해제
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { lock.release(); fc.close(); } catch (Exception ignored) {}
            }, "lock-release"));
            return true;
        } catch (Exception e) {
            log.warn("단일 인스턴스 잠금 실패 (계속 진행): {}", e.getMessage());
            return true;
        }
    }

    /**
     * ProgramData 초기화 (설치 후 첫 실행 또는 커스텀 액션 실패 시 복구)
     * Program Files의 config.json/app/config.json을 ProgramData로 복사 (없으면)
     */
    private static void ensureProgramDataSetup() {
        try {
            Path dataDir = Path.of("C:\\ProgramData\\PCAgent");
            Files.createDirectories(dataDir);

            Path dst = dataDir.resolve("config.json");
            if (!Files.exists(dst)) {
                // 여러 경로에서 config.json 찾기 (설치 경로 다양성 대응)
                Path[] candidates = {
                    Path.of("C:\\Program Files\\PCAgent\\app\\config.json"),
                    Path.of("C:\\Program Files\\PCAgent\\config.json"),
                    Path.of("C:\\Program Files (x86)\\PCAgent\\app\\config.json"),
                };

                for (Path candidate : candidates) {
                    if (Files.exists(candidate)) {
                        Files.copy(candidate, dst);
                        log.info("config.json 초기화: {} → {}", candidate, dst);
                        return;
                    }
                }
                log.warn("config.json을 찾을 수 없음. 기본 설정으로 진행.");
            }
        } catch (Exception e) {
            log.warn("ProgramData 초기화 중 오류 (계속 진행): {}", e.getMessage());
        }
    }

    /**
     * MSI 설치 완료 직후 WiX 커스텀 액션(SYSTEM)이 호출하는 초기 설정.
     *
     * 1. ProgramData 생성 + config.json 복사
     * 2. 방화벽 규칙
     * 3. PCAgent.exe를 일반 모드로 백그라운드 실행
     *    - CA(Windows Installer)의 제한적 SYSTEM 컨텍스트와 달리,
     *      자식 프로세스는 제약 없는 SYSTEM이라 schtask 등록 가능
     *    - 즉시 온라인 → 재부팅 없이 대시보드에 바로 표시
     */
    private static void handleFirstInstall() {
        try {
            Path dataDir = Path.of("C:\\ProgramData\\PCAgent");
            Files.createDirectories(dataDir);

            Path dst = dataDir.resolve("config.json");
            if (!Files.exists(dst)) {
                Path src = findConfigJsonSource();
                if (src != null) {
                    Files.copy(src, dst);
                }
            }

            ensureFirewallRules();

            // 에이전트를 일반 모드로 즉시 시작 (설치 후 재부팅 불필요)
            String exePath = resolveInstalledExePath();
            if (exePath != null) {
                new ProcessBuilder(exePath).start();
            }
        } catch (Exception e) {
            // 실패해도 설치 자체는 성공 — HKLM Run 키로 다음 로그인 시 자동 시작
        }
    }

    /**
     * config.json 소스 파일 탐색.
     * ProcessHandle이 SYSTEM/session-0에서 빈 값을 반환하는 경우를 대비해
     * 하드코딩된 jpackage 설치 경로를 fallback으로 사용한다.
     */
    private static Path findConfigJsonSource() {
        // 1순위: ProcessHandle 기반 동적 탐지
        String exeCmd = ProcessHandle.current().info().command().orElse("");
        if (!exeCmd.isBlank()) {
            Path appDir = Path.of(exeCmd).getParent();
            if (appDir != null) {
                for (Path candidate : new Path[]{
                        appDir.resolve("config.json"),
                        appDir.resolve("app").resolve("config.json")}) {
                    if (Files.exists(candidate)) return candidate;
                }
            }
        }
        // 2순위: 하드코딩 경로 (jpackage 기본 설치 위치)
        for (Path candidate : new Path[]{
                Path.of("C:\\Program Files\\PCAgent\\app\\config.json"),
                Path.of("C:\\Program Files\\PCAgent\\config.json"),
                Path.of("C:\\Program Files (x86)\\PCAgent\\app\\config.json"),
                Path.of("C:\\Program Files (x86)\\PCAgent\\config.json")}) {
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    /**
     * 설치된 PCAgent.exe 경로 탐색 (ensureAutostart fallback용).
     */
    private static String resolveInstalledExePath() {
        for (String candidate : new String[]{
                "C:\\Program Files\\PCAgent\\PCAgent.exe",
                "C:\\Program Files (x86)\\PCAgent\\PCAgent.exe"}) {
            if (Path.of(candidate).toFile().exists()) return candidate;
        }
        return null;
    }

    private static void waitUntilServerReachable(HeartbeatSender heartbeat) throws InterruptedException {
        while (true) {
            waitForInternetConnection();

            boolean serverAlive = false;
            for (int i = 1; i <= SERVER_INITIAL_RETRY; i++) {
                if (heartbeat.checkServerAlive()) {
                    serverAlive = true;
                    break;
                }
                log.warn("서버 연결 대기 중... ({}/{})", i, SERVER_INITIAL_RETRY);
                Thread.sleep(SERVER_RETRY_SLEEP_MS);
            }

            if (serverAlive) {
                log.info("서버 연결 확인 완료.");
                return;
            }

            log.warn("서버 연결 5회 실패. 10분 후 다시 확인합니다.");
            Thread.sleep(SERVER_BACKOFF_MS);
        }
    }

    private static void waitForInternetConnection() throws InterruptedException {
        while (!isInternetAvailable()) {
            log.warn("인터넷 연결 없음. 30초 후 재확인합니다.");
            Thread.sleep(INTERNET_RETRY_MS);
        }
    }

    /**
     * TCP 소켓으로 설정된 호스트:포트 연결을 시도한다.
     * config.json의 internetCheckHost/internetCheckPort로 대상 변경 가능.
     */
    private static boolean isInternetAvailable() {
        String host = agentConfig != null ? agentConfig.getInternetCheckHost() : "1.1.1.1";
        int port = agentConfig != null ? agentConfig.getInternetCheckPort() : 53;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Windows 절전·최대절전 타이머를 비활성화한다.
     * MSI 설치 시 1회 적용은 부족하므로 (윈도우 업데이트로 초기화 가능) 매 기동마다 재적용.
     * powercfg.exe(native)만 사용 — PowerShell 의존 없음.
     */
    private static void preventSleep() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        String[][] commands = {
                {"powercfg", "-change", "-standby-timeout-ac", "0"},
                {"powercfg", "-change", "-standby-timeout-dc", "0"},
                {"powercfg", "-change", "-hibernate-timeout-ac", "0"},
                {"powercfg", "-change", "-hibernate-timeout-dc", "0"},
                {"powercfg", "-hibernate", "off"}
        };
        for (String[] cmd : commands) {
            runQuietly(cmd, 5);
        }
        log.info("절전 비활성화 설정 적용 완료.");
    }

    /**
     * 에이전트 통신용 방화벽 규칙을 자체 등록한다.
     * 규칙이 이미 있으면 netsh가 "already exists" 반환 → 무시.
     * MSI install-time custom action 대신 에이전트가 SYSTEM 권한으로 직접 등록.
     */
    private static void ensureFirewallRules() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        runQuietly(new String[]{
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=PCAgent Outbound", "dir=out", "action=allow",
                "protocol=TCP", "remoteport=8888,443,80"
        }, 5);
        runQuietly(new String[]{
                "netsh", "advfirewall", "firewall", "add", "rule",
                "name=PCAgent Update Cache", "dir=in", "action=allow",
                "protocol=TCP", "localport=18777"
        }, 5);
        log.info("방화벽 규칙 확인/등록 완료.");
    }

    /**
     * Windows Task Scheduler에 자동 시작 작업(PCAgent)을 등록한다.
     * Windows Service(--launcher-as-service / WinSW) 대안 — 미서명 EXE 권한 문제 회피.
     *
     * 동작:
     *   - jpackage 런처(PCAgent.exe)로 실행되었을 때만 등록 시도
     *   - 작업이 이미 있으면 건너뜀 (멱등)
     *   - /SC ONSTART /RU SYSTEM → 부팅 시 SYSTEM 권한으로 자동 실행 (/RL HIGHEST 제거: SYSTEM과 충돌)
     *   - 관리자 권한이 없으면 등록 실패하지만 에러로 처리하지 않음 (사용자가 admin으로 한번 실행하면 등록됨)
     */
    private static void ensureAutostart() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        cleanupLegacyAutostartEntries();
        // ProcessHandle이 SYSTEM/session-0에서 빈 값을 반환할 수 있으므로
        // 빈 값이면 하드코딩 경로로 fallback한다.
        String launcher = ProcessHandle.current().info().command().orElse("");
        if (launcher.isBlank() || !launcher.toLowerCase().endsWith("pcagent.exe")) {
            launcher = resolveInstalledExePath();
            if (launcher == null) {
                log.debug("PCAgent.exe 경로를 찾을 수 없어 자동 시작 등록 생략");
                return;
            }
        }
        try {
            String normalizedLauncher = Path.of(launcher).toAbsolutePath().normalize().toString();
            boolean registered = registerAutostartWithPowerShell(normalizedLauncher)
                    || registerAutostartWithSchtasks(normalizedLauncher);
            if (registered) {
                log.info("자동 시작 작업({}) 확인/등록 완료: {}", MAIN_AUTOSTART_TASK, normalizedLauncher);
                return;
            }
            log.warn("자동 시작 등록 실패 (관리자 권한 필요). 관리자 권한으로 1회 실행하면 등록됩니다.");
        } catch (Exception e) {
            log.warn("자동 시작 등록 중 오류 (무시): {}", e.getMessage());
        }
    }

    private static boolean registerAutostartWithPowerShell(String launcher) {
        Path workDir = Path.of(launcher).getParent();
        String workDirValue = workDir == null ? "" : workDir.toString();
        String script = "$action = New-ScheduledTaskAction"
                + " -Execute " + psSingleQuote(launcher)
                + (workDirValue.isBlank() ? "" : " -WorkingDirectory " + psSingleQuote(workDirValue))
                + "; $trigger = New-ScheduledTaskTrigger -AtStartup"
                + "; $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest"
                + "; Register-ScheduledTask -TaskName " + psSingleQuote(MAIN_AUTOSTART_TASK)
                + " -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null";
        return runCommand("PowerShell 자동 시작 작업 등록",
                new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script},
                15);
    }

    private static boolean registerAutostartWithSchtasks(String launcher) {
        // schtasks /TR 값은 작업 스케줄러가 다시 파싱한다.
        // 공백이 있는 Program Files 경로는 /TR 문자열 내부에서도 반드시 따옴표로 감싸야 한다.
        return runCommand("schtasks 자동 시작 작업 등록",
                new String[]{
                        "schtasks", "/Create",
                        "/TN", MAIN_AUTOSTART_TASK,
                        "/TR", "\"" + launcher + "\"",
                        "/SC", "ONSTART",
                        "/RU", "SYSTEM",
                        "/F"
                },
                15);
    }

    private static String psSingleQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean runCommand(String description, String[] command, int timeoutSec) {
        try {
            Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("{} 시간 초과", description);
                return false;
            }
            if (proc.exitValue() == 0) {
                return true;
            }
            log.debug("{} 실패(exit={})", description, proc.exitValue());
            return false;
        } catch (Exception e) {
            log.debug("{} 실패: {}", description, e.getMessage());
            return false;
        }
    }

    private static void cleanupLegacyAutostartEntries() {
        removeLegacyScheduledTask();
        removeLegacyAutoRunRegistryValue();
    }

    private static void removeLegacyScheduledTask() {
        if (!isSchtaskRegistered(LEGACY_WATCHDOG_TASK)) {
            return;
        }
        try {
            Process proc = new ProcessBuilder(
                    "schtasks", "/Delete",
                    "/TN", LEGACY_WATCHDOG_TASK,
                    "/F"
            ).redirectErrorStream(true).start();
            if (proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                log.info("레거시 자동 시작 작업 제거 완료: {}", LEGACY_WATCHDOG_TASK);
            } else {
                log.warn("레거시 자동 시작 작업 제거 실패: {}", LEGACY_WATCHDOG_TASK);
            }
        } catch (Exception e) {
            log.warn("레거시 자동 시작 작업 제거 중 오류: {}", e.getMessage());
        }
    }

    private static void removeLegacyAutoRunRegistryValue() {
        try {
            Process proc = new ProcessBuilder(
                    "reg", "delete",
                    LEGACY_AUTORUN_REG_KEY,
                    "/v", LEGACY_AUTORUN_REG_VALUE,
                    "/f"
            ).redirectErrorStream(true).start();
            if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                log.info("레거시 로그인 자동 실행 레지스트리 제거 완료: {}", LEGACY_AUTORUN_REG_VALUE);
            }
        } catch (Exception e) {
            log.debug("레거시 로그인 자동 실행 레지스트리 제거 실패(무시): {}", e.getMessage());
        }
    }

    /** Windows Task Scheduler에 해당 이름 작업이 있는지 확인. */
    private static boolean isSchtaskRegistered(String taskName) {
        try {
            Process proc = new ProcessBuilder("schtasks", "/Query", "/TN", taskName)
                    .redirectErrorStream(true).start();
            return proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 출력 무시하고 명령 실행 (실패해도 로그 한 줄만). */
    private static void runQuietly(String[] cmd, int timeoutSec) {
        try {
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                log.debug("명령 타임아웃: {}", String.join(" ", cmd));
            }
        } catch (Exception e) {
            log.debug("명령 실행 실패: {} → {}", cmd[0], e.getMessage());
        }
    }

    /**
     * pcagent-bg-scripts 임시 디렉토리에서 24시간 이상 된 파일을 삭제한다.
     * 백그라운드 PS1 실행 후 남겨진 파일이 무한히 누적되지 않도록 한다.
     */
    private static void cleanupOldBgScripts() {
        try {
            Path bgDir = Path.of(System.getProperty("java.io.tmpdir"), "pcagent-bg-scripts");
            if (!Files.exists(bgDir)) return;
            long cutoffMs = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            try (var stream = Files.list(bgDir)) {
                stream.forEach(f -> {
                    try {
                        if (Files.getLastModifiedTime(f).toMillis() < cutoffMs) {
                            Files.deleteIfExists(f);
                        }
                    } catch (IOException e) {
                        log.debug("임시 스크립트 파일 삭제 실패: {}", f);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("임시 스크립트 정리 실패: {}", e.getMessage());
        }
    }

    private static void scheduleNextHeartbeat(
            ScheduledExecutorService scheduler,
            HeartbeatSender heartbeat,
            AgentConfig config) {

        int jitter = config.getJitterSec() > 0
                ? ThreadLocalRandom.current().nextInt(-config.getJitterSec(), config.getJitterSec() + 1)
                : 0;
        int delaySeconds = Math.max(10, config.getHeartbeatIntervalSec() + jitter);

        scheduler.schedule(() -> {
            try {
                // 토큰이 초기화(재등록 필요) 되었는지 확인
                if (config.getAgentToken() == null || config.getAgentToken().isBlank()) {
                    log.info("토큰 초기화됨 → 재등록 시도 (최대 3회)...");
                    boolean reRegistered = false;
                    for (int i = 1; i <= 3; i++) {
                        if (heartbeat.register()) {
                            reRegistered = true;
                            log.info("재등록 성공.");
                            break;
                        }
                        log.warn("재등록 실패 {}/3", i);
                        if (i < 3) Thread.sleep(10_000);
                    }
                    if (!reRegistered) {
                        log.error("재등록 3회 모두 실패. 다음 하트비트까지 대기.");
                        return; // 토큰 없이 하트비트 전송 시도하지 않음
                    }
                }
                heartbeat.send();
            } catch (Exception e) {
                log.error("하트비트 오류: {}", e.getMessage());
            } finally {
                // 다음 하트비트 스케줄링 (재귀 방식으로 jitter 적용)
                scheduleNextHeartbeat(scheduler, heartbeat, config);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private static String resolveAppVersion() {
        Package pkg = AgentMain.class.getPackage();
        if (pkg != null) {
            String implementationVersion = pkg.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.isBlank()) {
                return implementationVersion.trim();
            }
        }
        String systemProp = System.getProperty("pcagent.version");
        if (systemProp != null && !systemProp.isBlank()) {
            return systemProp.trim();
        }
        return "dev";
    }
}
