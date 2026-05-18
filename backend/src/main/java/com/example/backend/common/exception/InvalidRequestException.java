package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 요청 데이터가 유효하지 않거나 비즈니스 규칙을 위반할 때 던져지는 예외.
 *
 * <p>사용 사례:
 * <ul>
 *   <li>필수 필드 누락 또는 잘못된 형식 (MAC 주소, IP 등)</li>
 *   <li>허용 범위를 벗어난 값 (음수 임계값, 미래 날짜 등)</li>
 *   <li>비즈니스 규칙 위반 (비활성 그룹에 PC 추가 시도 등)</li>
 * </ul>
 *
 * <p>HTTP 400 Bad Request로 매핑된다.
 */
public class InvalidRequestException extends ApplicationException {

    private static final String CODE = "INVALID_REQUEST";

    public InvalidRequestException(String message) {
        super(CODE, message, HttpStatus.BAD_REQUEST.value());
    }

    public InvalidRequestException(String fieldName, String issue) {
        super(CODE,
              String.format("요청 검증 실패: %s - %s", fieldName, issue),
              HttpStatus.BAD_REQUEST.value());
    }

    public InvalidRequestException(String message, Throwable cause) {
        super(CODE, message, HttpStatus.BAD_REQUEST.value(), cause);
    }
}
