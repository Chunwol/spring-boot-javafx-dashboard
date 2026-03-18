package com.example.backend.event;

import org.springframework.context.ApplicationEvent;

public class AlertEvent extends ApplicationEvent {
    private final String macAddress;
    private final String message;

    public AlertEvent(Object source, String macAddress, String message) {
        super(source);
        this.macAddress = macAddress;
        this.message = message;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getMessage() {
        return message;
    }
}
