package com.example.backend.remotecontrol;

import com.example.backend.Application;
import com.example.backend.security.JwtExpiryWebSocketHandlerDecoratorFactory;
import com.example.backend.security.WebSocketSessionExpiryRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteControlApplicationContextIntegrationTest {

    private static final Path DB_PATH = Path.of("build", "tmp",
            "remote-control-context-" + Instant.now().toEpochMilli() + ".db")
            .toAbsolutePath().normalize();
    private static final Path UPLOAD_DIR = Path.of("build", "tmp",
            "remote-control-uploads-" + Instant.now().toEpochMilli())
            .toAbsolutePath().normalize();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toString().replace('\\', '/'));
        registry.add("app.scripts.dir", UPLOAD_DIR::toString);
        registry.add("app.jwt.secret", () -> "remote-control-integration-signing-key-2026");
        registry.add("agent.install-key", () -> "REMOTE-CONTROL-CONTEXT-INSTALL-KEY");
        registry.add("DEFAULT_ADMIN_USERNAME", () -> "remote-context-admin");
        registry.add("DEFAULT_ADMIN_PASSWORD", () -> "remote-context-password-123!");
        registry.add("DEFAULT_ADMIN_EMAIL", () -> "remote-context@example.com");
        registry.add("DEFAULT_ADMIN_SYNC_ON_STARTUP", () -> "true");
        registry.add("DEFAULT_VIEWER_SYNC_ON_STARTUP", () -> "false");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        deleteOrOnExit(DB_PATH);
        deleteOrOnExit(Path.of(DB_PATH + "-journal"));
        deleteOrOnExit(Path.of(DB_PATH + "-wal"));
        deleteOrOnExit(Path.of(DB_PATH + "-shm"));
        if (Files.exists(UPLOAD_DIR)) {
            try (var paths = Files.walk(UPLOAD_DIR)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static void deleteOrOnExit(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            path.toFile().deleteOnExit();
        }
    }

    @Test
    void startsWithStompAndRawVncRelayBeans(ApplicationContext context) {
        assertThat(context.getBean(RemoteControlController.class)).isNotNull();
        assertThat(context.getBean(RemoteControlSessionService.class)).isNotNull();
        assertThat(context.getBean(VncRelayWebSocketHandler.class)).isNotNull();
        assertThat(context.getBean(VncRelayHandshakeInterceptor.class)).isNotNull();
        assertThat(context.getBean(WebSocketSessionExpiryRegistry.class)).isNotNull();
        assertThat(context.getBean(JwtExpiryWebSocketHandlerDecoratorFactory.class)).isNotNull();
    }
}
