package com.example.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvConfigTest {

    @Test
    void fallsBackToPostgresPasswordWhenDbPasswordIsBlank() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-test-");
        String originalUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", tempDir.toString());

            ConfigurableEnvironment environment = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            source.put("DB_PASSWORD", "");
            source.put("POSTGRES_PASSWORD", "from-postgres");
            environment.getPropertySources().addFirst(new MapPropertySource("test-env", source));

            new EnvConfig().postProcessEnvironment(environment, new SpringApplication());

            assertEquals("from-postgres", environment.getProperty("DB_PASSWORD"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void runtimePostgresPasswordOverridesDotenvDefaultDbPassword() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-test-");
        String originalUserDir = System.getProperty("user.dir");
        try {
            Files.writeString(tempDir.resolve(".env"),
                    "DB_PASSWORD=from-dotenv\nPOSTGRES_PASSWORD=from-dotenv-postgres\n");
            System.setProperty("user.dir", tempDir.toString());

            ConfigurableEnvironment environment = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            source.put("DB_PASSWORD", "");
            source.put("POSTGRES_PASSWORD", "from-runtime-postgres");
            environment.getPropertySources().addFirst(new MapPropertySource("test-env", source));

            new EnvConfig().postProcessEnvironment(environment, new SpringApplication());

            assertEquals("from-runtime-postgres", environment.getProperty("DB_PASSWORD"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
            Files.deleteIfExists(tempDir.resolve(".env"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void convertsBackupTimeToCronWhenCronIsMissing() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-test-");
        String originalUserDir = System.getProperty("user.dir");
        Path dotenvFile = tempDir.resolve(".env");
        try {
            Files.writeString(dotenvFile, "APP_PROFILE=test\n");
            System.setProperty("user.dir", tempDir.toString());

            ConfigurableEnvironment environment = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            source.put("DB_BACKUP_TIME", "03:45");
            source.put("DB_BACKUP_CRON", "");
            environment.getPropertySources().addFirst(new MapPropertySource("test-env", source));

            new EnvConfig().postProcessEnvironment(environment, new SpringApplication());

            assertEquals("0 45 03 * * *", environment.getProperty("DB_BACKUP_CRON"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
            Files.deleteIfExists(dotenvFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void backupTimeTakesPrecedenceOverBackupCron() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-test-");
        String originalUserDir = System.getProperty("user.dir");
        Path dotenvFile = tempDir.resolve(".env");
        try {
            Files.writeString(dotenvFile, "APP_PROFILE=test\n");
            System.setProperty("user.dir", tempDir.toString());

            ConfigurableEnvironment environment = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            source.put("DB_BACKUP_TIME", "03:45");
            source.put("DB_BACKUP_CRON", "0 10 2 * * *");
            environment.getPropertySources().addFirst(new MapPropertySource("test-env", source));

            new EnvConfig().postProcessEnvironment(environment, new SpringApplication());

            assertEquals("0 45 03 * * *", environment.getProperty("DB_BACKUP_CRON"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
            Files.deleteIfExists(dotenvFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void invalidBackupTimeFallsBackToBackupCron() throws IOException {
        Path tempDir = Files.createTempDirectory("env-config-test-");
        String originalUserDir = System.getProperty("user.dir");
        Path dotenvFile = tempDir.resolve(".env");
        try {
            Files.writeString(dotenvFile, "APP_PROFILE=test\n");
            System.setProperty("user.dir", tempDir.toString());

            ConfigurableEnvironment environment = new StandardEnvironment();
            Map<String, Object> source = new HashMap<>();
            source.put("DB_BACKUP_TIME", "25:99");
            source.put("DB_BACKUP_CRON", "0 10 2 * * *");
            environment.getPropertySources().addFirst(new MapPropertySource("test-env", source));

            new EnvConfig().postProcessEnvironment(environment, new SpringApplication());

            assertEquals("0 10 2 * * *", environment.getProperty("DB_BACKUP_CRON"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
            Files.deleteIfExists(dotenvFile);
            Files.deleteIfExists(tempDir);
        }
    }
}
