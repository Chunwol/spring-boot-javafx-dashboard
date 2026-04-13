package com.example.agent.util;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtil {

    private static final long MAX_ENTRY_SIZE  = 8L * 1024 * 1024 * 1024;   // 8GB per entry
    private static final long MAX_TOTAL_SIZE  = 32L * 1024 * 1024 * 1024;  // 32GB total
    private static final int  MAX_ENTRY_COUNT = 100_000;                    // 엔트리 수 제한

    /**
     * zip 파일을 targetDir에 해제 (Zip Slip + Zip Bomb 방지)
     */
    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            int entryCount = 0;
            long totalExtracted = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("Zip entry count exceeds limit: " + MAX_ENTRY_COUNT);
                }
                Path newPath = safeResolve(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream out = Files.newOutputStream(newPath)) {
                        byte[] buf = new byte[8192];
                        int n;
                        long entrySize = 0;
                        while ((n = zis.read(buf)) > 0) {
                            entrySize += n;
                            totalExtracted += n;
                            if (entrySize > MAX_ENTRY_SIZE) {
                                throw new IOException("Zip entry too large (possible zip bomb): " + entry.getName());
                            }
                            if (totalExtracted > MAX_TOTAL_SIZE) {
                                throw new IOException("Zip total extracted size exceeds limit");
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    // Zip Slip 방지
    private static Path safeResolve(Path targetDir, String entryName) throws IOException {
        Path resolved = targetDir.resolve(entryName).normalize();
        if (!resolved.startsWith(targetDir)) throw new IOException("Zip entry outside target dir: " + entryName);
        return resolved;
    }
}
