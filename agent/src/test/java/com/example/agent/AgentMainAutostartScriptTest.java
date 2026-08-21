package com.example.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMainAutostartScriptTest {

    @Test
    void watchdogActionUsesEncodedPowerShellInsteadOfFileArguments() throws Exception {
        String launcher = "C:\\Program Files\\PCAgent\\PCAgent.exe";
        String watchdogScript = "C:\\Program Files\\PCAgent\\app\\agent-watchdog.ps1";

        String actionArgs = invokeString("buildHiddenPowerShellArgs", launcher, watchdogScript);

        assertThat(actionArgs)
                .contains("-WindowStyle Hidden")
                .contains("-EncodedCommand")
                .doesNotContain("-File");

        String encoded = actionArgs.substring(actionArgs.lastIndexOf(' ') + 1);
        String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
        assertThat(decoded).isEqualTo("& 'C:\\Program Files\\PCAgent\\app\\agent-watchdog.ps1'"
                + " -InstallDir 'C:\\Program Files\\PCAgent'"
                + " -LauncherPath 'C:\\Program Files\\PCAgent\\PCAgent.exe'");
    }

    @Test
    void registrationScriptStoresHiddenEncodedWatchdogAndBatterySafeSettings() throws Exception {
        String launcher = "C:\\Program Files\\PCAgent\\PCAgent.exe";
        String watchdogScript = "C:\\Program Files\\PCAgent\\app\\agent-watchdog.ps1";

        String script = invokeString("buildAutostartRegistrationScript", launcher, watchdogScript);

        assertThat(script)
                .contains("$ErrorActionPreference = 'Stop'")
                .contains("New-ScheduledTaskAction -Execute 'powershell.exe'")
                .contains("-WindowStyle Hidden")
                .contains("-EncodedCommand")
                .doesNotContain(" -File ")
                .contains("-ExecutionTimeLimit ([TimeSpan]::Zero)")
                .contains("-RestartCount 999")
                .contains("-AllowStartIfOnBatteries")
                .contains("-DontStopIfGoingOnBatteries")
                .contains("Register-ScheduledTask -TaskName 'PCAgentWatchdog'");
    }

    private static String invokeString(String methodName, String launcher, String watchdogScript) throws Exception {
        Method method = AgentMain.class.getDeclaredMethod(methodName, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, launcher, watchdogScript);
    }
}
