package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for API rate limiting settings.
 */
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
public class RateLimitConfig {

    private int requestsPerMinute = 600;  // Default: 10 requests per second
    private int requestsPerHour = 30000;  // Default: ~8.3 requests per second sustained
    private int concurrentRequests = 100; // Default: allow 100 concurrent requests

    // Getters and Setters
    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getRequestsPerHour() {
        return requestsPerHour;
    }

    public void setRequestsPerHour(int requestsPerHour) {
        this.requestsPerHour = requestsPerHour;
    }

    public int getConcurrentRequests() {
        return concurrentRequests;
    }

    public void setConcurrentRequests(int concurrentRequests) {
        this.concurrentRequests = concurrentRequests;
    }
}
