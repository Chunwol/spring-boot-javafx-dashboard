package com.example.agent.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class PendingCommandResultStore {

    private static final Logger log = LoggerFactory.getLogger(PendingCommandResultStore.class);
    private static final TypeReference<List<PendingCommandResult>> STORE_TYPE = new TypeReference<>() {};

    private final Path storePath;
    private final ObjectMapper mapper;

    public PendingCommandResultStore(Path baseDir, ObjectMapper mapper) {
        this.storePath = baseDir.resolve("pending-command-results.json");
        this.mapper = mapper;
    }

    public synchronized void upsert(long taskId, CommandExecutor.CommandResult result) {
        List<PendingCommandResult> items = readAll();
        items.removeIf(item -> item.taskId() == taskId);
        items.add(new PendingCommandResult(
                taskId,
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                LocalDateTime.now()
        ));
        writeAll(items);
    }

    public synchronized List<PendingCommandResult> findAll() {
        return List.copyOf(readAll());
    }

    public synchronized void remove(long taskId) {
        List<PendingCommandResult> items = readAll();
        boolean changed = items.removeIf(item -> item.taskId() == taskId);
        if (changed) {
            writeAll(items);
        }
    }

    private List<PendingCommandResult> readAll() {
        if (!Files.exists(storePath)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(mapper.readValue(storePath.toFile(), STORE_TYPE));
        } catch (IOException e) {
            log.error("미전송 결과 저장소 읽기 실패 {}: {}", storePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeAll(List<PendingCommandResult> items) {
        try {
            Files.createDirectories(storePath.getParent());
            Path tempFile = Files.createTempFile(storePath.getParent(), "pending-command-results", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), items);
            try {
                Files.move(tempFile, storePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, storePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("미전송 결과 저장소 쓰기 실패 {}: {}", storePath, e.getMessage());
        }
    }

    public record PendingCommandResult(
            long taskId,
            int exitCode,
            String stdout,
            String stderr,
            LocalDateTime storedAt
    ) {}
}