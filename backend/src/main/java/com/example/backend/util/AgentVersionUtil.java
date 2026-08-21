package com.example.backend.util;

public final class AgentVersionUtil {

    private AgentVersionUtil() {
    }

    public static boolean isAtLeast(String version, int requiredMajor, int requiredMinor, int requiredPatch) {
        if (version == null || version.isBlank()) return false;
        String[] parts = version.trim().split("[.-]", 4);
        if (parts.length < 3) return false;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major != requiredMajor) return major > requiredMajor;
            if (minor != requiredMinor) return minor > requiredMinor;
            return patch >= requiredPatch;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
