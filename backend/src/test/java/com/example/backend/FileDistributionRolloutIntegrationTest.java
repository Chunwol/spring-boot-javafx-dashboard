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

    private PcMaster saveOnlinePc(String mac, String ip, String sessionId, String version) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(mac);
        pc.setPcName("PC-" + mac.substring(mac.length() - 2));
        pc.setIpAddress(ip);
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
