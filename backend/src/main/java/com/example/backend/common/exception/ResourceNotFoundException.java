package com.example.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown when a requested resource is not found.
 * Maps to HTTP 404 Not Found.
 */
public class ResourceNotFoundException extends ApplicationException {

    private static final String CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(CODE, message, HttpStatus.NOT_FOUND.value());
    }

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(CODE,
              String.format("%s을(를) 찾을 수 없습니다: %s", resourceType, resourceId),
              HttpStatus.NOT_FOUND.value());
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(CODE, message, HttpStatus.NOT_FOUND.value(), cause);
    }
}
