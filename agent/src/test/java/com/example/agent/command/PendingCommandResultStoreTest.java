package com.example.agent.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PendingCommandResultStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void upsert_persistsAndReplacesByTaskId() {
        PendingCommandResultStore store = new PendingCommandResultStore(tempDir, mapper);

        store.upsert(101L, new CommandExecutor.CommandResult(0, "ok", ""));
        store.upsert(101L, new CommandExecutor.CommandResult(1, "retry", "err"));

        assertThat(store.findAll()).hasSize(1);
        assertThat(store.findAll().get(0).exitCode()).isEqualTo(1);
        assertThat(store.findAll().get(0).stdout()).isEqualTo("retry");
    }

    @Test
    void remove_deletesPersistedResult() {
        PendingCommandResultStore store = new PendingCommandResultStore(tempDir, mapper);

        store.upsert(202L, new CommandExecutor.CommandResult(0, "done", ""));
        store.remove(202L);

        assertThat(store.findAll()).isEmpty();
    }
}