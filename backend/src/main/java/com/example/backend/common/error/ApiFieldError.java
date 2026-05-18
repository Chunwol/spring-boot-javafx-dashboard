package com.example.backend.common.error;

/**
 * API 응답 내 단일 필드 검증 오류를 나타내는 DTO.
 *
 * <p>{@link ApiErrorResponse#getErrors()} 리스트의 원소로 사용된다.
 * Spring의 {@code BindingResult}·{@code ConstraintViolation} 등에서 변환하여 생성한다.
 */
public class ApiFieldError {
    /** 검증에 실패한 필드명 (예: "macAddress", "groupId") */
    private final String field;

    /** 실패 이유 (예: "필수값입니다", "유효하지 않은 형식") */
    private final String reason;

    /** 거부된 실제 입력값 (민감 정보인 경우 null로 마스킹) */
    private final Object rejectedValue;

    public ApiFieldError(String field, String reason, Object rejectedValue) {
        this.field = field;
        this.reason = reason;
        this.rejectedValue = rejectedValue;
    }

    public String getField() {
        return field;
    }

    public String getReason() {
        return reason;
    }

    public Object getRejectedValue() {
        return rejectedValue;
    }
}
