package com.example.frontend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * 프론트엔드 환경 변수 관리 클래스
 * frontend/.env 또는 frontend.env 파일을 로드합니다.
 */
public class FrontendEnvConfig {
    private static final Logger log = LoggerFactory.getLogger(FrontendEnvConfig.class);
    private static final Map<String, String> properties = new HashMap<>();
    private static boolean loaded = false;

    static {
        loadEnv();
    }

    private static void loadEnv() {
        // frontend/.env 파일 경로 우선 시도
        String[] searchPaths = {
            "frontend/.env",    // 프로젝트 루트에서 frontend 폴더
            // 백엔드의 .env는 더이상 프론트엔드에서 직접 읽지 않음 (운영상 secrets 공유 금지)
            "frontend.env",      // 프로젝트 루트에 frontend.env 파일
            ".env"               // 호환성: 기본 .env 파일
        };
        
        for (String path : searchPaths) {
            File file = new File(path);
            if (file.exists()) {
                if (loadFile(file)) {
                    log.info("Loaded frontend env from: {}", file.getAbsolutePath());
                    loaded = true;
                    break;
                }
            }
        }

        if (!loaded) {
            log.warn("No frontend env file found. Using defaults.");
        }
    }

    private static boolean loadFile(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                properties.put(key, stripQuotes(value));
            }
            return true;
        } catch (Exception ex) {
            log.error("Failed to load env file: {}", file.getAbsolutePath());
            return false;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    public static String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public static String getProperty(String key) {
        return properties.get(key);
    }

    public static int getIntProperty(String key, int defaultValue) {
        String value = properties.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid integer for key: {}", key);
            }
        }
        return defaultValue;
    }

    public static double getDoubleProperty(String key, double defaultValue) {
        String value = properties.get(key);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid double for key: {}", key);
            }
        }
        return defaultValue;
    }

    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.get(key);
        if (value != null) {
            return "true".equalsIgnoreCase(value);
        }
        return defaultValue;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static Map<String, String> getAllProperties() {
        return new HashMap<>(properties);
    }
}
