package com.example.backend.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import com.example.backend.common.exception.ConflictException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        ResponseEntity<ApiErrorResponse> resp = handler.handleIllegalArgument(
                new IllegalArgumentException("잘못된 입력"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("잘못된 입력");
        assertThat(resp.getBody().getPath()).isEqualTo("/api/test");
    }

    @Test
    void handleUnauthorized_returns401() {
        ResponseEntity<ApiErrorResponse> resp = handler.handleUnauthorized(
                new UnauthorizedException("인증 실패"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("인증 실패");
    }

    @Test
    void handleConflict_returns409() {
        ResponseEntity<ApiErrorResponse> resp = handler.handleConflict(
                new ConflictException("중복 데이터"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("중복 데이터");
    }

    @Test
    void handleNoSuchElement_returns404() {
        ResponseEntity<ApiErrorResponse> resp = handler.handleNoSuchElement(
                new NoSuchElementException("데이터 없음"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("요청한 리소스를 찾을 수 없습니다.");
    }

    @Test
    void handleUnexpected_returns500() {
        ResponseEntity<ApiErrorResponse> resp = handler.handleUnexpected(
                new RuntimeException("서버 오류"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }
}
