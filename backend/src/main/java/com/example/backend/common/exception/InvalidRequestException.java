package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when a request contains invalid data or violates business rules.
 * Maps to HTTP 400 Bad Request.
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
