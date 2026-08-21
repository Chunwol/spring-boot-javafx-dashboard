package com.example.agent.scmate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScmateRecoveryInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void silentInstallerUsesArchitectureSpecificVendorExecutable() {
        assertThat(ScmateRecoveryInstaller.buildInstallCommand(tempDir, true))
                .containsExactly(tempDir.resolve("x64.exe").toString(), "/s");
        assertThat(ScmateRecoveryInstaller.buildInstallCommand(tempDir, false))
                .containsExactly(tempDir.resolve("setup.exe").toString(), "/s");
    }

    @Test
    void installerConfigurationDisablesEveryAutomaticRebootSetting() throws Exception {
        Path ini = tempDir.resolve("setup.ini");
        Files.writeString(ini,
                "[General]\r\nreboot=1\r\n[Advanced]\r\nReboot = 1\r\nProtectedDrives=0x4\r\n",
                StandardCharsets.ISO_8859_1);

        assertThat(ScmateRecoveryInstaller.disableAutomaticReboot(ini)).isTrue();
        assertThat(Files.readString(ini, StandardCharsets.ISO_8859_1))
                .contains("reboot=0", "Reboot = 0", "ProtectedDrives=0x4")
                .doesNotContain("reboot=1", "Reboot = 1");
    }
}
