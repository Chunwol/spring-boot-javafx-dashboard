package com.example.agent.util;

import java.net.URI;
import java.nio.file.Path;

/**
 * Masks sensitive information in logs to prevent information disclosure.
 * Prevents exposure of:
 * - Authentication tokens
 * - File paths
 * - Script contents
 * - IP addresses
 * - URLs with credentials
 */
public class SensitiveDataMasker {

    private static final String MASK = "***";

    /**
     * Mask URL to hide credentials and sensitive parts
     * Example: https://user:pass@host/path → https://host/path
     */
    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                // Remove user:pass from URL
                return url.replace(userInfo + "@", "");
            }
        } catch (Exception ignored) {
        }
        return url;
    }

    /**
     * Mask authentication tokens
     * Example: "abc123def456" → "abc***456"
     */
    public static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }
        if (token.length() <= 4) {
            return MASK;
        }
        int keepStart = Math.min(3, token.length() / 4);
        int keepEnd = Math.min(3, token.length() / 4);
        return token.substring(0, keepStart) + MASK + token.substring(token.length() - keepEnd);
    }

    /**
     * Mask file path to hide directory structure
     * Example: "C:\\Users\\admin\\script.ps1" → "script.ps1"
     */
    public static String maskPath(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        try {
            Path p = Path.of(path);
            return p.getFileName().toString();
        } catch (Exception ignored) {
        }
        // Fallback: just return filename part
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    /**
     * Mask IP address
     * Example: "192.168.1.100" → "192.168.1.***"
     */
    public static String maskIpAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + "." + parts[2] + ".***";
        }
        return ip;
    }

    /**
     * Mask script content to hide sensitive/business logic
     * Limit displayed content to first N characters
     */
    public static String maskScriptContent(String script, int maxLen) {
        if (script == null || script.isBlank()) {
            return script;
        }
        if (script.length() <= maxLen) {
            return script;
        }
        return script.substring(0, maxLen) + "... (truncated)";
    }

    /**
     * Mask command line - hide sensitive arguments
     * Example: "powershell.exe -Password abc123" → "powershell.exe -Password ***"
     */
    public static String maskCommand(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        // Hide common sensitive parameters
        return command
            .replaceAll("(?i)(-password|--password|passwd|pwd)\\s+[^\\s]+",
                        "$1 " + MASK)
            .replaceAll("(?i)(-token|--token|api_key|apikey)\\s+[^\\s]+",
                        "$1 " + MASK)
            .replaceAll("(?i)(-credential|--credential|cred)\\s+[^\\s]+",
                        "$1 " + MASK);
    }

    /**
     * Check if exception message contains sensitive data
     */
    public static String maskExceptionMessage(String message) {
        if (message == null) {
            return null;
        }
        return maskCommand(message)
            .replaceAll("(password|auth|token|secret|key|credential)[=:]\\s*[^\\s,;]+",
                        "$1=" + MASK);
    }
}
