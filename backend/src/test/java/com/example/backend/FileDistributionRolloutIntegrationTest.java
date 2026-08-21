package com.example.backend;

import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.filedist.FileDistributionAgentStatus;
import com.example.backend.model.filedist.FileDistributionEvent;
import com.example.backend.model.filedist.FileDistributionJob;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.model.script.ScriptArtifact;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.filedist.FileDistributionAgentStatusRepository;
import com.example.backend.repository.filedist.FileDistributionEventRepository;
import com.example.backend.repository.filedist.FileDistributionJobRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.repository.script.ScriptArtifactRepository;
import com.example.backend.service.filedist.FileDistributionOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileDistributionRolloutIntegrationTest {

    private static final Path DB_PATH = Path.of("build", "tmp", "fd-rollout-it-" + System.currentTimeMillis() + ".db")
            .toAbsolutePath()
            .normalize();
    private static final Path SCRIPTS_DIR = Path.of("build", "tmp", "fd-rollout-uploads-" + System.currentTimeMillis())
            .toAbsolutePath()
            .normalize();
    private static final String JWT_SECRET = "file-distribution-rollout-test-secret-2026";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private FileDistributionOrchestrator orchestrator;

    @Autowired
    private FileDistributionJobRepository fileDistributionJobRepository;

    @Autowired
    private FileDistributionAgentStatusRepository fileDistributionAgentStatusRepository;

    @Autowired
    private FileDistributionEventRepository fileDistributionEventRepository;

    @Autowired
    private CommandQueueRepository commandQueueRepository;

    @Autowired
    private ScriptArtifactRepository scriptArtifactRepository;

    @Autowired
    private PcMasterRepository pcMasterRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("app.scripts.dir", () -> SCRIPTS_DIR.toString());
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "false");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
        registry.add("app.file-distribution.auto-rollback-enabled", () -> "true");
        registry.add("app.file-distribution.auto-catchup-on-heartbeat", () -> "true");
        registry.add("app.file-distribution.fail-threshold", () -> "0.20");
    }

    @AfterAll
    static void cleanup() {
        safeDelete(DB_PATH);
        safeDelete(Path.of(DB_PATH + "-journal"));
        safeDelete(Path.of(DB_PATH + "-wal"));
        safeDelete(Path.of(DB_PATH + "-shm"));
        deleteRecursively(SCRIPTS_DIR);
    }

    @BeforeEach
    void cleanDatabase() {
        commandQueueRepository.deleteAll();
        fileDistributionEventRepository.deleteAll();
        fileDistributionAgentStatusRepository.deleteAll();
        fileDistributionJobRepository.deleteAll();
        scriptArtifactRepository.deleteAll();
        pcMasterRepository.deleteAll();
    }

    @Test
    void autoRollbackJobIsCreatedWhenWaveFailRateExceeded() {
        PcMaster pc1 = saveOnlinePc("AA:BB:CC:DD:10:01", "10.10.0.11", "session-11", "1.0.0");
        PcMaster pc2 = saveOnlinePc("AA:BB:CC:DD:10:02", "10.10.0.12", "session-12", "1.0.0");

        ScriptArtifact stableArtifact = saveArtifact("agent-1.0.0.jar", "a".repeat(64));
        saveCompletedJob("stable-job", stableArtifact, "1.0.0", LocalDateTime.now().minusMinutes(20));

        ScriptArtifact newArtifact = saveArtifact("agent-2.0.0.jar", "b".repeat(64));
        FileDistributionJob rolloutJob = orchestrator.createJob(
                "rollout-job-" + System.currentTimeMillis(),
                "agent-2.0.0.jar",
                1,
                2,
                false,
                null,
                newArtifact.getId(),
                "2.0.0",
                "SERVER_ONLY",
                1.0,
                List.of(pc1.getMacAddress(), pc2.getMacAddress())
        );

        orchestrator.reportAgentResult(rolloutJob.getId(), pc1.getMacAddress(), true, null);
        orchestrator.reportAgentResult(rolloutJob.getId(), pc2.getMacAddress(), false, "simulated-failure");

        FileDistributionJob failed = fileDistributionJobRepository.findById(rolloutJob.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");

        Optional<FileDistributionJob> rollbackOpt = fileDistributionJobRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .filter(job -> job.getUploadId() != null
                        && job.getUploadId().startsWith("auto-rollback-" + rolloutJob.getId() + "-"))
                .findFirst();
        assertThat(rollbackOpt).isPresent();

        FileDistributionJob rollbackJob = rollbackOpt.orElseThrow();
        assertThat(rollbackJob.getArtifactId()).isEqualTo(stableArtifact.getId());
        assertThat(rollbackJob.getTargetVersion()).isEqualTo("1.0.0");

        List<FileDistributionAgentStatus> rollbackStatuses =
                fileDistributionAgentStatusRepository.findByJobIdOrderByWaveAscIdAsc(rollbackJob.getId());
        assertThat(rollbackStatuses).hasSize(2);
        assertThat(rollbackStatuses.stream().map(FileDistributionAgentStatus::getAgentId).toList())
                .containsExactlyInAnyOrder(pc1.getMacAddress(), pc2.getMacAddress());

        long rollbackCommandCount = commandQueueRepository.findAll().stream()
                .filter(command -> "AGENT_UPDATE".equalsIgnoreCase(command.getCommandType()))
                .filter(command -> rollbackJob.getId().equals(extractPayloadLong(command, "jobId")))
                .count();
        assertThat(rollbackCommandCount).isEqualTo(2L);

        List<FileDistributionEvent> failedEvents =
                fileDistributionEventRepository.findByJobIdOrderByCreatedAtDesc(failed.getId());
        assertThat(failedEvents.stream().map(FileDistributionEvent::getEventType).toList())
                .contains("AUTO_ROLLBACK_STARTED");
    }

    @Test
    void autoCatchupCreatesSingleTargetJobForVersionMismatch() {
        PcMaster laggingPc = saveOnlinePc("AA:BB:CC:DD:20:01", "10.20.0.11", "session-21", "2.0.0");
        ScriptArtifact latestArtifact = saveArtifact("agent-2.1.0.jar", "c".repeat(64));
        saveCompletedJob("baseline-job", latestArtifact, "2.1.0", LocalDateTime.now().minusMinutes(5));

        Optional<Long> firstCatchupJobId = orchestrator.tryAutoCatchUpOnHeartbeat(laggingPc);
        assertThat(firstCatchupJobId).isPresent();

        FileDistributionJob catchupJob = fileDistributionJobRepository.findById(firstCatchupJobId.orElseThrow()).orElseThrow();
        assertThat(catchupJob.getTargetVersion()).isEqualTo("2.1.0");
        assertThat(catchupJob.getArtifactId()).isEqualTo(latestArtifact.getId());

        List<FileDistributionAgentStatus> statuses =
                fileDistributionAgentStatusRepository.findByJobIdOrderByWaveAscIdAsc(catchupJob.getId());
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).getAgentId()).isEqualTo(laggingPc.getMacAddress());

        Optional<Long> secondCatchup = orchestrator.tryAutoCatchUpOnHeartbeat(laggingPc);
        assertThat(secondCatchup).isEmpty();

        List<FileDistributionEvent> catchupEvents =
                fileDistributionEventRepository.findByJobIdOrderByCreatedAtDesc(catchupJob.getId());
        assertThat(catchupEvents.stream().map(FileDistributionEvent::getEventType).toList())
                .contains("AUTO_CATCHUP_CREATED");
    }

    @Test
    void explicitRolloutTargetsOnlyRegisteredOnlinePcs() {
        PcMaster online = saveOnlinePc("AA:BB:CC:DD:25:01", "10.25.0.11", "session-25", "1.0.0");
        PcMaster offline = saveOnlinePc("AA:BB:CC:DD:25:02", "10.25.0.12", "session-26", "1.0.0");
        offline.setStatus("OFFLINE");
        pcMasterRepository.save(offline);
        ScriptArtifact artifact = saveArtifact("agent-1.1.0.jar", "d".repeat(64));

        FileDistributionJob job = orchestrator.createJob(
                "explicit-job-" + System.currentTimeMillis(),
                "agent-1.1.0.jar",
                1,
                5,
                false,
                null,
                artifact.getId(),
                "1.1.0",
                "SERVER_ONLY",
                1.0,
                List.of(online.getMacAddress(), online.getMacAddress(), offline.getMacAddress(), "AA:BB:CC:DD:25:99")
        );

        List<FileDistributionAgentStatus> statuses =
                fileDistributionAgentStatusRepository.findByJobIdOrderByWaveAscIdAsc(job.getId());
        assertThat(statuses).extracting(FileDistributionAgentStatus::getAgentId)
                .containsExactly(online.getMacAddress());
    }

    @Test
    void fullBundleRequiresBootstrapVersionAndQueuesBundleCommandOnlyForReadyPc() throws Exception {
        PcMaster legacy = saveOnlinePc("AA:BB:CC:DD:26:01", "10.26.0.11", "session-legacy", "1.1.19");
        PcMaster ready = saveOnlinePc("AA:BB:CC:DD:26:02", "10.26.0.12", "session-ready", "1.1.20");
        ScriptArtifact artifact = saveArtifact("PCAgent-1.1.20-update-bundle.zip", "e".repeat(64));

        FileDistributionJob legacyJob = orchestrator.createJob(
                "bundle-legacy-" + System.currentTimeMillis(),
                artifact.getOriginalFilename(),
                1,
                1,
                true,
                null,
                artifact.getId(),
                "1.1.20",
                "HYBRID_PEER",
                1.0,
                "FULL_BUNDLE",
                List.of(legacy.getMacAddress())
        );

        List<FileDistributionAgentStatus> legacyStatuses =
                fileDistributionAgentStatusRepository.findByJobIdOrderByWaveAscIdAsc(legacyJob.getId());
        assertThat(legacyStatuses).singleElement().satisfies(status -> {
            assertThat(status.getStatus()).isEqualTo("FAILED");
            assertThat(status.getErrorMessage()).contains("bootstrap agent 1.1.20");
        });
        assertThat(commandQueueRepository.findAll()).noneMatch(command ->
                legacyJob.getId().equals(extractPayloadLong(command, "jobId")));

        FileDistributionJob readyJob = orchestrator.createJob(
                "bundle-ready-" + System.currentTimeMillis(),
                artifact.getOriginalFilename(),
                1,
                1,
                true,
                null,
                artifact.getId(),
                "1.1.20",
                "HYBRID_PEER",
                1.0,
                "FULL_BUNDLE",
                List.of(ready.getMacAddress())
        );

        CommandQueue command = commandQueueRepository.findAll().stream()
                .filter(candidate -> readyJob.getId().equals(extractPayloadLong(candidate, "jobId")))
                .findFirst()
                .orElseThrow();
        JsonNode payload = objectMapper.readTree(command.getPayloadJson());
        assertThat(command.getCommandType()).isEqualTo("AGENT_BUNDLE_UPDATE");
        assertThat(readyJob.getSourceMode()).isEqualTo("SERVER_ONLY");
        assertThat(readyJob.getUpdatePackageType()).isEqualTo("FULL_BUNDLE");
        assertThat(payload.get("updatePackageType").asText()).isEqualTo("FULL_BUNDLE");
        assertThat(payload.get("sourceMode").asText()).isEqualTo("SERVER_ONLY");
    }

    @Test
    void hybridPeerCandidatesPreferClassroomAndRotatePerTarget() throws Exception {
        PcMaster seed1 = saveOnlinePc("AA:BB:CC:DD:30:01", "10.30.1.11", "session-31", "1.0.0", 301L);
        PcMaster seed2 = saveOnlinePc("AA:BB:CC:DD:30:02", "10.30.1.12", "session-32", "1.0.0", 301L);
        PcMaster target1 = saveOnlinePc("AA:BB:CC:DD:30:03", "10.30.1.13", "session-33", "1.0.0", 301L);
        PcMaster target2 = saveOnlinePc("AA:BB:CC:DD:30:04", "10.30.1.14", "session-34", "1.0.0", 301L);
        PcMaster target3 = saveOnlinePc("AA:BB:CC:DD:30:05", "10.30.1.15", "session-35", "1.0.0", 301L);
        ScriptArtifact artifact = saveArtifact("agent-3.0.0.msi", "d".repeat(64));

        FileDistributionJob job = orchestrator.createJob(
                "hybrid-peer-job-" + System.currentTimeMillis(),
                artifact.getOriginalFilename(),
                1,
                2,
                false,
                null,
                artifact.getId(),
                "3.0.0",
                "HYBRID_PEER",
                1.0,
                List.of(
                        seed1.getMacAddress(),
                        seed2.getMacAddress(),
                        target1.getMacAddress(),
                        target2.getMacAddress(),
                        target3.getMacAddress()
                )
        );

        orchestrator.reportAgentResult(job.getId(), seed1.getMacAddress(), true, null);
        orchestrator.reportAgentResult(job.getId(), seed2.getMacAddress(), true, null);

        List<CommandQueue> waveOneCommands = commandQueueRepository.findAll().stream()
                .filter(command -> "AGENT_UPDATE".equalsIgnoreCase(command.getCommandType()))
                .filter(command -> job.getId().equals(extractPayloadLong(command, "jobId")))
                .filter(command -> Long.valueOf(1L).equals(extractPayloadLong(command, "wave")))
                .toList();

        assertThat(waveOneCommands).hasSize(3);
        for (CommandQueue command : waveOneCommands) {
            JsonNode payload = objectMapper.readTree(command.getPayloadJson());
            assertThat(payload.get("sourceMode").asText()).isEqualTo("HYBRID_PEER");
            JsonNode candidates = payload.get("peerCandidates");
            assertThat(candidates).isNotNull();
            assertThat(candidates.size()).isEqualTo(2);
            assertThat(candidates.get(0).get("peerScope").asText()).isEqualTo("SAME_CLASSROOM");
            assertThat(candidates.get(1).get("peerScope").asText()).isEqualTo("SAME_CLASSROOM");
        }
    }

    private PcMaster saveOnlinePc(String mac, String ip, String sessionId, String version) {
        return saveOnlinePc(mac, ip, sessionId, version, null);
    }

    private PcMaster saveOnlinePc(String mac, String ip, String sessionId, String version, Long groupId) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(mac);
        pc.setPcName("PC-" + mac.substring(mac.length() - 2));
        pc.setIpAddress(ip);
        pc.setGroupId(groupId);
        pc.setStatus("ONLINE");
        pc.setLastSeen(LocalDateTime.now());
        pc.setLastAgentSessionId(sessionId);
        pc.setAgentVersion(version);
        return pcMasterRepository.save(pc);
    }

    private ScriptArtifact saveArtifact(String filename, String sha256) {
        ScriptArtifact artifact = new ScriptArtifact();
        artifact.setOriginalFilename(filename);
        artifact.setStoredFilename("stored-" + filename);
        artifact.setSha256(sha256);
        artifact.setSizeBytes(1024L);
        artifact.setContentType("application/java-archive");
        artifact.setCreatedAt(LocalDateTime.now());
        return scriptArtifactRepository.save(artifact);
    }

    private FileDistributionJob saveCompletedJob(String uploadId,
                                                 ScriptArtifact artifact,
                                                 String targetVersion,
                                                 LocalDateTime createdAt) {
        FileDistributionJob job = new FileDistributionJob();
        job.setUploadId(uploadId);
        job.setOriginalFilename(artifact.getOriginalFilename());
        job.setTotalChunks(1);
        job.setStatus("COMPLETED");
        job.setCreatedAt(createdAt);
        job.setCompletedAt(createdAt.plusMinutes(1));
        job.setArtifactId(artifact.getId());
        job.setTargetVersion(targetVersion);
        job.setSourceMode("SERVER_ONLY");
        job.setRampUpFactor(1.0);
        return fileDistributionJobRepository.save(job);
    }

    private Long extractPayloadLong(CommandQueue command, String field) {
        if (command == null || command.getPayloadJson() == null || command.getPayloadJson().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(command.getPayloadJson());
            if (node == null || !node.has(field) || node.get(field).isNull()) {
                return null;
            }
            return node.get(field).asLong();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(FileDistributionRolloutIntegrationTest::safeDelete);
        } catch (IOException ignored) {
        }
    }

    private static void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
