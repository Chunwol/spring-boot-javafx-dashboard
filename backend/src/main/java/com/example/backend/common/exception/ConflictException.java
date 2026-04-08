package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when a request conflicts with existing data or current state.
 * Maps to HTTP 409 Conflict.
 */
public class ConflictException extends ApplicationException {

    private static final String CODE = "CONFLICT";

    public ConflictException(String message) {
        super(CODE, message, HttpStatus.CONFLICT.value());
    }

    public ConflictException(String entityType, String conflictReason) {
        super(CODE,
              String.format("%s 충돌: %s", entityType, conflictReason),
              HttpStatus.CONFLICT.value());
    }

    public ConflictException(String message, Throwable cause) {
        super(CODE, message, HttpStatus.CONFLICT.value(), cause);
    }
}
