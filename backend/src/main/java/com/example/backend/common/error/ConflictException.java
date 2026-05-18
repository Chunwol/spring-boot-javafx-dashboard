package com.example.backend.common.error;

/**
 * @deprecated {@link com.example.backend.common.exception.ConflictException} 사용 권장.
 * 이 클래스는 {@code common/exception/ConflictException}과 중복되며,
 * HTTP 상태 코드, 에러 코드, cause 지원이 부족한 구버전입니다.
 * 신규 코드에서는 {@code common.exception.ConflictException}을 사용하세요.
 */
@Deprecated
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
