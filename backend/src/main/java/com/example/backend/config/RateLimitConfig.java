package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * API 요청 속도 제한(Rate Limit) 임계값 설정.
 *
 * <p>{@link com.example.backend.interceptor.RateLimitingInterceptor}가 이 설정값을 참조하여
 * 클라이언트 IP 또는 에이전트 MAC 단위로 분당·시간당 최대 요청 수를 제어한다.
 *
 * <p>설정 키 (application.yml 또는 .env):
 * <pre>
 * app.ratelimit.requests-per-minute  (기본 600, 초당 10건)
 * app.ratelimit.requests-per-hour    (기본 30000, 초당 약 8.3건)
 * app.ratelimit.concurrent-requests  (기본 100, 동시 처리 허용 요청 수)
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
public class RateLimitConfig {

    /** 분당 허용 최대 요청 수 (기본값: 600 = 초당 10건). 슬라이딩 윈도우 기준. */
    private int requestsPerMinute = 600;

    /** 시간당 허용 최대 요청 수 (기본값: 30000 = 초당 약 8.3건). 장기 버스트 제어용. */
    private int requestsPerHour = 30000;

    /** 동시에 처리 중인 최대 요청 수 (기본값: 100). 동시성 과부하 방지용. */
    private int concurrentRequests = 100;

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
