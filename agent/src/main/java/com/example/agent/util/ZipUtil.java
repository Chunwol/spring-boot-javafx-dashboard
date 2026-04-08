package com.example.agent.util;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipUtil {
    /**
     * zip 파일을 targetDir에 해제
     */
    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = safeResolve(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    Files.createDirectories(newPath.getParent());
                    try (OutputStream out = Files.newOutputStream(newPath)) {
                        zis.transferTo(out);
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
