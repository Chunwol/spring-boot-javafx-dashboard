package com.example.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AgentTokenInterceptor agentTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 에이전트 채널: heartbeat, agent/commands, scripts/download에 적용
        // agent/register는 제외 (install-key 방식)
        registry.addInterceptor(agentTokenInterceptor)
                .addPathPatterns(
                        "/api/v1/heartbeat",
                        "/api/v1/agent/commands/**",
                        "/api/v1/scripts/*/download"
                );
    }
}
