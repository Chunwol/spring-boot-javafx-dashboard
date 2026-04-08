package com.example.backend.common.exception;

/**
 * Base exception for application-specific errors.
 * All business logic exceptions should extend this class.
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
