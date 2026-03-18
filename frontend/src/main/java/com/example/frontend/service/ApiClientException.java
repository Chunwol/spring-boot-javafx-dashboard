package com.example.frontend.service;

public class ApiClientException extends RuntimeException {

    private final int statusCode;

    public ApiClientException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
