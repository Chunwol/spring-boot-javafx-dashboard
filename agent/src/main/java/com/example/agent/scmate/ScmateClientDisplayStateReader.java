package com.example.agent.scmate;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

final class ScmateClientDisplayStateReader {

    private static final Path CLIENT_PATH = Path.of(
            "C:\\Program Files\\SCMATE_RX2Client\\SCMateClient.exe");
    private static final String SUPPORTED_CLIENT_SHA256 =
            "5a9c827b3904f1d6f59577000a2a86db561701fd1957c6170270b740722a822b";
    private static final long DISPLAY_STATE_POINTER_ADDRESS = 0x00F0F9C0L;
    private static final long PRODUCT_VARIANT_POINTER_ADDRESS = 0x00F109E0L;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final Duration HASH_CACHE_MAX_AGE = Duration.ofSeconds(30);
    private static final SystemInfo SYSTEM_INFO = new SystemInfo();
    private static volatile VerificationCache verificationCache;

    private ScmateClientDisplayStateReader() {
    }

    static OptionalInt read() {
        return inspect().displayState();
    }

    static Snapshot inspect() {
        if (!isWindows()) {
            return Snapshot.unavailable();
        }
        FileVerification clientFile = verifyClientFile(CLIENT_PATH);
        if (!clientFile.supportedHash()) {
            return new Snapshot(OptionalInt.empty(), clientFile.present(), false,
                    false, false, false, "UNKNOWN", -1, 0L);
        }

        List<OSProcess> processes = SYSTEM_INFO.getOperatingSystem().getProcesses(
                candidate -> candidate.getName() != null
                        && candidate.getName().toLowerCase(Locale.ROOT).startsWith("scmateclient"),
                Comparator.comparingInt(OSProcess::getProcessID), 32);
        boolean processPresent = !processes.isEmpty();
        for (OSProcess process : processes) {
            Path actualPath = processPath(process);
            if (actualPath == null || !sameExecutable(CLIENT_PATH, actualPath)) {
                continue;
            }
            FileVerification processFile = verifyClientFile(actualPath);
            if (!processFile.supportedHash()) {
                continue;
            }
            MemoryState memory = readProcessState(process.getProcessID());
            String observedProduct = memory.productVariant();
            boolean productRead = observedProduct.matches("(?i)^[A-Z]$");
            if (productRead && !"A".equalsIgnoreCase(observedProduct)) {
                return new Snapshot(OptionalInt.empty(), true, true,
                        true, true, true, observedProduct,
                        process.getProcessID(), process.getStartTime());
            }
            return new Snapshot(memory.displayState(), true, true,
                    true, true, productRead, "A",
                    process.getProcessID(), process.getStartTime());
        }

        // The verified v12.95 product-A executable remains valid evidence when its UI process
        // has not started yet. Memory-derived display state is intentionally unavailable.
        return new Snapshot(OptionalInt.empty(), true, true,
                processPresent, false, false, "A", -1, 0L);
    }

