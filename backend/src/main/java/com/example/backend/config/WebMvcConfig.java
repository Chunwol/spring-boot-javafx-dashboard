package com.example.backend.config;

import com.example.backend.interceptor.RateLimitingInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 전역 설정 — 인터셉터 등록 및 공용 빈 정의.
 *
 * <p>주요 역할:
 * <ul>
 *   <li>{@link ObjectMapper} 빈 등록 — Java 8 날짜/시간 타입(JSR-310) 직렬화 지원 포함</li>
 *   <li>에이전트 토큰 인터셉터({@link AgentTokenInterceptor}) 경로 매핑</li>
 *   <li>속도 제한 인터셉터({@link RateLimitingInterceptor}) 전체 API 경로 적용</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AgentTokenInterceptor agentTokenInterceptor;
    private final RateLimitingInterceptor rateLimitingInterceptor;

    /**
     * 전역 Jackson {@link ObjectMapper} 빈.
     * JavaTimeModule 등록으로 {@code LocalDateTime} 등 JSR-310 타입을 ISO-8601 형식으로
     * 직렬화/역직렬화한다.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ── 에이전트 토큰 인터셉터 ─────────────────────────────────────────
        // 에이전트(PC 클라이언트)가 호출하는 엔드포인트에만 적용한다.
        // /api/v1/agent/register는 설치 시 install-key 인증 방식이므로 제외.
        registry.addInterceptor(agentTokenInterceptor)
                .addPathPatterns(
                        "/api/v1/heartbeat",          // 하트비트 수신
                        "/api/v1/heartbeat/**",        // 하트비트 하위 경로
                        "/api/v1/agent/commands/**",   // 에이전트 명령 조회/응답
                        "/api/v1/agent/uploads",       // 파일 단건 업로드
                        "/api/v1/agent/file-chunks",   // 청크 업로드 시작
                        "/api/v1/agent/file-chunks/**",// 청크 업로드 조각 전송
                        "/api/v1/scripts/*/download"   // 스크립트 다운로드
                );

        // ── Rate Limiting 인터셉터 ────────────────────────────────────────
        // 모든 /api/** 경로에 적용하며 order(1)로 에이전트 인터셉터보다 먼저 실행된다.
        // 건강 체크·Swagger·Actuator 경로는 인터셉터 내부에서 면제 처리된다.
        registry.addInterceptor(rateLimitingInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
    }
}
