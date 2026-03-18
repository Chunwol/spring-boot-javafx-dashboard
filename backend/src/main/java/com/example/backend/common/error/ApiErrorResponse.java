package com.example.backend.common.error;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ApiErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
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
