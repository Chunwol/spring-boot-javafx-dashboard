package com.example.agent.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorRemoteControlTest {

    @Test
    void writesUltraVncSocketSettingsInAdminSection() {
        String ini = CommandExecutor.buildUltraVncIni();

        assertThat(ini).startsWith("[ultravnc]\r\n[admin]\r\n");
        assertThat(ini).contains("SocketConnect=1\r\n");
        assertThat(ini).contains("AutoPortSelect=0\r\n");
        assertThat(ini).contains("PortNumber=5900\r\n");
        assertThat(ini).contains("LoopbackOnly=0\r\n");
        assertThat(ini).contains("AuthRequired=1\r\n");
        assertThat(ini).contains("FileTransferEnabled=1\r\n");
        assertThat(ini).contains("RemoveWallpaper=0\r\n");
        assertThat(ini).contains("RemoveAero=0\r\n");
        assertThat(ini).contains("RemoveEffects=0\r\n");
        assertThat(ini).contains("RemoveFontSmoothing=0\r\n");
        assertThat(ini).contains("MaxCpu=100\r\n");
        assertThat(ini).contains("sendbuffer=8192\r\n");
        assertThat(ini).contains("primary=1\r\n");
        assertThat(ini).contains("secondary=1\r\n");
        assertThat(ini).contains("[poll]\r\nTurboMode=1\r\n");
        assertThat(ini).contains("PollFullScreen=1\r\n");
        assertThat(ini).contains("OnlyPollOnEvent=0\r\n");
        assertThat(ini).contains("EnableHook=1\r\n");
        assertThat(ini).contains("EnableDriver=0\r\n");
        assertThat(ini.substring(0, ini.indexOf("[admin]")))
                .doesNotContain("PortNumber", "SocketConnect", "AuthRequired");
    }

    @Test
    void createsANonConflictingClipboardTransferName() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pcagent-transfer-name-");
        java.nio.file.Path existing = dir.resolve("lecture.txt");
        java.nio.file.Files.writeString(existing, "first");
        java.nio.file.Files.writeString(dir.resolve("lecture (2).txt"), "second");

        assertThat(CommandExecutor.resolveNonConflictingPath(existing))
                .isEqualTo(dir.resolve("lecture (3).txt"));
    }

    @Test
    void resolvesConsoleUserHomeWhenAgentRunsAsMachineAccount() throws Exception {
        java.nio.file.Path users = java.nio.file.Files.createTempDirectory("pcagent-users-");
        java.nio.file.Path userHome = java.nio.file.Files.createDirectory(users.resolve("USER"));
        String queryOutput = " USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME\r\n"
                + " user                  console             1  Active      none   2026-08-06 3:57 PM\r\n";

        assertThat(CommandExecutor.resolveUserHomeFromQueryOutput(queryOutput, users))
                .isEqualTo(userHome);
    }

    @Test
    void acceptsCurrentSessionMarkerInQueryUserOutput() throws Exception {
        java.nio.file.Path users = java.nio.file.Files.createTempDirectory("pcagent-users-current-");
        java.nio.file.Path userHome = java.nio.file.Files.createDirectory(users.resolve("student"));
        String queryOutput = ">student               console             1  Active      none   today\r\n";

        assertThat(CommandExecutor.resolveUserHomeFromQueryOutput(queryOutput, users))
                .isEqualTo(userHome);
    }
}
