package com.example.frontend.service;

public class ApiClientException extends RuntimeException {

    private final int statusCode;
    private final boolean preferServerMessage;

    public ApiClientException(int statusCode, String message) {
        this(statusCode, message, false);
    }

    public ApiClientException(int statusCode, String message, boolean preferServerMessage) {
        super(message);
        this.statusCode = statusCode;
        this.preferServerMessage = preferServerMessage;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean preferServerMessage() {
        return preferServerMessage;
    }
}
