package com.example.backend.common.exception;

/**
 * 애플리케이션 비즈니스 로직 예외의 공통 기반 클래스.
 *
 * <p>모든 도메인 예외({@link ResourceNotFoundException}, {@link InvalidRequestException},
 * {@link ConflictException} 등)는 이 클래스를 상속한다.
 * {@code GlobalExceptionHandler}는 이 타입을 기반으로 {@code httpStatus}에 따른
 * HTTP 응답을 자동으로 생성한다.
 *
 * <p>필드 설명:
 * <ul>
 *   <li>{@code code}: 머신-리더블 오류 식별자 (예: {@code "RESOURCE_NOT_FOUND"})</li>
 *   <li>{@code httpStatus}: 매핑될 HTTP 상태 코드 (기본값 500)</li>
 * </ul>
 */
public class ApplicationException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public ApplicationException(String code, String message) {
        super(message);
        this.code = code;
        this.httpStatus = 500;
    }

    public ApplicationException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public ApplicationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = 500;
    }

    public ApplicationException(String code, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
