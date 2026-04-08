package com.example.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class FileDistributionRequestSecurity {

    @Value("${app.file-distribution.security.enabled:false}")
    private boolean enabled;

    @Value("${app.file-distribution.security.require-https:true}")
    private boolean requireHttps;

    @Value("${app.file-distribution.security.max-skew-seconds:60}")
    private long maxSkewSeconds;

    @Value("${app.file-distribution.security.shared-secret:change-me-file-distribution-secret}")
    private String sharedSecret;

    public void validateOrThrow(HttpServletRequest request, String canonicalPayload) {
        if (!enabled) return;

        if (requireHttps && !isHttps(request)) {
            throw new IllegalArgumentException("HTTPS required");
        }

        String tsHeader = request.getHeader("X-FD-Timestamp");
        String sigHeader = request.getHeader("X-FD-Signature");
        if (tsHeader == null || sigHeader == null || tsHeader.isBlank() || sigHeader.isBlank()) {
            throw new IllegalArgumentException("Missing signature headers");
        }

        long ts;
        try {
            ts = Long.parseLong(tsHeader);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid timestamp");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > maxSkewSeconds) {
            throw new IllegalArgumentException("Signature timestamp expired");
        }

        String expected = hmacSha256Hex(sharedSecret, tsHeader + "\n" + canonicalPayload);
        if (!constantTimeEquals(expected, sigHeader)) {
            throw new IllegalArgumentException("Invalid request signature");
        }
    }

    private boolean isHttps(HttpServletRequest req) {
        if (req.isSecure()) return true;
        String fp = req.getHeader("X-Forwarded-Proto");
        return fp != null && "https".equalsIgnoreCase(fp);
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC init failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int diff = 0;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
