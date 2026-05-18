package com.example.backend.common.error;

/**
 * 인증되지 않은 접근을 나타내는 예외.
 *
 * <p>유효하지 않은 자격증명이나 누락된 인증 토큰으로 보호된 리소스에 접근할 때 던져진다.
 * {@code GlobalExceptionHandler}가 이 예외를 HTTP 401 응답으로 변환한다.
 *
 * <p>참고: 인증(Authentication) 실패에 사용한다.
 * 인가(Authorization) 실패(권한 부족)는 Spring Security의 {@code AccessDeniedException}을 사용할 것.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}