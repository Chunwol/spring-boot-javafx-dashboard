package com.example.backend.common.error;

public class ApiFieldError {
    private final String field;
    private final String reason;
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
