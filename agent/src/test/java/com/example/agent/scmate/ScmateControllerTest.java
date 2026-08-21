package com.example.agent.scmate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScmateControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void decodeStatus_zeroDoesNotInferGuiMode() {
        ScmateController.Status status = ScmateController.decodeStatus(0, 0);

        assertThat(status.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(status.schedule()).isEqualTo(ScmateController.Schedule.UNKNOWN);
        assertThat(status.availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.STATUS_UNAVAILABLE);
        assertThat(status.transitionPending()).isFalse();
    }

    @Test
    void decodeStatus_oneDoesNotInferGuiMode() {
        ScmateController.Status status = ScmateController.decodeStatus(1, 0);

        assertThat(status.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(status.availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.STATUS_UNAVAILABLE);
        assertThat(status.transitionPending()).isFalse();
    }

    @Test
    void decodeStatus_transitionBitIsIndependentFromTargetMode() {
        ScmateController.Status protectedTransition = ScmateController.decodeStatus(2, 7);
        ScmateController.Status operatorTransition = ScmateController.decodeStatus(3, 9);

        assertThat(protectedTransition.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(protectedTransition.transitionPending()).isTrue();
        assertThat(protectedTransition.auxiliaryRaw()).isEqualTo(7);
        assertThat(operatorTransition.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(operatorTransition.transitionPending()).isTrue();
    }

    @Test
    void decodeWorkModePacketSeparatesStateAndAuxiliaryByte() {
        ScmateController.Status status = ScmateController.decodeWorkModePacket(0x00000702);

        assertThat(status.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(status.transitionPending()).isTrue();
        assertThat(status.raw()).isEqualTo(2);
        assertThat(status.auxiliaryRaw()).isEqualTo(7);
    }

    @Test
    void displayStateOverridesNativeModeUsingScmateClientSemantics() {
        ScmateController.Status nativeOperator = ScmateController.decodeStatus(1, 0);

        assertThat(ScmateController.applyDisplayState(nativeOperator, OptionalInt.of(0)).mode())
                .isEqualTo(ScmateController.Mode.PROTECTED);
        assertThat(ScmateController.applyDisplayState(nativeOperator, OptionalInt.of(3)).mode())
                .isEqualTo(ScmateController.Mode.OPERATOR);
        assertThat(ScmateController.applyDisplayState(nativeOperator, OptionalInt.of(9)).availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.PROTECT_DISABLED);
        assertThat(ScmateController.applyDisplayState(nativeOperator, OptionalInt.empty()).mode())
                .isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(ScmateController.applyDisplayState(nativeOperator, OptionalInt.empty()).availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.STATUS_UNAVAILABLE);
    }

    @Test
    void confirmedNotInstalledDominatesAnyDisplayState() {
        ScmateController.Status nativeStatus = ScmateController.decodeStatus(0, 0);

        ScmateController.Status status = ScmateController.applyDisplayState(
                nativeStatus, OptionalInt.of(5), true);

        assertThat(status.mode()).isEqualTo(ScmateController.Mode.UNKNOWN);
        assertThat(status.schedule()).isEqualTo(ScmateController.Schedule.UNKNOWN);
        assertThat(status.selectedPoint()).isEqualTo(ScmateController.RecoveryPoint.UNKNOWN);
        assertThat(status.availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.NOT_INSTALLED);
    }

    @Test
    void operatorModeDeletesResetTasks() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        AtomicReference<List<List<String>>> arguments = new AtomicReference<>();

        ScmateController.Result failure = ScmateController.requestMode(
                commandPath,
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                ScmateController.Mode.OPERATOR,
                "operator",
                "test-password",
                (path, mode, username, password, timeout) -> {
                    arguments.set(ScmateController.buildModeCommands(path, mode, username, password));
                    assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
                    return ScmateController.RestoreExecution.completed(0);
                });

        assertThat(failure).isNull();
        assertThat(arguments.get()).containsExactly(
                List.of(commandPath.toString(), "/DelTask", "/type", "reset",
                        "/u", "operator", "/p", "test-password"));
    }

    @Test
    void modeRequestRejectsAuthenticationMessageEvenWhenExitCodeIsZero() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        byte[] output = "사용자 이름 또는 암호가 올바르지 않습니다."
                .getBytes(Charset.forName("MS949"));

        ScmateController.Result result = ScmateController.requestMode(
                commandPath,
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                ScmateController.Mode.OPERATOR,
                "operator",
                "test-password",
                (path, mode, username, password, timeout) ->
                        ScmateController.RestoreExecution.completed(0, output));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_MODE_REJECTED:");
    }

    @Test
    void vendorAdministratorUsernameUsesTheExactLowercaseCliSpelling() {
        Path commandPath = tempDir.resolve("ShdCmd.exe");

        assertThat(ScmateController.buildModeCommands(commandPath,
                ScmateController.Mode.OPERATOR, "Administrator", "secret").get(0))
                .containsSequence("/u", "administrator", "/p", "secret");
    }

    @Test
    void interactiveTaskCommandLinePreservesWindowsArguments() {
        assertThat(ScmateController.windowsCommandLine(List.of(
                "C:\\Program Files\\SCMATE_RX2Client\\Shield\\ShdCmd.exe",
                "/DelTask", "/type", "reset", "/p", "a b")))
                .isEqualTo("\"C:\\Program Files\\SCMATE_RX2Client\\Shield\\ShdCmd.exe\" "
                        + "/DelTask /type reset /p \"a b\"");
    }

    @Test
    void interactiveWrapperDoesNotStoreCredentialsAsPlainText() {
        String wrapper = ScmateController.buildInteractiveWrapper(
                List.of("ShdCmd.exe", "/u", "administrator", "/p", "secret-value"),
                Path.of("C:\\Windows\\Temp\\mode.out"),
                Path.of("C:\\Windows\\Temp\\mode.result"));

        assertThat(wrapper).doesNotContain("administrator", "secret-value");
        assertThat(wrapper).contains("FromBase64String", "Set-Location -LiteralPath $working",
                "mode.out", "mode.result");
    }

    @Test
    void detailParserIgnoresDefragFirstBootMaintenanceTask() {
        ScmateController.Status staleProtected = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(1));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(1), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Details details = ScmateController.parseDetails(
                "", "0   Defrag   Defrag Snapshots   Daily@ First bootup", staleProtected, client);

        assertThat(details.schedule()).isEqualTo(ScmateController.Schedule.MANUAL);
        assertThat(details.selectedPoint()).isEqualTo(ScmateController.RecoveryPoint.UNKNOWN);
    }

    @Test
    void detailParserReadsOnlyResetTaskSchedule() {
        ScmateController.Status staleOperator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Details details = ScmateController.parseDetails(
                "", "0 Defrag Defrag Snapshots Daily@ First bootup\r\n"
                        + "1 SCMATEBASE_TASK Reset System Restart Win:SCMATE_BASELINE", staleOperator, client);

        assertThat(details.schedule()).isEqualTo(ScmateController.Schedule.RESTART);
        assertThat(details.selectedPoint()).isEqualTo(ScmateController.RecoveryPoint.BASELINE);
    }

    @Test
    void detailParserReadsVendorRollbackTaskAsRestartRecovery() {
        ScmateController.Status staleOperator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Details details = ScmateController.parseDetails(
                "", "1 SCMATEBASE_TASK Rollback Computer @ Restart", staleOperator, client);

        assertThat(details.schedule()).isEqualTo(ScmateController.Schedule.RESTART);
        assertThat(details.selectedPoint()).isEqualTo(ScmateController.RecoveryPoint.BASELINE);
    }

    @Test
    void interactiveScriptWrapperExecutesScriptDirectlyAndWritesResult() {
        String wrapper = ScmateController.buildInteractiveScriptWrapper(
                "$value='ok'\r\nWrite-Output $value",
                Path.of("C:\\Windows\\Temp\\refresh.out"),
                Path.of("C:\\Windows\\Temp\\refresh.result"));

        assertThat(wrapper).contains("& {", "$value='ok'", "Write-Output $value",
                "refresh.out", "refresh.result", "$code=0");
        assertThat(wrapper).doesNotContain("powershell.exe -Command");
    }

    @Test
    void protectedDisplayStateMatchesSelectedRecoveryPoint() {
        assertThat(ScmateController.protectedDisplayState(
                ScmateController.RecoveryPoint.BASELINE)).isZero();
        assertThat(ScmateController.protectedDisplayState(
                ScmateController.RecoveryPoint.SNAP1)).isEqualTo(5);
        assertThat(ScmateController.protectedDisplayState(
                ScmateController.RecoveryPoint.SNAP2)).isEqualTo(6);
    }

    @Test
    void protectedModeReplacesResetTasksWithEveryBootBaselineTask() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        AtomicReference<List<List<String>>> arguments = new AtomicReference<>();

        ScmateController.Result failure = ScmateController.requestMode(
                commandPath,
                new ScmateController.Status(ScmateController.Mode.OPERATOR, false, 3, 0),
                ScmateController.Mode.PROTECTED,
                "operator",
                "test-password",
                (path, mode, username, password, timeout) -> {
                    arguments.set(ScmateController.buildModeCommands(path, mode, username, password));
                    return ScmateController.RestoreExecution.completed(0);
                });

        assertThat(failure).isNull();
        assertThat(arguments.get()).containsExactly(
                List.of(commandPath.toString(), "/DelTask", "/type", "reset",
                        "/u", "operator", "/p", "test-password"),
                List.of(commandPath.toString(), "/AddResetTask", "/schtype", "restart",
                        "/name", "SCMATEBASE_TASK", "/n", "Win:SCMATE_BASELINE",
                        "/u", "operator", "/p", "test-password"));
    }

    @Test
    void protectionEnableUsesOnlyTheVendorEnableCommand() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        ScmateController.Status disabled = new ScmateController.Status(
                ScmateController.Mode.UNKNOWN, false, 9, 1,
                ScmateController.Schedule.UNKNOWN,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.AvailabilityReason.PROTECT_DISABLED);

        ScmateController.Result result = ScmateController.enableProtection(
                commandPath, disabled, "operator", "test-password", new ObjectMapper(),
                (command, timeout) -> {
                    arguments.set(List.copyOf(command));
                    assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
                    return ScmateController.RestoreExecution.completed(0);
                });

        assertThat(result.exitCode()).isZero();
        assertThat(arguments.get()).containsExactly(
                commandPath.toString(), "/protect", "enable",
                "/u", "operator", "/p", "test-password");
        assertThat(new ObjectMapper().readTree(result.stdout())
                .path("protectionEnableRequested").asBoolean()).isTrue();
    }

    @Test
    void protectionEnableRejectsAnyStateOtherThanDisabled() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status active = new ScmateController.Status(
                ScmateController.Mode.PROTECTED, false, 0, 0);

        ScmateController.Result result = ScmateController.enableProtection(
                commandPath, active, "operator", "test-password", new ObjectMapper(),
                (command, timeout) -> ScmateController.RestoreExecution.completed(0));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_PROTECTION_NOT_DISABLED:");
    }

    @Test
    void modeRequestResultIncludesVerifiedObservedState() throws Exception {
        ScmateController.Status before = new ScmateController.Status(
                ScmateController.Mode.PROTECTED, false, 0, 0);
        ScmateController.Status after = new ScmateController.Status(
                ScmateController.Mode.OPERATOR, false, 3, 0,
                ScmateController.Schedule.MANUAL,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.AvailabilityReason.ACTIVE);

        ScmateController.Result result = ScmateController.modeRequestCompleted(
                ScmateController.Action.REQUEST_OPERATOR,
                before,
                after,
                ScmateController.Mode.OPERATOR,
                ScmateController.RecoveryPoint.BASELINE,
                new ObjectMapper());

        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(json.path("modeRequestAccepted").asBoolean()).isTrue();
        assertThat(json.path("requestedMode").asText()).isEqualTo("OPERATOR");
        assertThat(json.path("recoveryPoint").asText()).isEqualTo("BASELINE");
        assertThat(json.path("after").path("mode").asText()).isEqualTo("OPERATOR");
        assertThat(json.path("after").path("schedule").asText()).isEqualTo("MANUAL");
    }

    @Test
    void operatorModeAlreadyVisibleCompletesWithoutNativeOrCliExecution() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status operator = new ScmateController.Status(
                ScmateController.Mode.OPERATOR, false, 3, 0,
                ScmateController.Schedule.MANUAL,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.AvailabilityReason.ACTIVE);
        AtomicInteger commandRuns = new AtomicInteger();
        AtomicInteger refreshRuns = new AtomicInteger();

        ScmateController.Result result = ScmateController.executeModeAction(
                commandPath, ScmateController.Action.REQUEST_OPERATOR,
                ScmateController.RecoveryPoint.BASELINE, operator,
                "operator", "test-password", new ObjectMapper(),
                (path, mode, point, user, secret, timeout) -> {
                    commandRuns.incrementAndGet();
                    return ScmateController.RestoreExecution.completed(0);
                }, expectedState -> {
                    refreshRuns.incrementAndGet();
                    return null;
                });

        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(commandRuns).hasValue(0);
        assertThat(refreshRuns).hasValue(0);
        assertThat(json.path("after").path("mode").asText()).isEqualTo("OPERATOR");
    }

    @Test
    void modeActionsDoNotWaitForTheNativeApiLock() {
        assertThat(ScmateController.requiresNativeApiLock(
                ScmateController.Action.REQUEST_OPERATOR)).isFalse();
        assertThat(ScmateController.requiresNativeApiLock(
                ScmateController.Action.REQUEST_PROTECTED)).isFalse();
        assertThat(ScmateController.requiresNativeApiLock(
                ScmateController.Action.STATUS)).isTrue();
        assertThat(ScmateController.requiresNativeApiLock(
                ScmateController.Action.UPDATE_BASELINE)).isTrue();
    }

    @Test
    void modeTransitionAcceptsUnknownPreviousModeWhenClientAndCliAreVerified() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status unknown = new ScmateController.Status(
                ScmateController.Mode.UNKNOWN, false, -1, 0,
                ScmateController.Schedule.UNKNOWN,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.AvailabilityReason.ACTIVE);
        AtomicInteger commandRuns = new AtomicInteger();

        ScmateController.Result result = ScmateController.executeModeAction(
                commandPath, ScmateController.Action.REQUEST_OPERATOR,
                ScmateController.RecoveryPoint.BASELINE, unknown,
                "operator", "test-password", new ObjectMapper(),
                (path, mode, point, user, secret, timeout) -> {
                    commandRuns.incrementAndGet();
                    return ScmateController.RestoreExecution.completed(0);
                }, expectedState -> null);

        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(commandRuns).hasValue(1);
        assertThat(json.path("before").path("mode").asText()).isEqualTo("UNKNOWN");
        assertThat(json.path("after").path("mode").asText()).isEqualTo("OPERATOR");
    }

    @Test
    void protectedModeTransitionUsesCliAndDisplayRefresh() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status protectedMode = new ScmateController.Status(
                ScmateController.Mode.PROTECTED, false, 0, 0,
                ScmateController.Schedule.RESTART,
                ScmateController.RecoveryPoint.BASELINE,
                ScmateController.AvailabilityReason.ACTIVE);
        AtomicInteger commandRuns = new AtomicInteger();
        AtomicInteger refreshedState = new AtomicInteger(-1);

        ScmateController.Result result = ScmateController.executeModeAction(
                commandPath, ScmateController.Action.REQUEST_OPERATOR,
                ScmateController.RecoveryPoint.BASELINE, protectedMode,
                "operator", "test-password", new ObjectMapper(),
                (path, mode, point, user, secret, timeout) -> {
                    commandRuns.incrementAndGet();
                    return ScmateController.RestoreExecution.completed(0);
                }, expectedState -> {
                    refreshedState.set(expectedState);
                    return null;
                });

        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).isZero();
        assertThat(commandRuns).hasValue(1);
        assertThat(refreshedState).hasValue(3);
        assertThat(json.path("before").path("mode").asText()).isEqualTo("PROTECTED");
        assertThat(json.path("after").path("mode").asText()).isEqualTo("OPERATOR");
    }

    @Test
    void modeVerificationFailureRejectsUnconfirmedModeCommand() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status operator = new ScmateController.Status(
                ScmateController.Mode.OPERATOR, false, 3, 0,
                ScmateController.Schedule.MANUAL,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.AvailabilityReason.ACTIVE);

        ScmateController.Result result = ScmateController.executeModeAction(
                commandPath, ScmateController.Action.REQUEST_PROTECTED,
                ScmateController.RecoveryPoint.BASELINE, operator,
                "operator", "test-password", new ObjectMapper(),
                (path, mode, point, user, secret, timeout) ->
                        ScmateController.RestoreExecution.completed(0),
                expectedState -> ScmateController.Result.failure(
                        "SCMATE_MODE_UNVERIFIED", "mode was not confirmed"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_MODE_UNVERIFIED:");
    }

    @Test
    void protectedModeVerificationRequiresRestartTaskForSelectedPoint() {
        Path commandPath = tempDir.resolve("ShdCmd.exe");
        AtomicInteger reads = new AtomicInteger();

        ScmateController.Result result = ScmateController.verifyModeTask(
                commandPath, ScmateController.Mode.PROTECTED,
                ScmateController.RecoveryPoint.BASELINE,
                "operator", "test-password",
                (command, timeout) -> {
                    reads.incrementAndGet();
                    return ScmateController.CommandExecution.completed(0,
                            "1 SCMATEBASE_TASK Reset System Restart Win:SCMATE_BASELINE"
                                    .getBytes(Charset.forName("MS949")));
                }, millis -> {
                });

        assertThat(result.exitCode()).isZero();
        assertThat(reads).hasValue(1);
    }

    @Test
    void protectedModeVerificationRejectsMissingResetTask() {
        Path commandPath = tempDir.resolve("ShdCmd.exe");
        AtomicInteger reads = new AtomicInteger();

        ScmateController.Result result = ScmateController.verifyModeTask(
                commandPath, ScmateController.Mode.PROTECTED,
                ScmateController.RecoveryPoint.BASELINE,
                "operator", "test-password",
                (command, timeout) -> {
                    reads.incrementAndGet();
                    return ScmateController.CommandExecution.completed(0,
                            "0 Defrag Defrag Snapshots Daily@ First bootup"
                                    .getBytes(Charset.forName("MS949")));
                }, millis -> {
                });

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_MODE_UNVERIFIED:");
        assertThat(reads).hasValue(10);
    }

    @Test
    void productACommandsNeverUseResModeFrcmdOrProtect() {
        Path commandPath = tempDir.resolve("ShdCmd.exe");
        List<List<String>> commands = new ArrayList<>();
        for (ScmateController.RecoveryPoint point : List.of(
                ScmateController.RecoveryPoint.BASELINE,
                ScmateController.RecoveryPoint.SNAP1,
                ScmateController.RecoveryPoint.SNAP2)) {
            commands.addAll(ScmateController.buildModeCommands(commandPath,
                    ScmateController.Mode.OPERATOR, point, "user", "secret"));
            commands.addAll(ScmateController.buildModeCommands(commandPath,
                    ScmateController.Mode.PROTECTED, point, "user", "secret"));
            commands.add(ScmateController.buildSnapshotCommand(commandPath, "user", "secret", point));
            commands.add(ScmateController.buildRestoreCommand(commandPath, "user", "secret", point));
        }

        assertThat(commands).allSatisfy(command -> {
            String joined = String.join(" ", command).toLowerCase();
            assertThat(joined).doesNotContain("/pmode", "/resmode", "frcmd", "/protect");
        });
    }

    @Test
    void everyBootCommandsUseSelectedRecoveryPoint() {
        Path commandPath = tempDir.resolve("ShdCmd.exe");

        assertThat(ScmateController.buildModeCommands(commandPath,
                ScmateController.Mode.PROTECTED, ScmateController.RecoveryPoint.SNAP1,
                "user", "secret").get(1)).containsSequence(
                "/name", "SCMATE_SNAP1_TASK", "/n", "Win:SCMATE_SNAP1");
        assertThat(ScmateController.buildModeCommands(commandPath,
                ScmateController.Mode.PROTECTED, ScmateController.RecoveryPoint.SNAP2,
                "user", "secret").get(1)).containsSequence(
                "/name", "SCMATE_SNAP2_TASK", "/n", "Win:SCMATE_SNAP2");
        assertThat(ScmateController.buildSnapshotCommand(commandPath, "user", "secret",
                ScmateController.RecoveryPoint.SNAP2)).containsSequence(
                "/Snapshot", "/n", "SCMATE_SNAP2");
        assertThat(ScmateController.buildRestoreCommand(commandPath, "user", "secret",
                ScmateController.RecoveryPoint.SNAP1)).containsSequence(
                "/Restore", "/n", "Win:SCMATE_SNAP1");
    }

    @Test
    void displayStatesZeroThroughTwelveMapExactly() {
        ScmateController.Schedule[] schedules = {
                ScmateController.Schedule.RESTART,
                ScmateController.Schedule.DAILY_FIRST_BOOT,
                ScmateController.Schedule.UNKNOWN,
                ScmateController.Schedule.MANUAL,
                ScmateController.Schedule.WEEKLY,
                ScmateController.Schedule.RESTART,
                ScmateController.Schedule.RESTART,
                ScmateController.Schedule.DAILY_FIRST_BOOT,
                ScmateController.Schedule.DAILY_FIRST_BOOT,
                ScmateController.Schedule.UNKNOWN,
                ScmateController.Schedule.UNKNOWN,
                ScmateController.Schedule.WEEKLY,
                ScmateController.Schedule.WEEKLY
        };
        ScmateController.RecoveryPoint[] points = {
                ScmateController.RecoveryPoint.BASELINE,
                ScmateController.RecoveryPoint.BASELINE,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.RecoveryPoint.BASELINE,
                ScmateController.RecoveryPoint.SNAP1,
                ScmateController.RecoveryPoint.SNAP2,
                ScmateController.RecoveryPoint.SNAP1,
                ScmateController.RecoveryPoint.SNAP2,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.RecoveryPoint.UNKNOWN,
                ScmateController.RecoveryPoint.SNAP1,
                ScmateController.RecoveryPoint.SNAP2
        };

        for (int raw = 0; raw <= 12; raw++) {
            ScmateController.DisplayDescriptor descriptor =
                    ScmateController.mapDisplayState(raw, false);
            assertThat(descriptor.schedule()).as("raw=%s schedule", raw).isEqualTo(schedules[raw]);
            assertThat(descriptor.selectedPoint()).as("raw=%s point", raw).isEqualTo(points[raw]);
            ScmateController.AvailabilityReason availability = raw == 10
                    ? ScmateController.AvailabilityReason.LICENSE_EXPIRED
                    : raw == 2 || raw == 9
                    ? ScmateController.AvailabilityReason.STATUS_UNAVAILABLE
                    : ScmateController.AvailabilityReason.ACTIVE;
            assertThat(descriptor.availabilityReason()).as("raw=%s availability", raw)
                    .isEqualTo(availability);
        }
        assertThat(ScmateController.mapDisplayState(9, true).availabilityReason())
                .isEqualTo(ScmateController.AvailabilityReason.NOT_INSTALLED);
    }

    @Test
    void refreshDetailReadsListAndListTaskAndReturnsExactJsonContract() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        List<List<String>> invocations = new ArrayList<>();
        String password = "detail-secret";
        ScmateController.Status status = ScmateController.applyDisplayState(
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                OptionalInt.of(5));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(5), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Result result = ScmateController.detailedResult(
                commandPath,
                ScmateController.Action.REFRESH_STATUS_DETAIL,
                status,
                status,
                client,
                "operator",
                password,
                new ObjectMapper(),
                (command, timeout) -> {
                    invocations.add(List.copyOf(command));
                    String output = command.contains("/List")
                            ? "SCMATE_BASELINE 2026-08-01 10:11:12\r\n"
                            + "SCMATE_SNAP1 2026-08-12 13:14:15\r\n"
                            + "SCMATE_SNAP2 2026-08-13 16:17:18\r\n"
                            + "echo " + password
                            : "1 SCMATE_SNAP1_TASK Reset System Restart Win:SCMATE_SNAP1\r\n";
                    return ScmateController.CommandExecution.completed(0,
                            output.getBytes(Charset.forName("MS949")));
                });

        assertThat(result.exitCode()).isZero();
        assertThat(invocations).hasSize(2);
        assertThat(invocations.get(0)).containsSequence("/List", "/u", "operator", "/p", password);
        assertThat(invocations.get(1)).containsSequence("/ListTask", "/u", "operator", "/p", password);
        JsonNode json = new ObjectMapper().readTree(result.stdout());
        List<String> topLevelFields = new ArrayList<>();
        json.fieldNames().forEachRemaining(topLevelFields::add);
        assertThat(topLevelFields).containsExactly(
                "action", "before", "after", "details");
        JsonNode details = json.path("details");
        List<String> detailFields = new ArrayList<>();
        details.fieldNames().forEachRemaining(detailFields::add);
        assertThat(detailFields).containsExactly(
                "baselineCreatedAt", "snap1CreatedAt", "snap2CreatedAt", "schedule",
                "selectedPoint", "availabilityReason", "productVariant", "stateSource");
        assertThat(details.path("baselineCreatedAt").asText()).isEqualTo("2026-08-01T10:11:12");
        assertThat(details.path("snap1CreatedAt").asText()).isEqualTo("2026-08-12T13:14:15");
        assertThat(details.path("snap2CreatedAt").asText()).isEqualTo("2026-08-13T16:17:18");
        assertThat(details.path("schedule").asText()).isEqualTo("RESTART");
        assertThat(details.path("selectedPoint").asText()).isEqualTo("SNAP1");
        assertThat(details.path("availabilityReason").asText()).isEqualTo("ACTIVE");
        assertThat(details.path("productVariant").asText()).isEqualTo("A");
        assertThat(result.stdout()).doesNotContain(password, "operator");
    }

    @Test
    void parsesRealVendorListWithBlankLinesAndKoreanAmPrefix() {
        String listOutput = """
                Id: 0\r
                \r
                Snapshot: Installation\r
                \r
                Quality: Good\r
                \r
                Created Time: 2026-08-14 오전 4:47:27\r
                \r
                Id: 2*\r
                \r
                Snapshot: Win:SCMATE_BASELINE\r
                \r
                Quality: Good\r
                \r
                Status: Unlocked\r
                \r
                Size: 428MB\r
                \r
                Created Time: 2026-08-14 오전 5:27:24\r
                """;
        ScmateController.Status operator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Details details = ScmateController.parseDetails(
                listOutput, "", operator, client);

        assertThat(details.baselineCreatedAt()).isEqualTo("2026-08-14T05:27:24");
    }

    @Test
    void selectsNewestTimestampWhenVendorRetainsDuplicateSnapshotNames() {
        String listOutput = """
                Id: 1
                Snapshot: Win:SCMATE_BASELINE
                Created Time: 2026-08-14 오전 8:54:05
                Id: 2
                Snapshot: Win:SCMATE_BASELINE
                Created Time: 2026-08-18 오전 9:07:04
                Id: 3*
                Snapshot: Win:SCMATE_BASELINE
                Created Time: 2026-08-18 오전 9:14:41
                """;
        ScmateController.Status operator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Details details = ScmateController.parseDetails(
                listOutput, "", operator, client);

        assertThat(details.baselineCreatedAt()).isEqualTo("2026-08-18T09:14:41");
    }

    @Test
    void updateResultPreservesRequestedPointWhenOperatorDisplayHasNoSelectedPoint() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        ScmateController.Status operator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Result result = ScmateController.detailedResult(
                commandPath, ScmateController.Action.UPDATE_BASELINE,
                operator, operator, client, "operator", "secret",
                ScmateController.RecoveryPoint.SNAP2, new ObjectMapper(),
                (command, timeout) -> ScmateController.CommandExecution.completed(
                        0, "SCMATE_SNAP2 2026-08-13 18:00:00".getBytes(Charset.forName("MS949"))));

        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(json.path("details").path("selectedPoint").asText()).isEqualTo("SNAP2");
        assertThat(json.path("details").path("snap2CreatedAt").asText())
                .isEqualTo("2026-08-13T18:00:00");
        assertThat(json.path("after").path("selectedPoint").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void diagnosticDetailReturnsSanitizedVendorOutput() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd-diagnostic.exe"));
        String password = "diagnostic-secret";
        ScmateController.Status operator = ScmateController.applyDisplayState(
                ScmateController.decodeStatus(0, 0), OptionalInt.of(3));
        ScmateClientDisplayStateReader.Snapshot client = new ScmateClientDisplayStateReader.Snapshot(
                OptionalInt.of(3), true, true, true, true, true,
                "A", 101, 1234L);

        ScmateController.Result result = ScmateController.diagnosticDetailedResult(
                commandPath, ScmateController.Action.DIAGNOSTIC_STATUS_DETAIL,
                operator, operator, client, "operator", password, new ObjectMapper(),
                (command, timeout) -> {
                    String output = command.contains("/List")
                            ? "point=BASELINE\r\ncreated=2026-08-14 05:40:00\r\n"
                            + "credential=" + password
                            : "restart SCMATEBASE_TASK";
                    return ScmateController.CommandExecution.completed(
                            0, output.getBytes(Charset.forName("MS949")));
                });

        assertThat(result.exitCode()).isZero();
        JsonNode json = new ObjectMapper().readTree(result.stdout());
        assertThat(json.path("diagnostic").path("listOutput").asText())
                .contains("point=BASELINE", "created=2026-08-14 05:40:00")
                .doesNotContain(password, "operator");
        assertThat(json.path("diagnostic").path("taskOutput").asText())
                .contains("SCMATEBASE_TASK");
        assertThat(result.stdout()).doesNotContain(password, "operator");
    }

    @Test
    void windowsOutputDecoderSupportsCp949() {
        String korean = "생성 시간 2026-08-13 09:10:11";
        assertThat(ScmateController.decodeWindowsOutput(korean.getBytes(Charset.forName("MS949"))))
                .isEqualTo(korean);
    }

    @Test
    void statusIncludesProductVariantAndStateSource() throws Exception {
        ScmateController.Result result = ScmateController.execute(
                "STATUS", null, null, new ObjectMapper());

        assertThat(result.exitCode()).isZero();
        JsonNode after = new ObjectMapper().readTree(result.stdout()).path("after");
        assertThat(after.has("productVariant")).isTrue();
        assertThat(after.has("stateSource")).isTrue();
    }

    @Test
    void payloadRecoveryPointDefaultsToBaselineAndAcceptsAllContractValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(ScmateController.RecoveryPoint.fromPayload(mapper.readTree("{}")))
                .isEqualTo(ScmateController.RecoveryPoint.BASELINE);
        assertThat(ScmateController.RecoveryPoint.fromPayload(
                mapper.readTree("{\"recoveryPoint\":\"snap1\"}")))
                .isEqualTo(ScmateController.RecoveryPoint.SNAP1);
        assertThat(ScmateController.RecoveryPoint.fromPayload(
                mapper.readTree("{\"recoveryPoint\":\"SNAP2\"}")))
                .isEqualTo(ScmateController.RecoveryPoint.SNAP2);
    }

    @Test
    void recoveryModuleAvailabilityUsesOfficialUninstalledFlag() {
        assertThat(ScmateController.recoveryModuleInstalled(0)).isTrue();
        assertThat(ScmateController.recoveryModuleInstalled(1)).isFalse();
        assertThat(ScmateController.recoveryModuleInstalled(-1)).isFalse();
    }

    @Test
    void unknownNativeStateIsRejectedInsteadOfReportedAsAValidMode() {
        assertThatThrownBy(() -> ScmateController.decodeStatus(4, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected SCMATE work mode state");
    }

    @Test
    void restoreBaselineUsesOfficialDirectCommandAndBoundedTimeout() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();

        ScmateController.Result result = ScmateController.restoreBaseline(
                commandPath,
                "operator",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                new ObjectMapper(),
                (path, username, password, boundedTimeout) -> {
                    arguments.set(ScmateController.buildRestoreCommand(path, username, password));
                    timeout.set(boundedTimeout);
                    return ScmateController.RestoreExecution.completed(0);
                });

        assertThat(result.exitCode()).isZero();
        assertThat(arguments.get()).containsExactly(
                commandPath.toString(), "/Restore", "/n", "Win:SCMATE_BASELINE",
                "/u", "operator", "/p", "test-password");
        assertThat(arguments.get().getFirst()).isEqualTo(commandPath.toString());
        assertThat(arguments.get()).noneMatch(argument -> argument.equalsIgnoreCase("cmd.exe"));
        assertThat(arguments.get()).noneMatch(argument -> argument.equalsIgnoreCase("/shutdown"));
        assertThat(arguments.get()).noneMatch(argument -> argument.equalsIgnoreCase("/noreboot"));
        assertThat(timeout.get()).isEqualTo(Duration.ofSeconds(30));
        assertThat(result.stdout()).contains("\"restoreRequested\":true", "\"rebootRequested\":true");
    }

    @Test
    void restoreBaselineRejectsTransitionBeforeStartingCommand() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));

        ScmateController.Result result = ScmateController.restoreBaseline(
                commandPath,
                "operator",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.OPERATOR, true, 3, 9),
                new ObjectMapper(),
                (path, username, password, timeout) -> {
                    throw new AssertionError("restore command must not start during a transition");
                });

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_UNSAFE_STATE:");
        assertThat(result.stderr()).doesNotContain("operator", "test-password");
    }

    @Test
    void restoreBaselineReturnsSanitizedTimeoutResult() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));

        ScmateController.Result result = ScmateController.restoreBaseline(
                commandPath,
                "operator",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                new ObjectMapper(),
                (path, username, password, timeout) -> ScmateController.RestoreExecution.timeout());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo(
                "SCMATE_RESTORE_TIMEOUT: SCMATE restore request timed out.");
        assertThat(result.stderr()).doesNotContain("operator", "test-password", commandPath.toString());
    }

    @Test
    void restoreBaselineReturnsSanitizedNonZeroExitResult() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));

        ScmateController.Result result = ScmateController.restoreBaseline(
                commandPath,
                "operator",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                new ObjectMapper(),
                (path, username, password, timeout) -> ScmateController.RestoreExecution.completed(7));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).isEqualTo(
                "SCMATE_RESTORE_FAILED: SCMATE restore request failed with exit code 7.");
        assertThat(result.stderr()).doesNotContain("operator", "test-password", commandPath.toString());
    }

    @Test
    void restoreBaselineRequiresAuthentication() {
        assertThat(ScmateController.Action.RESTORE_BASELINE.requiresAuthentication()).isTrue();
    }

    @Test
    void updateBaselineUsesOfficialRemoteSnapshotCommandWithoutReboot() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();

        ScmateController.Result result = ScmateController.updateBaseline(
                commandPath,
                "operator",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.OPERATOR, false, 1, 0),
                new ObjectMapper(),
                (path, username, password, boundedTimeout) -> {
                    arguments.set(ScmateController.buildSnapshotCommand(path, username, password));
                    timeout.set(boundedTimeout);
                    return ScmateController.RestoreExecution.completed(0);
                });

        assertThat(result.exitCode()).isZero();
        assertThat(arguments.get()).containsExactly(
                commandPath.toString(), "/Snapshot", "/n", "SCMATE_BASELINE",
                "/u", "operator", "/p", "test-password");
        assertThat(arguments.get()).noneMatch(argument -> argument.equalsIgnoreCase("/reboot"));
        assertThat(timeout.get()).isEqualTo(Duration.ofMinutes(10));
        assertThat(result.stdout()).contains(
                "\"snapshotRequested\":true",
                "\"rebootRequested\":false");
    }

    @Test
    void updateBaselineRejectsNonOperatorStateBeforeStartingCommand() throws Exception {
        Path commandPath = Files.createFile(tempDir.resolve("ShdCmd.exe"));

        ScmateController.Result result = ScmateController.updateBaseline(
                commandPath,
                "cli-user",
                "test-password",
                new ScmateController.Status(ScmateController.Mode.PROTECTED, false, 0, 0),
                new ObjectMapper(),
                (path, username, password, timeout) -> {
                    throw new AssertionError("snapshot command must not start outside stable operator mode");
                });

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_UNSAFE_STATE:");
        assertThat(result.stderr()).doesNotContain("cli-user", "test-password");
    }

    @Test
    void mutatingActionRetriesTemporaryOpenFailureUntilScmateIsReady() {
        AtomicInteger attempts = new AtomicInteger();

        ScmateController.Result result = ScmateController.retryUntilReady(
                ScmateController.Action.UPDATE_BASELINE,
                () -> attempts.incrementAndGet() < 3
                        ? new ScmateController.Result(1, "", "SCMATE_OPEN_FAILED: temporary")
                        : new ScmateController.Result(0, "ready", ""),
                Duration.ofSeconds(5),
                Duration.ZERO,
                millis -> { });

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("ready");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void statusProbeDoesNotWaitWhenScmateIsStillStarting() {
        AtomicInteger attempts = new AtomicInteger();

        ScmateController.Result result = ScmateController.retryUntilReady(
                ScmateController.Action.STATUS,
                () -> {
                    attempts.incrementAndGet();
                    return new ScmateController.Result(1, "", "SCMATE_OPEN_FAILED: temporary");
                },
                Duration.ofMinutes(2),
                Duration.ofSeconds(5),
                millis -> { throw new AssertionError("STATUS must not sleep"); });

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void mutatingActionReturnsBoundedReadinessTimeout() {
        ScmateController.Result result = ScmateController.retryUntilReady(
                ScmateController.Action.REQUEST_OPERATOR,
                () -> new ScmateController.Result(1, "", "SCMATE_OPEN_FAILED: ShdOpen failed"),
                Duration.ZERO,
                Duration.ofSeconds(5),
                millis -> { throw new AssertionError("zero timeout must not sleep"); });

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).startsWith("SCMATE_READY_TIMEOUT:");
        assertThat(result.stderr()).contains("SCMATE_OPEN_FAILED");
    }

    @Test
    void mutatingActionRetriesTransientStatusUnavailableAfterModeChange() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();

        ScmateController.Result result = ScmateController.retryUntilReady(
                ScmateController.Action.REQUEST_OPERATOR,
                () -> attempts.incrementAndGet() == 1
                        ? new ScmateController.Result(1, "", "SCMATE_STATUS_UNAVAILABLE: starting")
                        : new ScmateController.Result(0, "ok", ""),
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                millis -> sleeps.incrementAndGet());

        assertThat(result.exitCode()).isZero();
        assertThat(attempts).hasValue(2);
        assertThat(sleeps).hasValue(1);
    }
}
