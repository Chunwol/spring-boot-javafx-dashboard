package com.example.backend.interceptor;

import com.example.backend.config.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingInterceptorTest {

    private RateLimitingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        config.setRequestsPerMinute(1);
        interceptor = new RateLimitingInterceptor(config);
    }

    @Test
    void remoteControlSessionRequestsBypassGenericRateLimit() throws Exception {
        assertThat(preHandle("/api/v1/remote-control/sessions").allowed()).isTrue();
        assertThat(preHandle("/api/v1/remote-control/sessions/session-1").allowed()).isTrue();
        assertThat(preHandle("/api/v1/remote-control/sessions/session-1/ticket").allowed()).isTrue();
    }

    @Test
    void regularApiRequestsRemainRateLimited() throws Exception {
        assertThat(preHandle("/api/v1/dashboard/summary").allowed()).isTrue();

        Result second = preHandle("/api/v1/dashboard/summary");

        assertThat(second.allowed()).isFalse();
        assertThat(second.response().getStatus()).isEqualTo(429);
    }

    private Result preHandle(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {
    }
}
