package com.example.backend.config;

import com.example.backend.interceptor.RateLimitingInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AgentTokenInterceptor agentTokenInterceptor;
    private final RateLimitingInterceptor rateLimitingInterceptor;

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 에이전트 채널: heartbeat, agent/commands, scripts/download에 적용
        // agent/register는 제외 (install-key 방식)
        registry.addInterceptor(agentTokenInterceptor)
                .addPathPatterns(
                        "/api/v1/heartbeat",
                        "/api/v1/heartbeat/**",
                        "/api/v1/agent/commands/**",
                        "/api/v1/agent/uploads",
                        "/api/v1/agent/file-chunks",
                        "/api/v1/agent/file-chunks/**",
                        "/api/v1/scripts/*/download"
                );

        // Rate limiting: Apply to all API endpoints
        registry.addInterceptor(rateLimitingInterceptor)
                .addPathPatterns("/api/**")
                .order(1); // Execute before other interceptors
    }
}
