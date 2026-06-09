package com.example.backend.util;

import java.util.Locale;

/**
 * IP/MAC 문자열 비교 시 공백, 대소문자, 구분자 차이를 흡수하기 위한 정규화 유틸리티.
 */
public final class DeviceAddressUtil {

    private DeviceAddressUtil() {}

    public static String normalizeIp(String ipAddress) {
        if (ipAddress == null) {
            return null;
        }
        String trimmed = ipAddress.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String normalizeMac(String macAddress) {
        if (macAddress == null) {
            return null;
        }
        String trimmed = macAddress.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
    }

    public static boolean sameIp(String left, String right) {
        String normalizedLeft = normalizeIp(left);
        return normalizedLeft != null && normalizedLeft.equals(normalizeIp(right));
    }

    public static boolean sameMac(String left, String right) {
        String normalizedLeft = normalizeMac(left);
        return normalizedLeft != null && normalizedLeft.equals(normalizeMac(right));
    }
}
