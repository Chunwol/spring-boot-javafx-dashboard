package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

@ConfigurationProperties(prefix = "app.security.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of(
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    );

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        if (allowedOrigins == null) {
            this.allowedOrigins = List.of();
            return;
        }

        this.allowedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .map(CorsProperties::validateAndNormalizeOrigin)
                .distinct()
                .toList();
    }

    private static String validateAndNormalizeOrigin(String origin) {
        if (origin.contains("*")) {
            throw new IllegalArgumentException("CORS allowed origins must not contain wildcards: " + origin);
        }

        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid CORS allowed origin: " + origin, ex);
        }

        String scheme = uri.getScheme();
        boolean httpOrigin = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean originOnly = uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        if (!httpOrigin || !originOnly) {
            throw new IllegalArgumentException("CORS allowed origin must be an HTTP(S) origin: " + origin);
        }

        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
