package com.example.backend.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class IpRangeUtil {

    private IpRangeUtil() {}

    public static long ipv4ToLong(String ip) {
        if (ip == null) throw new IllegalArgumentException("ip is null");
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("invalid ipv4: " + ip);
        long out = 0;
        for (int i = 0; i < 4; i++) {
            int p = Integer.parseInt(parts[i]);
            if (p < 0 || p > 255) throw new IllegalArgumentException("invalid ipv4: " + ip);
            out = (out << 8) | (p & 0xFF);
        }
        return out;
    }

    public static boolean isIpv4(String ip) {
        if (ip == null) return false;
        String trimmed = ip.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isBlank()) return false;
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean containsIp(String ip, String cidr, String startIp, String endIp) {
        if (!isIpv4(ip)) return false;
        long target = ipv4ToLong(ip);

        if (cidr != null && !cidr.isBlank()) {
            String[] p = cidr.trim().split("/");
            if (p.length == 2 && isIpv4(p[0])) {
                int prefix = Integer.parseInt(p[1]);
                if (prefix >= 0 && prefix <= 32) {
                    long base = ipv4ToLong(p[0]);
                    long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                    return (base & mask) == (target & mask);
                }
            }
        }

        if (startIp != null && endIp != null && isIpv4(startIp) && isIpv4(endIp)) {
            long s = ipv4ToLong(startIp);
            long e = ipv4ToLong(endIp);
            if (s > e) {
                long tmp = s; s = e; e = tmp;
            }
            return target >= s && target <= e;
        }

        return false;
    }
}