    private static Path processPath(OSProcess process) {
        String path = process.getPath();
        if (path == null || path.isBlank()) return null;
        try {
            return Path.of(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean sameExecutable(Path expected, Path actual) {
        if (expected == null || actual == null) return false;
        try {
            return expected.toRealPath().equals(actual.toRealPath());
        } catch (Exception ignored) {
            return expected.toAbsolutePath().normalize().toString()
                    .equalsIgnoreCase(actual.toAbsolutePath().normalize().toString());
        }
    }

    private static MemoryState readProcessState(int processId) {
        Pointer process = Kernel32.INSTANCE.OpenProcess(
                PROCESS_VM_READ | PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (process == null || Pointer.nativeValue(process) == 0) {
            return MemoryState.empty();
        }
        try {
            String stateText = readWidePointerString(process, DISPLAY_STATE_POINTER_ADDRESS, 16);
            OptionalInt displayState;
            try {
                int parsed = Integer.parseInt(stateText.trim());
                displayState = parsed >= 0 && parsed <= 12
                        ? OptionalInt.of(parsed) : OptionalInt.empty();
            } catch (NumberFormatException ignored) {
                displayState = OptionalInt.empty();
            }
            String productVariant = readWidePointerString(
                    process, PRODUCT_VARIANT_POINTER_ADDRESS, 16).trim();
            return new MemoryState(displayState, productVariant);
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static String readWidePointerString(Pointer process, long pointerAddress, int maxBytes) {
        try (Memory bytesRead = new Memory(Native.SIZE_T_SIZE)) {
            byte[] pointerBytes = new byte[Integer.BYTES];
            if (!Kernel32.INSTANCE.ReadProcessMemory(
                    process, new Pointer(pointerAddress), pointerBytes, pointerBytes.length, bytesRead)
                    || readCount(bytesRead) != pointerBytes.length) {
                return "";
            }
            long stringAddress = Integer.toUnsignedLong(ByteBuffer.wrap(pointerBytes)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (stringAddress == 0) return "";

            byte[] textBytes = new byte[maxBytes];
            if (!Kernel32.INSTANCE.ReadProcessMemory(
                    process, new Pointer(stringAddress), textBytes, textBytes.length, bytesRead)
                    || readCount(bytesRead) < Character.BYTES) {
                return "";
            }
            String value = new String(textBytes, 0,
                    (int) Math.min(readCount(bytesRead), textBytes.length), StandardCharsets.UTF_16LE);
            int terminator = value.indexOf('\0');
            return terminator >= 0 ? value.substring(0, terminator) : value;
        }
    }

    private static long readCount(Memory bytesRead) {
        return Native.SIZE_T_SIZE == Long.BYTES
                ? bytesRead.getLong(0)
                : Integer.toUnsignedLong(bytesRead.getInt(0));
    }

    static FileVerification verifyClientFile(Path path) {
        return verifyFile(path, SUPPORTED_CLIENT_SHA256);
    }

    static FileVerification verifyFile(Path path, String expectedSha256) {
        if (path == null || !Files.isRegularFile(path)) {
            return new FileVerification(false, false, "");
        }
        try {
            Path realPath = path.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
            String quickSignature = quickSignature(realPath, attributes.size());
            long now = System.nanoTime();
            VerificationCache cached = verificationCache;
            if (cached != null && cached.matches(realPath, attributes, quickSignature, expectedSha256)
                    && now - cached.checkedAtNanos() <= HASH_CACHE_MAX_AGE.toNanos()) {
                return cached.verification();
            }
            synchronized (ScmateClientDisplayStateReader.class) {
                cached = verificationCache;
                if (cached != null && cached.matches(realPath, attributes, quickSignature, expectedSha256)
                        && now - cached.checkedAtNanos() <= HASH_CACHE_MAX_AGE.toNanos()) {
                    return cached.verification();
                }
                String hash = sha256(realPath);
                FileVerification verification = new FileVerification(true,
                        expectedSha256 != null && expectedSha256.equalsIgnoreCase(hash), hash);
                verificationCache = new VerificationCache(realPath, attributes.size(),
                        attributes.lastModifiedTime().toMillis(), attributes.fileKey(), quickSignature,
                        expectedSha256 == null ? "" : expectedSha256, now, verification);
                return verification;
            }
        } catch (Exception ignored) {
            return new FileVerification(true, false, "");
        }
    }

    static String sha256(Path path) {
        if (!Files.isRegularFile(path)) return "";
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String quickSignature(Path path, long size) {
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int sampleSize = (int) Math.min(4096L, size);
            ByteBuffer sample = ByteBuffer.allocate(sampleSize);
            channel.read(sample);
            digest.update(sample.array(), 0, sample.position());
            if (size > sampleSize) {
                channel.position(Math.max(0L, size - sampleSize));
                sample.clear();
                channel.read(sample);
                digest.update(sample.array(), 0, sample.position());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return "";
        }
    }

    static void clearVerificationCache() {
        verificationCache = null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    record Snapshot(OptionalInt displayState,
                    boolean clientFilePresent,
                    boolean clientHashVerified,
                    boolean processPresent,
                    boolean processPathVerified,
                    boolean productVariantRead,
                    String productVariant,
                    int processId,
                    long processStartTimeMillis) {
        static Snapshot unavailable() {
            return new Snapshot(OptionalInt.empty(), false, false,
                    false, false, false, "UNKNOWN", -1, 0L);
        }

        boolean supportedProductA() {
            return clientHashVerified
                    && (!productVariantRead || "A".equalsIgnoreCase(productVariant));
        }
    }

    record FileVerification(boolean present, boolean supportedHash, String sha256) {
    }

    private record VerificationCache(Path realPath,
                                     long size,
                                     long lastModifiedMillis,
                                     Object fileKey,
                                     String quickSignature,
                                     String expectedSha256,
                                     long checkedAtNanos,
                                     FileVerification verification) {
        boolean matches(Path path,
                        BasicFileAttributes attributes,
                        String currentQuickSignature,
                        String currentExpectedSha256) {
            if (!realPath.equals(path) || size != attributes.size()
                    || lastModifiedMillis != attributes.lastModifiedTime().toMillis()
                    || !quickSignature.equals(currentQuickSignature)
                    || !expectedSha256.equalsIgnoreCase(
                    currentExpectedSha256 == null ? "" : currentExpectedSha256)) {
                return false;
            }
            Object currentKey = attributes.fileKey();
            return fileKey == null || currentKey == null || fileKey.equals(currentKey);
        }
    }

    private record MemoryState(OptionalInt displayState, String productVariant) {
        static MemoryState empty() {
            return new MemoryState(OptionalInt.empty(), "");
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean ReadProcessMemory(Pointer process, Pointer address, byte[] buffer, int size, Pointer bytesRead);

        boolean CloseHandle(Pointer handle);
    }
}
