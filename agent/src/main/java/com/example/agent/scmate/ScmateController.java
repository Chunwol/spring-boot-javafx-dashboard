package com.example.agent.scmate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ScmateController {

    private static final Path API_PATH = Path.of(
            "C:\\Program Files\\SCMATE_RX2Client\\Shield\\ShdApi.dll");
    private static final Path COMMAND_PATH = Path.of(
            "C:\\Program Files\\SCMATE_RX2Client\\Shield\\ShdCmd.exe");
    private static final Path CLIENT_PATH = Path.of(
            "C:\\Program Files\\SCMATE_RX2Client\\SCMateClient.exe");
    private static final Duration RESTORE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration MODE_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DETAIL_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RESTORE_STOP_GRACE = Duration.ofSeconds(2);
    private static final Duration SCHEDULED_TASK_COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration READY_RETRY_INTERVAL = Duration.ofSeconds(5);
    private static final int MODE_VERIFY_ATTEMPTS = 10;
    private static final long MODE_VERIFY_INTERVAL_MILLIS = 1_000L;
    private static final int DETAIL_OUTPUT_LIMIT = 256 * 1024;
    private static final String SHD_OPEN = "?ShdOpen@@YAJXZ";
    private static final String SHD_CLOSE = "?ShdClose@@YAJXZ";
    private static final String SHD_IS_UNINSTALLED = "?ShdIsUninstalled@@YAHXZ";
    private static final String SHD_LOGON = "?ShdLogonUser@@YAJPEB_W0@Z";
    private static final String SHD_GET_INFO = "?ShdGetInfo@@YAJKPEAXK@Z";
    private static final int WORK_MODE_STATE_INFO_CLASS = 0x42;
    private static final ReentrantLock API_LOCK = new ReentrantLock();
    private static final Pattern VENDOR_TIMESTAMP = Pattern.compile(
            "(?i)(\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}"
                    + "(?:\\s+(?:(?:AM|PM|오전|오후)\\s+)?\\d{1,2}:\\d{2}(?::\\d{2})?)?"
                    + "(?:\\s+(?:AM|PM|오전|오후))?)");
    private static final DateTimeFormatter DETAIL_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    private ScmateController() {
    }

    /** Existing callers remain BASELINE-compatible. */
    public static Result execute(String rawAction,
                                 String username,
                                 String password,
                                 ObjectMapper mapper) {
        return execute(rawAction, null, username, password, mapper);
    }

    public static Result execute(String rawAction,
                                 JsonNode payload,
                                 String username,
                                 String password,
                                 ObjectMapper mapper) {
        Action action;
        RecoveryPoint recoveryPoint;
        try {
            action = Action.parse(rawAction);
            recoveryPoint = RecoveryPoint.fromPayload(payload);
        } catch (IllegalArgumentException invalid) {
            return Result.failure("SCMATE_INVALID_REQUEST", invalid.getMessage());
        }
        RecoveryPoint selectedPoint = recoveryPoint;
        return retryUntilReady(action,
                () -> executeOnce(action, selectedPoint, username, password, mapper),
                READY_TIMEOUT, READY_RETRY_INTERVAL, Thread::sleep);
    }

    private static Result executeOnce(Action action,
                                      RecoveryPoint recoveryPoint,
                                      String username,
                                      String password,
                                      ObjectMapper mapper) {
        if (!requiresNativeApiLock(action)) {
            return executeLocked(action, recoveryPoint, username, password, mapper);
        }
        API_LOCK.lock();
        try {
            return executeLocked(action, recoveryPoint, username, password, mapper);
        } finally {
            API_LOCK.unlock();
        }
    }

    static boolean requiresNativeApiLock(Action action) {
        return action != Action.REQUEST_OPERATOR && action != Action.REQUEST_PROTECTED;
    }

    static Result retryUntilReady(Action action,
                                  Supplier<Result> attempt,
                                  Duration timeout,
                                  Duration retryInterval,
                                  Sleeper sleeper) {
        Result result = attempt.get();
        if (action.isReadOnly() || !isTransientReadyFailure(result)) {
            return result;
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                sleeper.sleep(retryInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.failure("SCMATE_READY_INTERRUPTED", "SCMATE readiness wait was interrupted.");
            }
            result = attempt.get();
            if (!isTransientReadyFailure(result)) {
                return result;
            }
        }
        return Result.failure("SCMATE_READY_TIMEOUT",
                "SCMATE did not become ready within " + timeout.toSeconds() + " seconds. " + result.stderr());
    }

    private static boolean isTransientReadyFailure(Result result) {
        if (result == null || result.stderr() == null) return false;
        return result.stderr().startsWith("SCMATE_OPEN_FAILED:")
                || result.stderr().startsWith("SCMATE_STATUS_UNAVAILABLE:");
    }

    private static Result executeLocked(Action action,
                                        RecoveryPoint recoveryPoint,
                                        String username,
                                        String password,
                                        ObjectMapper mapper) {
        if (action == Action.REQUEST_OPERATOR || action == Action.REQUEST_PROTECTED) {
            return executeModeWithoutNativeInspection(
                    action, recoveryPoint, username, password, mapper);
        }
        ScmateClientDisplayStateReader.Snapshot client = ScmateClientDisplayStateReader.inspect();
        if (!isWindows()) {
            if (action == Action.STATUS) {
                return statusResult(action, Status.unknown(AvailabilityReason.STATUS_UNAVAILABLE),
                        client, false, mapper);
            }
            return Result.failure("SCMATE_NOT_SUPPORTED", "SCMATE control is available only on Windows.");
        }
        if (!client.supportedProductA()) {
            if (action == Action.STATUS) {
                Status status = statusWithoutNativeApi(client, AvailabilityReason.STATUS_UNAVAILABLE);
                return statusResult(action, status, client, false, mapper);
            }
            return Result.failure("SCMATE_UNSUPPORTED_CLIENT",
                    "SCMATE v12.95 product A client verification failed.");
        }
        if (!Files.isRegularFile(API_PATH)) {
            if (action == Action.INSTALL_RECOVERY_MODULE) {
                return ScmateRecoveryInstaller.install(mapper);
            }
            AvailabilityReason reason = client.clientFilePresent()
                    ? AvailabilityReason.NOT_INSTALLED
                    : AvailabilityReason.STATUS_UNAVAILABLE;
            Status status = statusWithoutNativeApi(client, reason);
            if (action == Action.STATUS) {
                return statusResult(action, status, client, false, mapper);
            }
            return Result.failure(reason == AvailabilityReason.NOT_INSTALLED
                            ? "SCMATE_NOT_INSTALLED" : "SCMATE_STATUS_UNAVAILABLE",
                    reason == AvailabilityReason.NOT_INSTALLED
                            ? "SCMATE recovery module is not installed."
                            : "SCMATE status evidence is unavailable.");
        }

        NativeLibrary library = null;
        Function close = null;
        boolean opened = false;
        try {
            library = NativeLibrary.getInstance(API_PATH.toString());
            Function open = library.getFunction(SHD_OPEN);
            close = library.getFunction(SHD_CLOSE);
            int openResult = open.invokeInt(new Object[0]);
            if (openResult != 0) {
                return Result.failure("SCMATE_OPEN_FAILED", hresult("ShdOpen", openResult));
            }
            opened = true;

            int uninstalled = library.getFunction(SHD_IS_UNINSTALLED).invokeInt(new Object[0]);
            if (!recoveryModuleInstalled(uninstalled)) {
                if (action == Action.INSTALL_RECOVERY_MODULE) {
                    return ScmateRecoveryInstaller.install(mapper);
                }
                Status unavailable = applyDisplayState(
                        Status.unknown(AvailabilityReason.NOT_INSTALLED), client.displayState(), true);
                if (action == Action.STATUS) {
                    return statusResult(action, unavailable, client, true, mapper);
                }
                return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE recovery module is not installed.");
            }
            if (action == Action.INSTALL_RECOVERY_MODULE) {
                return Result.failure("SCMATE_ALREADY_INSTALLED", "SCMATE recovery module is already installed.");
            }

            Status before = applyDisplayState(readStatus(library), client.displayState(), false);
            if (action == Action.STATUS) {
                return statusResult(action, before, client, true, mapper);
            }
            if (action == Action.ENABLE_PROTECTION) {
                if (before.availabilityReason() != AvailabilityReason.PROTECT_DISABLED) {
                    if (before.availabilityReason() == AvailabilityReason.ACTIVE) {
                        return statusResult(action, before, client, true, mapper);
                    }
                    return Result.failure("SCMATE_PROTECTION_NOT_DISABLED",
                            "SCMATE protection is not in the disabled state.");
                }
                Result authFailure = authenticate(library, username, password);
                if (authFailure != null) {
                    return authFailure;
                }

                closeNativeApi(library, close, opened);
                opened = false;
                close = null;
                library = null;
                return enableProtection(COMMAND_PATH, before, username, password, mapper,
                        ScmateController::runDirectCommand);
            }
            if (!action.isReadOnly()
                    && before.availabilityReason() == AvailabilityReason.LICENSE_EXPIRED) {
                return Result.failure("SCMATE_LICENSE_EXPIRED", "SCMATE recovery license has expired.");
            }
            if (!action.isReadOnly() && before.availabilityReason() != AvailabilityReason.ACTIVE) {
                return Result.failure("SCMATE_STATUS_UNAVAILABLE", "SCMATE status is unavailable.");
            }
            if (action.requiresAuthentication()) {
                Result authFailure = authenticate(library, username, password);
                if (authFailure != null) {
                    return authFailure;
                }
            }

            switch (action) {
                case REFRESH_STATUS_DETAIL -> {
                    Status after = readEffectiveStatus(library);
                    return detailedResult(COMMAND_PATH, action, before, after, client, username, password, mapper,
                            ScmateController::runCapturedCommand);
                }
                case DIAGNOSTIC_STATUS_DETAIL -> {
                    Status after = readEffectiveStatus(library);
                    return diagnosticDetailedResult(COMMAND_PATH, action, before, after, client,
                            username, password, mapper, ScmateController::runCapturedCommand);
                }
                case UPDATE_BASELINE -> {
                    if (before.mode() != Mode.OPERATOR || before.transitionPending()) {
                        return Result.failure("SCMATE_UNSAFE_STATE",
                                "Snapshot update requires stable operator mode.");
                    }
                    Result update = updateBaseline(COMMAND_PATH, username, password, before,
                            recoveryPoint, mapper,
                            (path, user, secret, timeout) ->
                                    runSnapshotCommand(path, user, secret, recoveryPoint, timeout));
                    if (update.exitCode() != 0) return update;
                    Status after = readEffectiveStatus(library);
                    return detailedResult(COMMAND_PATH, action, before, after,
                            ScmateClientDisplayStateReader.inspect(), username, password,
                            recoveryPoint, mapper, ScmateController::runCapturedCommand);
                }
                case RESTORE_BASELINE -> {
                    Status authenticatedStatus = readStatus(library);
                    return restoreBaseline(COMMAND_PATH, username, password, authenticatedStatus,
                            recoveryPoint, mapper,
                            (path, user, secret, timeout) ->
                                    runRestoreCommand(path, user, secret, recoveryPoint, timeout));
                }
                case STATUS, INSTALL_RECOVERY_MODULE, ENABLE_PROTECTION,
                        REQUEST_OPERATOR, REQUEST_PROTECTED ->
                        throw new IllegalStateException(
                        "SCMATE action dispatch reached an invalid state.");
            }
            throw new IllegalStateException("SCMATE action did not produce a result.");
        } catch (ScmateStatusException error) {
            return Result.failure("SCMATE_STATUS_FAILED", error.getMessage());
        } catch (Throwable error) {
            return Result.failure("SCMATE_API_ERROR", "SCMATE API call failed: "
                    + error.getClass().getSimpleName());
        } finally {
            closeNativeApi(library, close, opened);
        }
    }

    private static void closeNativeApi(NativeLibrary library, Function close, boolean opened) {
        if (opened && close != null) {
            try {
                close.invokeInt(new Object[0]);
            } catch (Throwable ignored) {
            }
        }
        if (library != null) {
            try {
                library.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static Result modeRequestCompleted(Action action,
                                       Status before,
                                       Status after,
                                       Mode requestedMode,
                                       RecoveryPoint recoveryPoint,
                                       ObjectMapper mapper) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", action.name());
        output.put("before", before.toMap());
        output.put("after", after.toMap());
        output.put("requestedMode", requestedMode.name());
        output.put("recoveryPoint", recoveryPoint.name());
        output.put("modeRequestAccepted", true);
        output.put("apiResult", 0);
        return Result.success(writeJson(mapper, output));
    }

    static Result executeModeAction(Path commandPath,
                                    Action action,
                                    RecoveryPoint recoveryPoint,
                                    Status before,
                                    String username,
                                    String password,
                                    ObjectMapper mapper,
                                    ModeCommandRunner runner,
                                    DisplayRefresher displayRefresher) {
        if (before.availabilityReason() != AvailabilityReason.ACTIVE) {
            return Result.failure("SCMATE_STATUS_UNAVAILABLE",
                    "SCMATE client display state is unavailable.");
        }
        Mode requestedMode = action == Action.REQUEST_OPERATOR
                ? Mode.OPERATOR : Mode.PROTECTED;
        int expectedDisplayState = requestedMode == Mode.OPERATOR
                ? 3 : protectedDisplayState(recoveryPoint);
        if (before.mode() == requestedMode
                && before.raw() == expectedDisplayState
                && !before.transitionPending()) {
            return modeRequestCompleted(action, before, before,
                    requestedMode, recoveryPoint, mapper);
        }

        Result failure = requestMode(commandPath, before, requestedMode,
                recoveryPoint, username, password, runner);
        if (failure != null) return failure;
        Result verification = displayRefresher.refresh(expectedDisplayState);
        if (verification != null && verification.exitCode() != 0) {
            return verification;
        }
        Status after = requestedMode == Mode.OPERATOR
                ? operatorStatus(before)
                : protectedStatus(before, recoveryPoint, expectedDisplayState);
        return modeRequestCompleted(action, before, after,
                requestedMode, recoveryPoint, mapper);
    }

    private static Result executeModeWithoutNativeInspection(Action action,
                                                             RecoveryPoint recoveryPoint,
                                                             String username,
                                                             String password,
                                                             ObjectMapper mapper) {
        if (!isWindows()) {
            return Result.failure("SCMATE_NOT_SUPPORTED",
                    "SCMATE control is available only on Windows.");
        }
        ScmateClientDisplayStateReader.FileVerification clientFile =
                ScmateClientDisplayStateReader.verifyClientFile(CLIENT_PATH);
        if (!clientFile.supportedHash()) {
            return Result.failure("SCMATE_UNSUPPORTED_CLIENT",
                    "SCMATE v12.95 product A client verification failed.");
        }
        if (!Files.isRegularFile(API_PATH) || !Files.isRegularFile(COMMAND_PATH)) {
            return Result.failure("SCMATE_NOT_INSTALLED",
                    "SCMATE recovery module is not installed.");
        }
        Status before = new Status(Mode.UNKNOWN, false, -1, 0,
                Schedule.UNKNOWN, RecoveryPoint.UNKNOWN, AvailabilityReason.ACTIVE);
        return executeModeAction(COMMAND_PATH, action, recoveryPoint, before,
                username, password, mapper,
                (path, mode, point, user, secret, timeout) ->
                        runModeCommand(path, mode, point, user, secret, timeout),
                ignored -> verifyModeTask(COMMAND_PATH,
                        action == Action.REQUEST_OPERATOR ? Mode.OPERATOR : Mode.PROTECTED,
                        recoveryPoint, username, password,
                        ScmateController::runCapturedCommand, Thread::sleep));
    }

    private static Status operatorStatus(Status previous) {
        return new Status(Mode.OPERATOR, false, 3, previous.auxiliaryRaw(),
                Schedule.MANUAL, RecoveryPoint.UNKNOWN, AvailabilityReason.ACTIVE);
    }

    private static Status protectedStatus(Status previous,
                                          RecoveryPoint recoveryPoint,
                                          int displayState) {
        return new Status(Mode.PROTECTED, false, displayState, previous.auxiliaryRaw(),
                Schedule.RESTART, recoveryPoint, AvailabilityReason.ACTIVE);
    }

    private static Result authenticate(NativeLibrary library, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Result.failure("SCMATE_CREDENTIAL_MISSING", "SCMATE credential is not configured.");
        }
        Function logon = library.getFunction(SHD_LOGON);
        Memory usernameMemory = wideString(username);
        Memory passwordMemory = wideString(password);
        try {
            int logonResult = logon.invokeInt(new Object[]{usernameMemory, passwordMemory});
            return logonResult == 0 ? null
                    : Result.failure("SCMATE_AUTH_FAILED", hresult("SCMATE authentication", logonResult));
        } finally {
            usernameMemory.clear();
            passwordMemory.clear();
            usernameMemory.close();
            passwordMemory.close();
        }
    }

    private static Memory wideString(String value) {
        Memory memory = new Memory((long) (value.length() + 1) * Native.WCHAR_SIZE);
        memory.clear();
        memory.setWideString(0, value);
        return memory;
    }

    private static Result statusResult(Action action,
                                       Status status,
                                       ScmateClientDisplayStateReader.Snapshot client,
                                       boolean nativeApiAvailable,
                                       ObjectMapper mapper) {
        Map<String, Object> statusMap = status.toMap();
        statusMap.put("productVariant", client.productVariant());
        statusMap.put("stateSource", statusStateSource(client, nativeApiAvailable));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", action.name());
        output.put("before", statusMap);
        output.put("after", new LinkedHashMap<>(statusMap));
        return Result.success(writeJson(mapper, output));
    }

    private static String statusStateSource(ScmateClientDisplayStateReader.Snapshot client,
                                            boolean nativeApiAvailable) {
        if (client.processPathVerified() && client.displayState().isPresent()) {
            return nativeApiAvailable ? "SCMATE_CLIENT_MEMORY+SHD_API" : "SCMATE_CLIENT_MEMORY";
        }
        if (client.clientHashVerified()) {
            return nativeApiAvailable ? "SCMATE_CLIENT_BINARY+SHD_API" : "SCMATE_CLIENT_BINARY";
        }
        return nativeApiAvailable ? "SHD_API" : "STATUS_UNAVAILABLE";
    }

    static Result detailedResult(Path commandPath,
                                 Action action,
                                 Status before,
                                 Status after,
                                 ScmateClientDisplayStateReader.Snapshot client,
                                 String username,
                                 String password,
                                 ObjectMapper mapper,
                                 DetailCommandRunner runner) {
        return detailedResult(commandPath, action, before, after, client, username, password,
                RecoveryPoint.UNKNOWN, mapper, runner);
    }

    static Result detailedResult(Path commandPath,
                                 Action action,
                                 Status before,
                                 Status after,
                                 ScmateClientDisplayStateReader.Snapshot client,
                                 String username,
                                 String password,
                                 RecoveryPoint requestedPoint,
                                 ObjectMapper mapper,
                                 DetailCommandRunner runner) {
        DetailRead detailRead = readDetails(commandPath, username, password, after, client, runner);
        if (!detailRead.success()) {
            return Result.failure(detailRead.errorCode(), detailRead.errorMessage());
        }
        Details details = detailRead.details();
        if (action == Action.UPDATE_BASELINE && requestedPoint != RecoveryPoint.UNKNOWN) {
            details = details.withSelectedPoint(requestedPoint);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", action.name());
        output.put("before", before.toMap());
        output.put("after", after.toMap());
        output.put("details", details.toMap());
        return Result.success(writeJson(mapper, output));
    }

    static DetailRead readDetails(Path commandPath,
                                  String username,
                                  String password,
                                  Status status,
                                  ScmateClientDisplayStateReader.Snapshot client,
                                  DetailCommandRunner runner) {
        if (!Files.isRegularFile(commandPath)) {
            return DetailRead.failure("SCMATE_NOT_INSTALLED", "SCMATE detail command is not installed.");
        }
        CommandExecution list = runDetailCommand(
                authenticatedCommand(commandPath, List.of("/List"), username, password), runner);
        if (!list.successful()) {
            return detailCommandFailure("LIST", list);
        }
        CommandExecution tasks = runDetailCommand(
                authenticatedCommand(commandPath, List.of("/ListTask"), username, password), runner);
        if (!tasks.successful()) {
            wipe(list.output());
            return detailCommandFailure("LIST_TASK", tasks);
        }

        String listText = sanitizeOutput(decodeWindowsOutput(list.output()), username, password);
        String taskText = sanitizeOutput(decodeWindowsOutput(tasks.output()), username, password);
        wipe(list.output());
        wipe(tasks.output());
        return DetailRead.success(parseDetails(listText, taskText, status, client));
    }

    static Result diagnosticDetailedResult(Path commandPath,
                                           Action action,
                                           Status before,
                                           Status after,
                                           ScmateClientDisplayStateReader.Snapshot client,
                                           String username,
                                           String password,
                                           ObjectMapper mapper,
                                           DetailCommandRunner runner) {
        if (!Files.isRegularFile(commandPath)) {
            return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE detail command is not installed.");
        }
        CommandExecution list = runDetailCommand(
                authenticatedCommand(commandPath, List.of("/List"), username, password), runner);
        if (!list.successful()) {
            DetailRead failure = detailCommandFailure("LIST", list);
            return Result.failure(failure.errorCode(), failure.errorMessage());
        }
        CommandExecution tasks = runDetailCommand(
                authenticatedCommand(commandPath, List.of("/ListTask"), username, password), runner);
        if (!tasks.successful()) {
            wipe(list.output());
            DetailRead failure = detailCommandFailure("LIST_TASK", tasks);
            return Result.failure(failure.errorCode(), failure.errorMessage());
        }

        String listText = sanitizeOutput(decodeWindowsOutput(list.output()), username, password);
        String taskText = sanitizeOutput(decodeWindowsOutput(tasks.output()), username, password);
        wipe(list.output());
        wipe(tasks.output());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", action.name());
        output.put("before", before.toMap());
        output.put("after", after.toMap());
        output.put("details", parseDetails(listText, taskText, after, client).toMap());
        output.put("diagnostic", Map.of(
                "listOutput", listText,
                "taskOutput", taskText));
        return Result.success(writeJson(mapper, output));
    }

    private static CommandExecution runDetailCommand(List<String> command, DetailCommandRunner runner) {
        try {
            return runner.run(command, DETAIL_COMMAND_TIMEOUT);
        } finally {
            Collections.fill(command, "");
        }
    }

    private static DetailRead detailCommandFailure(String operation, CommandExecution execution) {
        wipe(execution.output());
        if (execution.timedOut()) {
            return DetailRead.failure("SCMATE_DETAIL_TIMEOUT", "SCMATE " + operation + " request timed out.");
        }
        if (!execution.started()) {
            return DetailRead.failure("SCMATE_DETAIL_START_FAILED",
                    "SCMATE " + operation + " command could not be started.");
        }
        return DetailRead.failure("SCMATE_DETAIL_FAILED",
                "SCMATE " + operation + " request failed with exit code " + execution.exitCode() + ".");
    }

    static Details parseDetails(String listOutput,
                                String taskOutput,
                                Status status,
                                ScmateClientDisplayStateReader.Snapshot client) {
        Schedule schedule = status.schedule();
        RecoveryPoint selectedPoint = status.selectedPoint();
        ParsedTask parsedTask = parseTask(taskOutput);
        if (parsedTask.schedule() != Schedule.UNKNOWN) {
            schedule = parsedTask.schedule();
            selectedPoint = parsedTask.selectedPoint();
        } else {
            schedule = Schedule.MANUAL;
            selectedPoint = RecoveryPoint.UNKNOWN;
        }
        String stateSource = statusStateSource(client, true) + "+SHD_CMD";
        return new Details(
                findPointTimestamp(listOutput, RecoveryPoint.BASELINE),
                findPointTimestamp(listOutput, RecoveryPoint.SNAP1),
                findPointTimestamp(listOutput, RecoveryPoint.SNAP2),
                schedule,
                selectedPoint,
                status.availabilityReason(),
                client.productVariant(),
                stateSource);
    }

    private static ParsedTask parseTask(String output) {
        String normalized = Arrays.stream((output == null ? "" : output).split("\\R"))
                .filter(line -> {
                    String upper = line.toUpperCase(Locale.ROOT);
                    return upper.contains("RESET") || upper.contains("ROLLBACK");
                })
                .collect(Collectors.joining("\n"))
                .toUpperCase(Locale.ROOT);
        RecoveryPoint point = normalized.contains("SCMATE_SNAP2") ? RecoveryPoint.SNAP2
                : normalized.contains("SCMATE_SNAP1") ? RecoveryPoint.SNAP1
                : normalized.contains("SCMATE_BASELINE") || normalized.contains("SCMATEBASE_TASK")
                ? RecoveryPoint.BASELINE : RecoveryPoint.UNKNOWN;
        Schedule schedule = normalized.contains("RESTART") ? Schedule.RESTART
                : normalized.contains("FIRSTBOOT") || normalized.contains("FIRST BOOT")
                || normalized.contains("DAILY") ? Schedule.DAILY_FIRST_BOOT
                : normalized.contains("WEEKLY") || normalized.contains("WEEK") ? Schedule.WEEKLY
                : Schedule.UNKNOWN;
        return new ParsedTask(schedule, point);
    }

    private static String findPointTimestamp(String output, RecoveryPoint point) {
        if (output == null || output.isBlank() || point == RecoveryPoint.UNKNOWN) {
            return null;
        }
        String[] lines = output.split("\\R");
        String latest = null;
        for (int index = 0; index < lines.length; index++) {
            if (!containsPointName(lines[index], point)) continue;
            for (int candidate = index; candidate < lines.length; candidate++) {
                if (candidate > index && isNextRecoveryPoint(lines[candidate])) break;
                Matcher timestamp = VENDOR_TIMESTAMP.matcher(lines[candidate]);
                if (timestamp.find()) {
                    String normalized = normalizeVendorTimestamp(timestamp.group(1));
                    if (normalized != null && (latest == null || normalized.compareTo(latest) > 0)) {
                        latest = normalized;
                    }
                    break;
                }
            }
        }
        return latest;
    }

    private static boolean isNextRecoveryPoint(String line) {
        String normalized = line == null ? "" : line.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ID:") || normalized.startsWith("SNAPSHOT:");
    }

    private static boolean containsPointName(String line, RecoveryPoint point) {
        String normalized = line == null ? "" : line.toUpperCase(Locale.ROOT);
        return normalized.contains(point.snapshotName()) || normalized.contains(point.scheduleName());
    }

    static String normalizeVendorTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('T', ' ')
                .replace("오전", "AM").replace("오후", "PM")
                .replace('.', '-').replace('/', '-').replaceAll("\\s+", " ");
        normalized = normalized.replaceFirst(
                "(?i)^(\\d{4}-\\d{1,2}-\\d{1,2})\\s+(AM|PM)\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)$",
                "$1 $3 $2");
        List<DateTimeFormatter> dateTimeFormats = List.of(
                DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss", Locale.ROOT),
                DateTimeFormatter.ofPattern("yyyy-M-d H:mm", Locale.ROOT),
                new DateTimeFormatterBuilder().parseCaseInsensitive()
                        .appendPattern("yyyy-M-d h:mm[:ss] a").toFormatter(Locale.ENGLISH));
        for (DateTimeFormatter format : dateTimeFormats) {
            try {
                return LocalDateTime.parse(normalized, format).format(DETAIL_TIMESTAMP_FORMAT);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            DateTimeFormatter dateOnly = new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-M-d")
                    .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                    .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                    .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                    .toFormatter(Locale.ROOT);
            return LocalDateTime.parse(normalized, dateOnly).format(DETAIL_TIMESTAMP_FORMAT);
        } catch (DateTimeParseException ignored) {
            return value.trim();
        }
    }

    static String decodeWindowsOutput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        int offset = 0;
        Charset charset = null;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            charset = StandardCharsets.UTF_8;
            offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (looksUtf16Le(bytes)) {
            charset = StandardCharsets.UTF_16LE;
        }
        if (charset != null) {
            return new String(bytes, offset, bytes.length - offset, charset).replace("\u0000", "");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            Charset windowsKorean = Charset.isSupported("MS949")
                    ? Charset.forName("MS949") : Charset.defaultCharset();
            return new String(bytes, windowsKorean);
        }
    }

    private static boolean looksUtf16Le(byte[] bytes) {
        int pairs = Math.min(bytes.length / 2, 64);
        if (pairs < 2) return false;
        int zeroHighBytes = 0;
        for (int index = 0; index < pairs; index++) {
            if (bytes[index * 2 + 1] == 0) zeroHighBytes++;
        }
        return zeroHighBytes * 2 >= pairs;
    }

    static String sanitizeOutput(String output, String username, String password) {
        String sanitized = output == null ? "" : output;
        if (password != null && !password.isEmpty()) {
            sanitized = sanitized.replace(password, "[REDACTED]");
        }
        if (username != null && !username.isEmpty()) {
            sanitized = sanitized.replaceAll("(?i)(/u\\s+)", "$1")
                    .replace(username, "[REDACTED_USER]");
        }
        sanitized = sanitized.replaceAll("(?i)(/p\\s+)(\\\"[^\\\"]*\\\"|\\S+)", "$1[REDACTED]");
        return sanitized;
    }

    static Result restoreBaseline(Path commandPath,
                                  String username,
                                  String password,
                                  Status status,
                                  ObjectMapper mapper,
                                  RestoreCommandRunner runner) {
        return restoreBaseline(commandPath, username, password, status,
                RecoveryPoint.BASELINE, mapper, runner);
    }

    static Result restoreBaseline(Path commandPath,
                                  String username,
                                  String password,
                                  Status status,
                                  RecoveryPoint recoveryPoint,
                                  ObjectMapper mapper,
                                  RestoreCommandRunner runner) {
        if (!Files.isRegularFile(commandPath)) {
            return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE restore command is not installed.");
        }
        if (status.transitionPending()) {
            return Result.failure("SCMATE_UNSAFE_STATE", "SCMATE mode transition is pending.");
        }
        RestoreExecution execution = runner.run(commandPath, username, password, RESTORE_TIMEOUT);
        if (execution.timedOut()) {
            return Result.failure("SCMATE_RESTORE_TIMEOUT", "SCMATE restore request timed out.");
        }
        if (!execution.started()) {
            return Result.failure("SCMATE_RESTORE_START_FAILED", "SCMATE restore command could not be started.");
        }
        if (execution.exitCode() != 0) {
            return Result.failure("SCMATE_RESTORE_FAILED",
                    "SCMATE restore request failed with exit code " + execution.exitCode() + ".");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", Action.RESTORE_BASELINE.name());
        output.put("before", status.toMap());
        output.put("recoveryPoint", recoveryPoint.name());
        output.put("restoreRequested", true);
        output.put("rebootRequested", true);
        return Result.success(writeJson(mapper, output));
    }

    static Result updateBaseline(Path commandPath,
                                 String username,
                                 String password,
                                 Status status,
                                 ObjectMapper mapper,
                                 RestoreCommandRunner runner) {
        return updateBaseline(commandPath, username, password, status,
                RecoveryPoint.BASELINE, mapper, runner);
    }

    static Result updateBaseline(Path commandPath,
                                 String username,
                                 String password,
                                 Status status,
                                 RecoveryPoint recoveryPoint,
                                 ObjectMapper mapper,
                                 RestoreCommandRunner runner) {
        if (!Files.isRegularFile(commandPath)) {
            return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE snapshot command is not installed.");
        }
        if (status.mode() != Mode.OPERATOR || status.transitionPending()) {
            return Result.failure("SCMATE_UNSAFE_STATE",
                    "Snapshot update requires stable operator mode.");
        }
        RestoreExecution execution = runner.run(commandPath, username, password, SNAPSHOT_TIMEOUT);
        if (execution.timedOut()) {
            return Result.failure("SCMATE_SNAPSHOT_TIMEOUT", "SCMATE snapshot request timed out.");
        }
        if (!execution.started()) {
            return Result.failure("SCMATE_SNAPSHOT_START_FAILED", "SCMATE snapshot command could not be started.");
        }
        if (execution.exitCode() != 0) {
            return Result.failure("SCMATE_SNAPSHOT_FAILED",
                    "SCMATE snapshot request failed with exit code " + execution.exitCode() + ".");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", Action.UPDATE_BASELINE.name());
        output.put("before", status.toMap());
        output.put("recoveryPoint", recoveryPoint.name());
        output.put("snapshotRequested", true);
        output.put("rebootRequested", false);
        return Result.success(writeJson(mapper, output));
    }

    static List<String> buildRestoreCommand(Path commandPath, String username, String password) {
        return buildRestoreCommand(commandPath, username, password, RecoveryPoint.BASELINE);
    }

    static List<String> buildRestoreCommand(Path commandPath,
                                            String username,
                                            String password,
                                            RecoveryPoint recoveryPoint) {
        return authenticatedCommand(commandPath,
                List.of("/Restore", "/n", recoveryPoint.scheduleName()), username, password);
    }

    static List<String> buildSnapshotCommand(Path commandPath, String username, String password) {
        return buildSnapshotCommand(commandPath, username, password, RecoveryPoint.BASELINE);
    }

    static List<String> buildSnapshotCommand(Path commandPath,
                                             String username,
                                             String password,
                                             RecoveryPoint recoveryPoint) {
        return authenticatedCommand(commandPath,
                List.of("/Snapshot", "/n", recoveryPoint.snapshotName()), username, password);
    }

    static List<String> buildEnableProtectionCommand(Path commandPath,
                                                       String username,
                                                       String password) {
        return authenticatedCommand(commandPath,
                List.of("/protect", "enable"), username, password);
    }

    static Result enableProtection(Path commandPath,
                                   Status before,
                                   String username,
                                   String password,
                                   ObjectMapper mapper,
                                   DirectCommandRunner runner) {
        if (before.availabilityReason() != AvailabilityReason.PROTECT_DISABLED) {
            return Result.failure("SCMATE_PROTECTION_NOT_DISABLED",
                    "SCMATE protection is not in the disabled state.");
        }
        if (!Files.isRegularFile(commandPath)) {
            return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE mode command is not installed.");
        }
        List<String> command = buildEnableProtectionCommand(commandPath, username, password);
        RestoreExecution execution;
        try {
            execution = runner.run(command, MODE_COMMAND_TIMEOUT);
        } finally {
            Collections.fill(command, "");
        }
        if (execution.timedOut()) {
            return Result.failure("SCMATE_ENABLE_TIMEOUT", "SCMATE protection enable command timed out.");
        }
        if (!execution.started()) {
            return Result.failure("SCMATE_ENABLE_START_FAILED",
                    "SCMATE protection enable command could not be started.");
        }
        if (execution.exitCode() != 0) {
            return Result.failure("SCMATE_ENABLE_FAILED",
                    "SCMATE protection enable command failed with exit code "
                            + execution.exitCode() + ".");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", Action.ENABLE_PROTECTION.name());
        output.put("before", before.toMap());
        output.put("protectionEnableRequested", true);
        output.put("rebootRequested", false);
        return Result.success(writeJson(mapper, output));
    }

    static List<List<String>> buildModeCommands(Path commandPath,
                                                Mode requestedMode,
                                                String username,
                                                String password) {
        return buildModeCommands(commandPath, requestedMode, RecoveryPoint.BASELINE, username, password);
    }

    static List<List<String>> buildModeCommands(Path commandPath,
                                                Mode requestedMode,
                                                RecoveryPoint recoveryPoint,
                                                String username,
                                                String password) {
        List<List<String>> commands = new ArrayList<>();
        commands.add(authenticatedCommand(commandPath,
                List.of("/DelTask", "/type", "reset"), username, password));
        if (requestedMode == Mode.PROTECTED) {
            commands.add(authenticatedCommand(commandPath,
                    List.of("/AddResetTask", "/schtype", "restart",
                            "/name", recoveryPoint.taskName(), "/n", recoveryPoint.scheduleName()),
                    username, password));
        }
        return commands;
    }

    private static List<String> authenticatedCommand(Path commandPath,
                                                     List<String> arguments,
                                                     String username,
                                                     String password) {
        List<String> command = new ArrayList<>();
        command.add(commandPath.toString());
        command.addAll(arguments);
        command.add("/u");
        command.add("administrator".equalsIgnoreCase(username) ? "administrator" : username);
        command.add("/p");
        command.add(password);
        return command;
    }

    private static RestoreExecution runRestoreCommand(Path commandPath,
                                                       String username,
                                                       String password,
                                                       RecoveryPoint recoveryPoint,
                                                       Duration timeout) {
        return runDirectCommand(buildRestoreCommand(commandPath, username, password, recoveryPoint), timeout);
    }

    private static RestoreExecution runSnapshotCommand(Path commandPath,
                                                        String username,
                                                        String password,
                                                        RecoveryPoint recoveryPoint,
                                                        Duration timeout) {
        return runDirectCommand(buildSnapshotCommand(commandPath, username, password, recoveryPoint), timeout);
    }

    private static RestoreExecution runModeCommand(Path commandPath,
                                                   Mode requestedMode,
                                                   RecoveryPoint recoveryPoint,
                                                   String username,
                                                   String password,
                                                   Duration timeout) {
        List<List<String>> commands = buildModeCommands(
                commandPath, requestedMode, recoveryPoint, username, password);
        try {
            for (List<String> command : commands) {
                RestoreExecution execution = runInteractiveModeCommand(command, timeout);
                if (!execution.started() || execution.timedOut() || execution.exitCode() != 0) {
                    return execution;
                }
                if (modeCommandReportedFailure(execution.output())) {
                    return execution;
                }
                wipe(execution.output());
            }
            return RestoreExecution.completed(0);
        } finally {
            for (List<String> command : commands) {
                Collections.fill(command, "");
            }
        }
    }

    private static RestoreExecution runInteractiveModeCommand(List<String> command, Duration timeout) {
        try {
            RestoreExecution interactive = runInteractiveExecution(timeout,
                    (outputPath, resultPath) -> buildInteractiveWrapper(
                            command, outputPath, resultPath));
            if (!interactive.timedOut()) {
                return interactive;
            }
            wipe(interactive.output());
            return runCapturedModeCommand(command, timeout);
        } finally {
            Collections.fill(command, "");
        }
    }

    static RestoreExecution runCapturedModeCommand(List<String> command, Duration timeout) {
        return RestoreExecution.from(runCapturedCommand(new ArrayList<>(command), timeout));
    }

    private static RestoreExecution runInteractivePowerShellScript(String script, Duration timeout) {
        return runInteractiveExecution(timeout,
                (outputPath, resultPath) -> buildInteractiveScriptWrapper(
                        script, outputPath, resultPath));
    }

    private static RestoreExecution runInteractiveExecution(Duration timeout,
                                                             InteractiveWrapperBuilder wrapperBuilder) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String taskName = "PCAgent-SCMate-" + id;
        Path tempRoot = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Temp");
        Path wrapperPath = tempRoot.resolve("PCAgent-SCMate-" + id + ".ps1");
        Path outputPath = tempRoot.resolve("PCAgent-SCMate-" + id + ".out");
        Path resultPath = tempRoot.resolve("PCAgent-SCMate-" + id + ".result");
        try {
            Files.writeString(wrapperPath, wrapperBuilder.build(outputPath, resultPath),
                    StandardCharsets.UTF_8);
        } catch (Exception writeFailure) {
            return RestoreExecution.notStarted();
        }
        String startTime = LocalTime.now().plusMinutes(2)
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        List<String> create = new ArrayList<>(List.of(
                "schtasks.exe", "/Create", "/TN", taskName,
                "/TR", windowsCommandLine(List.of(
                        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-File", wrapperPath.toString())),
                "/SC", "ONCE", "/ST", startTime,
                "/RU", "INTERACTIVE", "/IT", "/RL", "HIGHEST", "/F"));
        List<String> start = new ArrayList<>(List.of(
                "schtasks.exe", "/Run", "/TN", taskName));
        try {
            CommandExecution created = runCapturedCommand(create, SCHEDULED_TASK_COMMAND_TIMEOUT);
            try {
                if (!created.successful()) return RestoreExecution.from(created);
            } finally {
                wipe(created.output());
            }

            CommandExecution started = runCapturedCommand(start, SCHEDULED_TASK_COMMAND_TIMEOUT);
            try {
                if (!started.successful()) return RestoreExecution.from(started);
            } finally {
                wipe(started.output());
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            while (!Files.isRegularFile(resultPath) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return RestoreExecution.notStarted();
                }
            }
            if (!Files.isRegularFile(resultPath)) return RestoreExecution.timeout();
            String resultText;
            try {
                resultText = Files.readString(resultPath, StandardCharsets.UTF_8).trim();
            } catch (Exception readFailure) {
                return RestoreExecution.completed(1);
            }
            byte[] output;
            try (InputStream stream = Files.newInputStream(outputPath)) {
                output = readLimited(stream);
            } catch (Exception ignored) {
                output = new byte[0];
            }
            try {
                return RestoreExecution.completed(Integer.parseInt(resultText), output);
            } catch (NumberFormatException invalidResult) {
                wipe(output);
                return RestoreExecution.completed(1);
            }
        } finally {
            runTaskCleanup(taskName, "/End");
            runTaskCleanup(taskName, "/Delete", "/F");
            deleteQuietly(wrapperPath);
            deleteQuietly(outputPath);
            deleteQuietly(resultPath);
        }
    }

    static String buildInteractiveWrapper(List<String> command, Path outputPath, Path resultPath) {
        String encoded = command.stream()
                .map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .map(value -> "'" + value + "'")
                .collect(Collectors.joining(","));
        return "$ErrorActionPreference='Stop'\r\n"
                + "$values=@(" + encoded + ") | ForEach-Object { "
                + "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }\r\n"
                + "$commandArgs=@($values[1..($values.Length-1)])\r\n"
                + "$working=[IO.Path]::GetDirectoryName($values[0]); "
                + "if ($working) { Set-Location -LiteralPath $working }\r\n"
                + "$code=1; $output=''\r\n"
                + "try { $output=(& $values[0] @commandArgs 2>&1 | Out-String); "
                + "$code=if ($null -eq $LASTEXITCODE) { 1 } else { $LASTEXITCODE } } "
                + "catch { $output=$_.Exception.Message; $code=1 }\r\n"
                + "$utf8=[Text.UTF8Encoding]::new($false)\r\n"
                + "[IO.File]::WriteAllText('" + powershellLiteral(outputPath.toString())
                + "',[string]$output,$utf8)\r\n"
                + "[IO.File]::WriteAllText('" + powershellLiteral(resultPath.toString())
                + "',[string]$code,$utf8)\r\n";
    }

    static String buildInteractiveScriptWrapper(String script, Path outputPath, Path resultPath) {
        return "$ErrorActionPreference='Stop'\r\n"
                + "$code=1; $output=''\r\n"
                + "try { $output=(& {\r\n" + script + "\r\n} 2>&1 | Out-String); $code=0 } "
                + "catch { $output=$_.Exception.Message; $code=1 }\r\n"
                + "$utf8=[Text.UTF8Encoding]::new($false)\r\n"
                + "[IO.File]::WriteAllText('" + powershellLiteral(outputPath.toString())
                + "',[string]$output,$utf8)\r\n"
                + "[IO.File]::WriteAllText('" + powershellLiteral(resultPath.toString())
                + "',[string]$code,$utf8)\r\n";
    }

    private static String powershellLiteral(String value) {
        return value.replace("'", "''");
    }

    static Result verifyModeTask(Path commandPath,
                                 Mode requestedMode,
                                 RecoveryPoint recoveryPoint,
                                 String username,
                                 String password,
                                 DetailCommandRunner runner,
                                 Sleeper sleeper) {
        for (int attempt = 0; attempt < MODE_VERIFY_ATTEMPTS; attempt++) {
            CommandExecution execution = runDetailCommand(
                    authenticatedCommand(commandPath, List.of("/ListTask"), username, password), runner);
            if (execution.successful()) {
                String taskText = sanitizeOutput(decodeWindowsOutput(execution.output()), username, password);
                wipe(execution.output());
                ParsedTask task = parseTask(taskText);
                boolean verified = requestedMode == Mode.OPERATOR
                        ? task.schedule() == Schedule.UNKNOWN
                        : task.schedule() == Schedule.RESTART
                        && task.selectedPoint() == recoveryPoint;
                if (verified) {
                    return Result.success("");
                }
            } else {
                wipe(execution.output());
            }
            if (attempt + 1 < MODE_VERIFY_ATTEMPTS) {
                try {
                    sleeper.sleep(MODE_VERIFY_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Result.failure("SCMATE_MODE_VERIFY_INTERRUPTED",
                            "SCMATE mode verification was interrupted.");
                }
            }
        }
        return Result.failure("SCMATE_MODE_UNVERIFIED",
                "SCMATE /ListTask did not confirm the requested mode.");
    }

    static int protectedDisplayState(RecoveryPoint recoveryPoint) {
        return switch (recoveryPoint) {
            case BASELINE -> 0;
            case SNAP1 -> 5;
            case SNAP2 -> 6;
            case UNKNOWN -> throw new IllegalArgumentException("Recovery point is required.");
        };
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Temporary diagnostics are also removed by the next installer cleanup.
        }
    }

    private static void runTaskCleanup(String taskName, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("schtasks.exe");
        command.addAll(List.of(arguments));
        command.add("/TN");
        command.add(taskName);
        CommandExecution cleanup = runCapturedCommand(command, SCHEDULED_TASK_COMMAND_TIMEOUT);
        wipe(cleanup.output());
    }

    static String windowsCommandLine(List<String> arguments) {
        return arguments.stream().map(ScmateController::quoteWindowsArgument)
                .collect(Collectors.joining(" "));
    }

    private static String quoteWindowsArgument(String argument) {
        if (argument.isEmpty()) return "\"\"";
        if (argument.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '"')) return argument;
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int index = 0; index < argument.length(); index++) {
            char current = argument.charAt(index);
            if (current == '\\') {
                backslashes++;
            } else if (current == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                quoted.append("\\".repeat(backslashes)).append(current);
                backslashes = 0;
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }

    private static RestoreExecution runDirectCommand(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stopProcess(process);
                return RestoreExecution.timeout();
            }
            return RestoreExecution.completed(process.exitValue());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return RestoreExecution.notStarted();
        } catch (Exception exception) {
            if (process != null) process.destroyForcibly();
            return RestoreExecution.notStarted();
        } finally {
            Collections.fill(command, "");
        }
    }

    private static CommandExecution runCapturedCommand(List<String> command, Duration timeout) {
        Process process = null;
        CompletableFuture<byte[]> output = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            InputStream stream = process.getInputStream();
            output = CompletableFuture.supplyAsync(() -> readLimited(stream));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stopProcess(process);
                return CommandExecution.timeout();
            }
            byte[] bytes = output.get(RESTORE_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            return CommandExecution.completed(process.exitValue(), bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return CommandExecution.notStarted();
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            return CommandExecution.notStarted();
        } finally {
            if (output != null && !output.isDone()) output.cancel(true);
            Collections.fill(command, "");
        }
    }

    private static byte[] readLimited(InputStream stream) {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int remaining = DETAIL_OUTPUT_LIMIT;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
            return output.toByteArray();
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static void stopProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(RESTORE_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
        }
    }

    static Result requestMode(Path commandPath,
                              Status before,
                              Mode requestedMode,
                              String username,
                              String password,
                              LegacyModeCommandRunner runner) {
        return requestMode(commandPath, before, requestedMode, RecoveryPoint.BASELINE,
                username, password,
                (path, mode, point, user, secret, timeout) -> runner.run(
                        path, mode, user, secret, timeout));
    }

    static Result requestMode(Path commandPath,
                              Status before,
                              Mode requestedMode,
                              RecoveryPoint recoveryPoint,
                              String username,
                              String password,
                              ModeCommandRunner runner) {
        if (before.transitionPending()) {
            return Result.failure("SCMATE_UNSAFE_STATE", "SCMATE mode transition is already pending.");
        }
        if (!Files.isRegularFile(commandPath)) {
            return Result.failure("SCMATE_NOT_INSTALLED", "SCMATE mode command is not installed.");
        }
        RestoreExecution execution = runner.run(
                commandPath, requestedMode, recoveryPoint, username, password, MODE_COMMAND_TIMEOUT);
        try {
            if (execution.timedOut()) {
                return Result.failure("SCMATE_MODE_TIMEOUT", "SCMATE mode command timed out.");
            }
            if (!execution.started()) {
                return Result.failure("SCMATE_MODE_START_FAILED", "SCMATE mode command could not be started.");
            }
            if (execution.exitCode() != 0) {
                return Result.failure("SCMATE_MODE_FAILED",
                        "SCMATE mode command failed with exit code " + execution.exitCode() + ".");
            }
            if (modeCommandReportedFailure(execution.output())) {
                return Result.failure("SCMATE_MODE_REJECTED", "SCMATE rejected the mode command.");
            }
            return null;
        } finally {
            wipe(execution.output());
        }
    }

    static boolean modeCommandReportedFailure(byte[] output) {
        String text = decodeWindowsOutput(output).toLowerCase(Locale.ROOT);
        return text.contains("사용자 이름") || text.contains("암호") || text.contains("올바르지")
                || text.contains("실패") || text.contains("password") || text.contains("authentication")
                || text.contains("logon") || text.contains("failed") || text.contains("error");
    }

    static boolean recoveryModuleInstalled(int shdIsUninstalled) {
        return shdIsUninstalled == 0;
    }

    static Status applyDisplayState(Status nativeStatus, OptionalInt displayState) {
        return applyDisplayState(nativeStatus, displayState, false);
    }

    static Status applyDisplayState(Status nativeStatus,
                                    OptionalInt displayState,
                                    boolean confirmedNotInstalled) {
        if (confirmedNotInstalled) {
            return new Status(Mode.UNKNOWN, nativeStatus.transitionPending(),
                    displayState.isPresent() ? displayState.getAsInt() : nativeStatus.raw(),
                    nativeStatus.auxiliaryRaw(), Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.NOT_INSTALLED);
        }
        if (displayState.isEmpty()) {
            return new Status(Mode.UNKNOWN, nativeStatus.transitionPending(), nativeStatus.raw(),
                    nativeStatus.auxiliaryRaw(), Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.STATUS_UNAVAILABLE);
        }
        if (displayState.getAsInt() == 9 && nativeStatus.raw() == 1) {
            return new Status(Mode.UNKNOWN, nativeStatus.transitionPending(), 9,
                    nativeStatus.auxiliaryRaw(), Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.PROTECT_DISABLED);
        }
        DisplayDescriptor descriptor = mapDisplayState(displayState.getAsInt(), confirmedNotInstalled);
        return new Status(descriptor.mode(), nativeStatus.transitionPending(),
                displayState.getAsInt(), nativeStatus.auxiliaryRaw(), descriptor.schedule(),
                descriptor.selectedPoint(), descriptor.availabilityReason());
    }

    static DisplayDescriptor mapDisplayState(int raw, boolean confirmedNotInstalled) {
        return switch (raw) {
            case 0 -> descriptor(Mode.PROTECTED, Schedule.RESTART, RecoveryPoint.BASELINE,
                    AvailabilityReason.ACTIVE);
            case 1 -> descriptor(Mode.PROTECTED, Schedule.DAILY_FIRST_BOOT, RecoveryPoint.BASELINE,
                    AvailabilityReason.ACTIVE);
            case 2 -> descriptor(Mode.UNKNOWN, Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.STATUS_UNAVAILABLE);
            case 3 -> descriptor(Mode.OPERATOR, Schedule.MANUAL, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.ACTIVE);
            case 4 -> descriptor(Mode.PROTECTED, Schedule.WEEKLY, RecoveryPoint.BASELINE,
                    AvailabilityReason.ACTIVE);
            case 5 -> descriptor(Mode.PROTECTED, Schedule.RESTART, RecoveryPoint.SNAP1,
                    AvailabilityReason.ACTIVE);
            case 6 -> descriptor(Mode.PROTECTED, Schedule.RESTART, RecoveryPoint.SNAP2,
                    AvailabilityReason.ACTIVE);
            case 7 -> descriptor(Mode.PROTECTED, Schedule.DAILY_FIRST_BOOT, RecoveryPoint.SNAP1,
                    AvailabilityReason.ACTIVE);
            case 8 -> descriptor(Mode.PROTECTED, Schedule.DAILY_FIRST_BOOT, RecoveryPoint.SNAP2,
                    AvailabilityReason.ACTIVE);
            case 9 -> descriptor(Mode.UNKNOWN, Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    confirmedNotInstalled ? AvailabilityReason.NOT_INSTALLED
                            : AvailabilityReason.STATUS_UNAVAILABLE);
            case 10 -> descriptor(Mode.UNKNOWN, Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.LICENSE_EXPIRED);
            case 11 -> descriptor(Mode.PROTECTED, Schedule.WEEKLY, RecoveryPoint.SNAP1,
                    AvailabilityReason.ACTIVE);
            case 12 -> descriptor(Mode.PROTECTED, Schedule.WEEKLY, RecoveryPoint.SNAP2,
                    AvailabilityReason.ACTIVE);
            default -> descriptor(Mode.UNKNOWN, Schedule.UNKNOWN, RecoveryPoint.UNKNOWN,
                    AvailabilityReason.STATUS_UNAVAILABLE);
        };
    }

    private static DisplayDescriptor descriptor(Mode mode,
                                                Schedule schedule,
                                                RecoveryPoint point,
                                                AvailabilityReason availability) {
        return new DisplayDescriptor(mode, schedule, point, availability);
    }

    private static Status statusWithoutNativeApi(ScmateClientDisplayStateReader.Snapshot client,
                                                 AvailabilityReason reason) {
        return applyDisplayState(Status.unknown(reason), client.displayState(),
                reason == AvailabilityReason.NOT_INSTALLED);
    }

    private static Status readEffectiveStatus(NativeLibrary library) {
        return applyDisplayState(readStatus(library), ScmateClientDisplayStateReader.read(), false);
    }

    private static Status readStatus(NativeLibrary library) {
        try (Memory packet = new Memory(Integer.BYTES)) {
            packet.clear();
            int result = library.getFunction(SHD_GET_INFO).invokeInt(new Object[]{
                    WORK_MODE_STATE_INFO_CLASS, packet, Integer.BYTES
            });
            if (result != 0) {
                throw new ScmateStatusException(hresult("ShdGetInfo(WORK_MODE_STATE)", result));
            }
            return decodeWorkModePacket(packet.getInt(0));
        }
    }

    static Status decodeWorkModePacket(int packet) {
        return decodeStatus(packet & 0xFF, (packet >>> 8) & 0xFF);
    }

    static Status decodeStatus(int raw, int auxiliaryRaw) {
        if (raw < 0 || raw > 3) {
            throw new ScmateStatusException("Unexpected SCMATE work mode state: " + raw);
        }
        return new Status(Mode.UNKNOWN, raw == 2 || raw == 3,
                Integer.toUnsignedLong(raw), Integer.toUnsignedLong(auxiliaryRaw),
                Schedule.UNKNOWN, RecoveryPoint.UNKNOWN, AvailabilityReason.STATUS_UNAVAILABLE);
    }

    private static String writeJson(ObjectMapper mapper, Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize SCMATE result", e);
        }
    }

    private static String hresult(String operation, int result) {
        return operation + " failed: 0x" + String.format(Locale.ROOT, "%08X", result);
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public enum Mode {
        PROTECTED,
        OPERATOR,
        UNKNOWN
    }

    public enum Schedule {
        MANUAL,
        RESTART,
        DAILY_FIRST_BOOT,
        WEEKLY,
        UNKNOWN
    }

    public enum AvailabilityReason {
        ACTIVE,
        NOT_INSTALLED,
        LICENSE_EXPIRED,
        PROTECT_DISABLED,
        STATUS_UNAVAILABLE
    }

    public enum RecoveryPoint {
        BASELINE("SCMATE_BASELINE", "Win:SCMATE_BASELINE", "SCMATEBASE_TASK"),
        SNAP1("SCMATE_SNAP1", "Win:SCMATE_SNAP1", "SCMATE_SNAP1_TASK"),
        SNAP2("SCMATE_SNAP2", "Win:SCMATE_SNAP2", "SCMATE_SNAP2_TASK"),
        UNKNOWN("", "", "");

        private final String snapshotName;
        private final String scheduleName;
        private final String taskName;

        RecoveryPoint(String snapshotName, String scheduleName, String taskName) {
            this.snapshotName = snapshotName;
            this.scheduleName = scheduleName;
            this.taskName = taskName;
        }

        String snapshotName() {
            if (this == UNKNOWN) throw new IllegalStateException("UNKNOWN recovery point cannot be mutated.");
            return snapshotName;
        }

        String scheduleName() {
            if (this == UNKNOWN) throw new IllegalStateException("UNKNOWN recovery point cannot be mutated.");
            return scheduleName;
        }

        String taskName() {
            if (this == UNKNOWN) throw new IllegalStateException("UNKNOWN recovery point cannot be mutated.");
            return taskName;
        }

        static RecoveryPoint fromPayload(JsonNode payload) {
            if (payload == null || payload.isNull() || !payload.has("recoveryPoint")
                    || payload.path("recoveryPoint").asText("").isBlank()) {
                return BASELINE;
            }
            String value = payload.path("recoveryPoint").asText("").trim().toUpperCase(Locale.ROOT);
            RecoveryPoint point;
            try {
                point = RecoveryPoint.valueOf(value);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("recoveryPoint must be BASELINE, SNAP1, or SNAP2.");
            }
            if (point == UNKNOWN) {
                throw new IllegalArgumentException("recoveryPoint must be BASELINE, SNAP1, or SNAP2.");
            }
            return point;
        }
    }

    public enum Action {
        STATUS,
        REFRESH_STATUS_DETAIL,
        DIAGNOSTIC_STATUS_DETAIL,
        ENABLE_PROTECTION,
        REQUEST_OPERATOR,
        REQUEST_PROTECTED,
        UPDATE_BASELINE,
        RESTORE_BASELINE,
        INSTALL_RECOVERY_MODULE;

        static Action parse(String value) {
            if (value == null || value.isBlank()) return STATUS;
            return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        boolean requiresAuthentication() {
            return this != STATUS && this != INSTALL_RECOVERY_MODULE;
        }

        boolean isReadOnly() {
            return this == STATUS || this == REFRESH_STATUS_DETAIL
                    || this == DIAGNOSTIC_STATUS_DETAIL;
        }
    }

    public record Status(Mode mode,
                         boolean transitionPending,
                         long raw,
                         long auxiliaryRaw,
                         Schedule schedule,
                         RecoveryPoint selectedPoint,
                         AvailabilityReason availabilityReason) {
        public Status(Mode mode, boolean transitionPending, long raw, long auxiliaryRaw) {
            this(mode, transitionPending, raw, auxiliaryRaw, Schedule.UNKNOWN,
                    RecoveryPoint.UNKNOWN, AvailabilityReason.ACTIVE);
        }

        static Status unknown(AvailabilityReason availabilityReason) {
            return new Status(Mode.UNKNOWN, false, -1, 0, Schedule.UNKNOWN,
                    RecoveryPoint.UNKNOWN, availabilityReason);
        }

        Status withAvailability(AvailabilityReason availabilityReason) {
            return new Status(mode, transitionPending, raw, auxiliaryRaw, schedule,
                    selectedPoint, availabilityReason);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("available", availabilityReason == AvailabilityReason.ACTIVE);
            value.put("mode", mode.name());
            value.put("transitionPending", transitionPending);
            value.put("raw", raw);
            value.put("auxiliaryRaw", auxiliaryRaw);
            value.put("schedule", schedule.name());
            value.put("selectedPoint", selectedPoint.name());
            value.put("availabilityReason", availabilityReason.name());
            return value;
        }
    }

    record DisplayDescriptor(Mode mode,
                             Schedule schedule,
                             RecoveryPoint selectedPoint,
                             AvailabilityReason availabilityReason) {
    }

    record Details(String baselineCreatedAt,
                   String snap1CreatedAt,
                   String snap2CreatedAt,
                   Schedule schedule,
                   RecoveryPoint selectedPoint,
                   AvailabilityReason availabilityReason,
                   String productVariant,
                   String stateSource) {
        Details withSelectedPoint(RecoveryPoint point) {
            return new Details(baselineCreatedAt, snap1CreatedAt, snap2CreatedAt,
                    schedule, point, availabilityReason, productVariant, stateSource);
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("baselineCreatedAt", baselineCreatedAt);
            value.put("snap1CreatedAt", snap1CreatedAt);
            value.put("snap2CreatedAt", snap2CreatedAt);
            value.put("schedule", schedule.name());
            value.put("selectedPoint", selectedPoint.name());
            value.put("availabilityReason", availabilityReason.name());
            value.put("productVariant", productVariant);
            value.put("stateSource", stateSource);
            return value;
        }
    }

    record ParsedTask(Schedule schedule, RecoveryPoint selectedPoint) {
    }

    private static final class ScmateStatusException extends IllegalStateException {
        private ScmateStatusException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface InteractiveWrapperBuilder {
        String build(Path outputPath, Path resultPath);
    }

    @FunctionalInterface
    interface DisplayRefresher {
        Result refresh(int expectedDisplayState);
    }

    @FunctionalInterface
    interface RestoreCommandRunner {
        RestoreExecution run(Path commandPath, String username, String password, Duration timeout);
    }

    @FunctionalInterface
    interface LegacyModeCommandRunner {
        RestoreExecution run(Path commandPath,
                             Mode requestedMode,
                             String username,
                             String password,
                             Duration timeout);
    }

    @FunctionalInterface
    interface ModeCommandRunner {
        RestoreExecution run(Path commandPath,
                             Mode requestedMode,
                             RecoveryPoint recoveryPoint,
                             String username,
                             String password,
                             Duration timeout);
    }

    @FunctionalInterface
    interface DetailCommandRunner {
        CommandExecution run(List<String> command, Duration timeout);
    }

    interface DirectCommandRunner {
        RestoreExecution run(List<String> command, Duration timeout);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    record RestoreExecution(boolean started, boolean timedOut, int exitCode, byte[] output) {
        static RestoreExecution completed(int exitCode) {
            return completed(exitCode, new byte[0]);
        }

        static RestoreExecution completed(int exitCode, byte[] output) {
            return new RestoreExecution(true, false, exitCode,
                    output == null ? new byte[0] : output);
        }

        static RestoreExecution from(CommandExecution execution) {
            return new RestoreExecution(execution.started(), execution.timedOut(),
                    execution.exitCode(), execution.output());
        }

        static RestoreExecution timeout() {
            return new RestoreExecution(true, true, -1, new byte[0]);
        }

        static RestoreExecution notStarted() {
            return new RestoreExecution(false, false, -1, new byte[0]);
        }
    }

    record CommandExecution(boolean started, boolean timedOut, int exitCode, byte[] output) {
        boolean successful() {
            return started && !timedOut && exitCode == 0;
        }

        static CommandExecution completed(int exitCode, byte[] output) {
            return new CommandExecution(true, false, exitCode,
                    output == null ? new byte[0] : output);
        }

        static CommandExecution timeout() {
            return new CommandExecution(true, true, -1, new byte[0]);
        }

        static CommandExecution notStarted() {
            return new CommandExecution(false, false, -1, new byte[0]);
        }
    }

    record DetailRead(boolean success,
                      Details details,
                      String errorCode,
                      String errorMessage) {
        static DetailRead success(Details details) {
            return new DetailRead(true, details, "", "");
        }

        static DetailRead failure(String code, String message) {
            return new DetailRead(false, null, code, message);
        }
    }

    public record Result(int exitCode, String stdout, String stderr) {
        static Result success(String stdout) {
            return new Result(0, stdout, "");
        }

        static Result failure(String code, String message) {
            return new Result(1, "", code + ": " + message);
        }
    }
}
