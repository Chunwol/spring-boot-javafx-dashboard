package com.example.agent.scmate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ScmateRecoveryInstaller {

    private static final Path SETUP_ROOT = Path.of(
            "C:\\Program Files\\SCMATE_RX2Client\\ShieldSetup");
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);
    private static final Pattern REBOOT_SETTING = Pattern.compile(
            "(?im)^(\\s*reboot\\s*=\\s*)[^\\r\\n]*(\\r?\\n|$)");

    private ScmateRecoveryInstaller() {
    }

    static ScmateController.Result install(ObjectMapper mapper) {
        Path sourceExecutable = installerExecutable(SETUP_ROOT, is64BitWindows());
        Path sourceIni = SETUP_ROOT.resolve("setup.ini");
        if (!Files.isRegularFile(sourceExecutable) || !Files.isRegularFile(sourceIni)) {
            return ScmateController.Result.failure(
                    "SCMATE_INSTALLER_NOT_FOUND", "SCMATE recovery installer is not available.");
        }

        Path workingRoot = null;
        try {
            workingRoot = Files.createTempDirectory("scmate-recovery-install-");
            copyTree(SETUP_ROOT, workingRoot);
            Path workingIni = workingRoot.resolve("setup.ini");
            if (!disableAutomaticReboot(workingIni)) {
                return ScmateController.Result.failure(
                        "SCMATE_INSTALL_CONFIG_INVALID", "SCMATE installer reboot setting was not found.");
            }

            List<String> command = buildInstallCommand(workingRoot, is64BitWindows());
            Process process = new ProcessBuilder(command)
                    .directory(workingRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(INSTALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return ScmateController.Result.failure(
                        "SCMATE_INSTALL_TIMEOUT", "SCMATE recovery installation timed out.");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0 && exitCode != 3010) {
                return ScmateController.Result.failure(
                        "SCMATE_INSTALL_FAILED", "SCMATE recovery installer returned exit code "
                                + exitCode + ".");
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("action", ScmateController.Action.INSTALL_RECOVERY_MODULE.name());
            output.put("installationRequested", true);
            output.put("automaticReboot", false);
            output.put("rebootRequired", true);
            output.put("installedFilesPresent", Files.isRegularFile(
                    Path.of("C:\\Program Files\\SCMATE_RX2Client\\Shield\\ShdApi.dll")));
            return ScmateController.Result.success(mapper.writeValueAsString(output));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ScmateController.Result.failure(
                    "SCMATE_INSTALL_INTERRUPTED", "SCMATE recovery installation was interrupted.");
        } catch (Exception error) {
            return ScmateController.Result.failure(
                    "SCMATE_INSTALL_ERROR", "SCMATE recovery installation failed: "
                            + error.getClass().getSimpleName());
        } finally {
            deleteTree(workingRoot);
        }
    }

    static List<String> buildInstallCommand(Path setupRoot, boolean is64Bit) {
        return List.of(installerExecutable(setupRoot, is64Bit).toString(), "/s");
    }

    static boolean disableAutomaticReboot(Path iniPath) throws IOException {
        byte[] bytes = Files.readAllBytes(iniPath);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher matcher = REBOOT_SETTING.matcher(text);
        StringBuffer updated = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            matcher.appendReplacement(updated, Matcher.quoteReplacement(matcher.group(1) + "0" + matcher.group(2)));
            replacements++;
        }
        matcher.appendTail(updated);
        if (replacements == 0) {
            return false;
        }
        Files.write(iniPath, updated.toString().getBytes(StandardCharsets.ISO_8859_1));
        return true;
    }

    private static Path installerExecutable(Path setupRoot, boolean is64Bit) {
        return setupRoot.resolve(is64Bit ? "x64.exe" : "setup.exe");
    }

    private static boolean is64BitWindows() {
        String architecture = System.getProperty("os.arch", "");
        return architecture.contains("64");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            List<Path> ordered = new ArrayList<>(paths.toList());
            ordered.sort(Comparator.reverseOrder());
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
