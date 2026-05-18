package com.example.frontend.util;

/**
 * API URL 경로 정규화 유틸리티.
 * baseUrl이 이미 /api/v1을 포함하는 경우, downloadUrl에서 중복 prefix를 제거한다.
 */
public final class ApiUrlUtil {

    private ApiUrlUtil() {}

    /**
     * 다운로드 URL 경로를 정규화한다.
     * <ul>
     *   <li>절대 URL(http/https)인 경우 그대로 반환</li>
     *   <li>/api/v1/로 시작하면 /api/v1 prefix 제거 후 반환</li>
     *   <li>/로 시작하는 상대 경로는 그대로 반환</li>
     *   <li>기타 경우 앞에 /를 붙여 반환</li>
     * </ul>
     */
    public static String normalizeDownloadPath(String downloadUrl) {
        String u = downloadUrl == null ? "" : downloadUrl.trim();
        if (u.isBlank()) {
            return "";
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (u.startsWith("/api/v1/")) {
            return u.substring("/api/v1".length());
        }
        if (u.startsWith("/")) {
            return u;
        }
        return "/" + u;
    }
}
