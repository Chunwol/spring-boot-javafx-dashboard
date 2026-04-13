package com.example.frontend.service;

import com.example.dto.dashboard.LayoutBackup;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Dashboard layout backup and restore service.
 * Handles persistence of dashboard layouts including panel positions, sizes, and settings.
 */
public class DashboardLayoutService {

    private static final Logger log = LoggerFactory.getLogger(DashboardLayoutService.class);
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    public DashboardLayoutService(ObjectMapper objectMapper, String apiBaseUrl) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * Backup current dashboard layout
     */
    public CompletableFuture<String> backupCurrentLayout(Long dashboardId, String name, Object layoutData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String timestamp = LocalDateTime.now().format(dateFormatter);
                String backupName = name != null ? name : "Backup_" + timestamp;

                LayoutBackup backup = new LayoutBackup();
                backup.setDashboardId(dashboardId);
                backup.setName(backupName);
                backup.setTimestamp(LocalDateTime.now());
                backup.setLayoutData(layoutData);

                String json = objectMapper.writeValueAsString(backup);

                // 로컬 파일로 저장
                String filename = "dashboard_layout_backup_" + dashboardId + "_" + timestamp + ".json";
                File defaultDirectory = new File(System.getProperty("user.home") + "/Dashboard Backups");
                if (!defaultDirectory.exists()) {
                    defaultDirectory.mkdirs();
                }

                File backupFile = new File(defaultDirectory, filename);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(backupFile, backup);

                log.info("Layout backed up to: {}", backupFile.getAbsolutePath());
                return backupFile.getAbsolutePath();
            } catch (Exception e) {
                log.error("Failed to backup layout", e);
                throw new RuntimeException("백업 실패: " + e.getMessage());
            }
        });
    }

    /**
     * Restore dashboard layout from file
     * FileChooser must be shown on JavaFX Application Thread
     */
    public CompletableFuture<LayoutBackup> restoreLayoutFromFile(Window owner, Long dashboardId) {
        CompletableFuture<File> fileChooserFuture = new CompletableFuture<>();

        Platform.runLater(() -> {
            try {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("레이아웃 백업 파일 선택");
                fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JSON 파일", "*.json")
                );

                String defaultPath = System.getProperty("user.home") + "/Dashboard Backups";
                File defaultDir = new File(defaultPath);
                if (defaultDir.exists()) {
                    fileChooser.setInitialDirectory(defaultDir);
                }

                File selectedFile = fileChooser.showOpenDialog(owner);
                fileChooserFuture.complete(selectedFile);
            } catch (Exception e) {
                fileChooserFuture.completeExceptionally(e);
            }
        });

        return fileChooserFuture.thenCompose(selectedFile ->
            CompletableFuture.supplyAsync(() -> {
                if (selectedFile == null) {
                    return null;
                }
                try {
                    LayoutBackup backup = objectMapper.readValue(selectedFile, LayoutBackup.class);
                    log.info("Layout restored from: {}", selectedFile.getAbsolutePath());
                    return backup;
                } catch (Exception e) {
                    log.error("Failed to restore layout", e);
                    throw new RuntimeException("복원 실패: " + e.getMessage());
                }
            })
        );
    }

    /**
     * List all backups for a dashboard
     */
    public CompletableFuture<List<LayoutBackup>> listBackups(Long dashboardId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File backupDir = new File(System.getProperty("user.home") + "/Dashboard Backups");
                List<LayoutBackup> backups = new ArrayList<>();

                if (backupDir.exists() && backupDir.isDirectory()) {
                    File[] files = backupDir.listFiles((dir, name) ->
                        name.startsWith("dashboard_layout_backup_" + dashboardId) && name.endsWith(".json")
                    );

                    if (files != null) {
                        for (File file : files) {
                            try {
                                LayoutBackup backup = objectMapper.readValue(file, LayoutBackup.class);
                                backups.add(backup);
                            } catch (Exception e) {
                                log.warn("Failed to read backup file: {}", file.getName(), e);
                            }
                        }
                    }
                }

                backups.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
                return backups;
            } catch (Exception e) {
                log.error("Failed to list backups", e);
                return new ArrayList<>();
            }
        });
    }

    /**
     * Delete a backup file
     */
    public CompletableFuture<Boolean> deleteBackup(LayoutBackup backup) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                File backupDir = new File(System.getProperty("user.home") + "/Dashboard Backups");
                File[] files = backupDir.listFiles((dir, name) ->
                    name.contains(backup.getDashboardId().toString()) &&
                    name.contains(backup.getTimestamp().format(dateFormatter))
                );

                if (files != null && files.length > 0) {
                    boolean deleted = files[0].delete();
                    if (deleted) {
                        log.info("Backup deleted: {}", files[0].getName());
                    }
                    return deleted;
                }
                return false;
            } catch (Exception e) {
                log.error("Failed to delete backup", e);
                return false;
            }
        });
    }

}
