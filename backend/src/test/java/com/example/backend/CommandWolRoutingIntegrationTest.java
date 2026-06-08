package com.example.backend;

import com.example.backend.dto.command.CommandRequestDto;
import com.example.backend.model.command.CommandQueue;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.command.CommandQueueRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.command.CommandLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CommandWolRoutingIntegrationTest {

    private static final Long GROUP_ID = 9101L;
    private static final String JWT_SECRET = "command-wol-routing-integration-secret-2026";
    private static final Path DB_PATH = Path.of("build", "tmp", "wol-routing-it-" + System.currentTimeMillis() + ".db")
            .toAbsolutePath()
            .normalize();
    private static final Path SCRIPTS_DIR = Path.of("build", "tmp", "wol-routing-uploads-" + System.currentTimeMillis())
            .toAbsolutePath()
            .normalize();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CommandLifecycleService commandLifecycleService;

    @Autowired
    private CommandQueueRepository commandQueueRepository;

    @Autowired
    private PcMasterRepository pcMasterRepository;

    @Autowired
    private ClassroomIpRangeRepository classroomIpRangeRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("app.scripts.dir", () -> SCRIPTS_DIR.toString());
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "false");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
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
        classroomIpRangeRepository.deleteAll();
        pcMasterRepository.deleteAll();
    }

    @Test
    void groupPconAutoSplitsBySlash24WithoutConfiguredIpRanges() throws Exception {
        PcMaster relayA = savePc("AA:BB:CC:91:01:01", "10.91.1.10", "ONLINE");
        PcMaster targetA = savePc("AA:BB:CC:91:01:20", "10.91.1.20", "OFFLINE");
        PcMaster relayB = savePc("AA:BB:CC:91:02:01", "10.91.2.10", "ONLINE");
        PcMaster targetB = savePc("AA:BB:CC:91:02:20", "10.91.2.20", "OFFLINE");

        CommandRequestDto req = new CommandRequestDto();
        req.setScope("GROUP");
        req.setGroupId(GROUP_ID);
        req.setType("PCON");
        req.setRole("ALL");
        req.setTargetMacs(List.of(targetA.getMacAddress(), targetB.getMacAddress()));

        var result = commandLifecycleService.enqueue(req);

        assertThat(result.get("queued")).isEqualTo(2);
        List<CommandQueue> commands = commandQueueRepository.findAll();
        assertThat(commands).hasSize(2);

        CommandQueue subnetA = commands.stream()
                .filter(command -> relayA.getMacAddress().equalsIgnoreCase(command.getMacAddress()))
                .findFirst()
                .orElseThrow();
        JsonNode payloadA = objectMapper.readTree(subnetA.getPayloadJson());
        assertThat(payloadA.get("broadcastIp").asText()).isEqualTo("10.91.1.255");
        assertThat(payloadA.get("targetMacs").asText()).contains(targetA.getMacAddress());

        CommandQueue subnetB = commands.stream()
                .filter(command -> relayB.getMacAddress().equalsIgnoreCase(command.getMacAddress()))
                .findFirst()
                .orElseThrow();
        JsonNode payloadB = objectMapper.readTree(subnetB.getPayloadJson());
        assertThat(payloadB.get("broadcastIp").asText()).isEqualTo("10.91.2.255");
        assertThat(payloadB.get("targetMacs").asText()).contains(targetB.getMacAddress());
    }

    private PcMaster savePc(String mac, String ip, String status) {
        PcMaster pc = new PcMaster();
        pc.setMacAddress(mac);
        pc.setPcName("WOL-" + mac.substring(mac.length() - 2));
        pc.setIpAddress(ip);
        pc.setGroupId(GROUP_ID);
        pc.setStatus(status);
        pc.setRole("STUDENT");
        pc.setLastSeen(LocalDateTime.now());
        return pcMasterRepository.save(pc);
    }

    private static void safeDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(CommandWolRoutingIntegrationTest::safeDelete);
        } catch (Exception ignored) {
        }
    }
}
