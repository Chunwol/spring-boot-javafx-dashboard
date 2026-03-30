package com.example.backend.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApiErrorResponseTest {

    @Test
    void of_createsResponseWithCorrectFields() {
        ApiErrorResponse resp = ApiErrorResponse.of(400, "Bad Request", "잘못된 요청", "/api/test");

        assertThat(resp.getStatus()).isEqualTo(400);
        assertThat(resp.getError()).isEqualTo("Bad Request");
        assertThat(resp.getMessage()).isEqualTo("잘못된 요청");
        assertThat(resp.getPath()).isEqualTo("/api/test");
        assertThat(resp.getTimestamp()).isNotNull();
        assertThat(resp.getErrors()).isEmpty();
    }

    @Test
    void addFieldError_addsToList() {
        ApiErrorResponse resp = ApiErrorResponse.of(400, "Validation", "검증 실패", "/api/test");
        resp.addFieldError("name", "필수 입력", null);
        resp.addFieldError("email", "형식 오류", "bad-email");

        assertThat(resp.getErrors()).hasSize(2);
        assertThat(resp.getErrors().get(0).getField()).isEqualTo("name");
        assertThat(resp.getErrors().get(1).getField()).isEqualTo("email");
    }
}
