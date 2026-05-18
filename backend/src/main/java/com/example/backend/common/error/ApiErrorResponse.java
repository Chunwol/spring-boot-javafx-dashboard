package com.example.backend.common.error;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * API 오류 응답 표준 DTO.
 *
 * <p>모든 예외 핸들러({@code GlobalExceptionHandler})가 이 클래스를 사용하여
 * 일관된 JSON 오류 응답을 반환한다.
 *
 * <p>응답 예시:
 * <pre>{@code
 * {
 *   "timestamp": "2026-05-19T10:30:00",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "요청 검증 실패: macAddress - 필수값입니다",
 *   "path": "/api/v1/heartbeat",
 *   "errors": [
 *     {"field": "macAddress", "reason": "필수값입니다", "rejectedValue": null}
 *   ]
 * }
 * }</pre>
 */
public class ApiErrorResponse {
    /** 오류 발생 시각 (서버 시간 기준) */
    private LocalDateTime timestamp;

    /** HTTP 상태 코드 (예: 400, 401, 404, 500) */
    private int status;

    /** HTTP 상태 텍스트 (예: "Bad Request", "Unauthorized") */
    private String error;

    /** 사람이 읽을 수 있는 오류 상세 메시지 */
    private String message;

    /** 오류가 발생한 요청 경로 (예: "/api/v1/heartbeat") */
    private String path;

    /** 입력값 검증 실패 필드 목록. 단순 오류의 경우 빈 리스트. */
    private List<ApiFieldError> errors;

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        ApiErrorResponse resp = new ApiErrorResponse();
        resp.timestamp = LocalDateTime.now();
        resp.status = status;
        resp.error = error;
        resp.message = message;
        resp.path = path;
        resp.errors = new ArrayList<>();
        return resp;
    }

    public ApiErrorResponse addFieldError(String field, String reason, Object rejectedValue) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(new ApiFieldError(field, reason, rejectedValue));
        return this;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public List<ApiFieldError> getErrors() {
        return errors;
    }
}
