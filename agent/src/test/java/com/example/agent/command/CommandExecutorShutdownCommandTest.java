package com.example.agent.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorShutdownCommandTest {

    @Test
    void shutdownCommand_forcesImmediateFullSystemShutdown() {
        assertThat(CommandExecutor.systemShutdownCommand())
                .isEqualTo("shutdown.exe /s /f /t 0");
    }

    @Test
    void rebootCommand_forcesImmediateSystemRestart() {
        assertThat(CommandExecutor.systemRebootCommand())
                .isEqualTo("shutdown.exe /r /f /t 0");
    }
}
