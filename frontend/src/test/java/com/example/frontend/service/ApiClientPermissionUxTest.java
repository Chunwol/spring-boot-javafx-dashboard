package com.example.frontend.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientPermissionUxTest {

    @Test
    void viewerRoleDetectionIsCaseInsensitive() {
        ApiClient client = new ApiClient("http://localhost:8888/api/v1");
        client.setCurrentUserRole("viewer");
        assertTrue(client.isViewerRole());

        client.setCurrentUserRole("USER");
        assertFalse(client.isViewerRole());
    }

    @Test
    void forbiddenMessageIsStandardized() {
        Throwable failure = new CompletionException(new ApiClientException(403, "custom"));
        assertTrue(ApiClient.isForbidden(failure));
        assertEquals(ApiClient.FORBIDDEN_MESSAGE,
                ApiClient.userFacingErrorMessage(failure, "fallback"));
    }

    @Test
    void unauthorizedMessageIsStandardized() {
        Throwable failure = new CompletionException(new ApiClientException(401, "custom"));
        assertTrue(ApiClient.isUnauthorized(failure));
        assertEquals(ApiClient.UNAUTHORIZED_MESSAGE,
                ApiClient.userFacingErrorMessage(failure, "fallback"));
    }

    @Test
    void loginUnauthorizedMessageCanUseServerMessage() {
        Throwable failure = new CompletionException(new ApiClientException(401,
                "아이디 또는 비밀번호가 올바르지 않습니다.", true));
        assertEquals("아이디 또는 비밀번호가 올바르지 않습니다.",
                ApiClient.userFacingErrorMessage(failure, "fallback"));
    }

    @Test
    void viewerWriteDeniedMessageContainsAction() {
        String message = ApiClient.viewerWriteDeniedMessage("원격 명령 실행");
        assertTrue(message.contains("조회 전용"));
        assertTrue(message.contains("원격 명령 실행"));
    }
}
