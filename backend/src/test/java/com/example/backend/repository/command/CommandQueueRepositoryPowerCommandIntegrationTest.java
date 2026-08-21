package com.example.backend.repository.command;

import com.example.backend.Application;
import com.example.backend.model.command.CommandQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CommandQueueRepositoryPowerCommandIntegrationTest {

    private static final Path DB_PATH = Path.of(
            "build", "tmp", "power-command-dedup-it-" + System.currentTimeMillis() + ".db")
            .toAbsolutePath()
            .normalize();

    @Autowired
    private CommandQueueRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "false");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
        registry.add("app.jwt.secret", () -> "power-command-dedup-integration-test-secret-2026");
    }

    @AfterAll
    static void cleanUp() {
        deleteIfAvailable(DB_PATH);
        deleteIfAvailable(Path.of(DB_PATH + "-journal"));
        deleteIfAvailable(Path.of(DB_PATH + "-wal"));
        deleteIfAvailable(Path.of(DB_PATH + "-shm"));
    }

    @Test
    void completedPowerCommandDoesNotBlockRetryWhileActiveCommandDoes() {
        LocalDateTime now = LocalDateTime.now();
        repository.saveAll(List.of(
                command("AA:BB:CC:DD:EE:01", "WOL_RELAY", "{\"targetMacs\":\"TARGET-1\"}", "COMPLETED", now),
                command("AA:BB:CC:DD:EE:02", "WOL_RELAY", "{\"targetMacs\":\"TARGET-2\"}", "ACKED", now)));
        repository.flush();

        long completed = repository.countRecentEquivalentPowerCommand(
                "AA:BB:CC:DD:EE:01", "WOL_RELAY", "{\"targetMacs\":\"TARGET-1\"}", now.minusMinutes(2));
        long active = repository.countRecentEquivalentPowerCommand(
                "AA:BB:CC:DD:EE:02", "WOL_RELAY", "{\"targetMacs\":\"TARGET-2\"}", now.minusMinutes(2));

        assertThat(completed).isZero();
        assertThat(active).isEqualTo(1);
    }

    private static CommandQueue command(String mac, String type, String payload, String status,
                                        LocalDateTime createdAt) {
        CommandQueue command = new CommandQueue();
        command.setMacAddress(mac);
        command.setCommandType(type);
        command.setPayloadJson(payload);
        command.setStatus(status);
        command.setCreatedAt(createdAt);
        command.setIsExecuted("COMPLETED".equals(status));
        return command;
    }

    private static void deleteIfAvailable(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
