package com.example.backend.config;

import com.example.backend.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Spring Security 설정 클래스.
 * JWT 기반 Stateless 인증을 사용하며, 역할(ADMIN/USER/VIEWER)별 API 접근 권한을 정의한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                             // REST API이므로 CSRF 비활성화
            .httpBasic(httpBasic -> httpBasic.disable())              // HTTP Basic 인증 비활성화
            .formLogin(form -> form.disable())                        // 폼 로그인 비활성화
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Stateless 세션
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(
                    "/actuator/health",                               // 헬스체크: 인증 불필요
                    "/actuator/health/**",                            // 헬스체크 하위 경로
                    "/api/v1/auth/login",                             // 로그인: 인증 불필요
                    "/api/v1/agent/register",                         // 에이전트 등록: 인증 불필요
                    "/api/v1/agent/uploads",                          // 에이전트 파일 업로드
                    "/api/v1/agent/file-chunks",                      // 파일 청크 업로드
                    "/api/v1/agent/file-chunks/**",                   // 파일 청크 하위 경로
                    "/api/v1/heartbeat",                              // 에이전트 하트비트: 토큰 검증은 AgentTokenInterceptor에서
                    "/api/v1/heartbeat/**",                           // 하트비트 하위 경로
                    "/api/v1/scripts/*/download",                     // 스크립트 다운로드: 에이전트 토큰으로 접근
                    "/ws/**",                                         // WebSocket 엔드포인트: 인증 불필요
                    "/swagger-ui/**",                                 // Swagger UI: 인증 불필요
                    "/swagger-ui.html",                               // Swagger UI HTML
                    "/v3/api-docs/**"                                 // OpenAPI 스펙
                ).permitAll()
                // Prometheus 메트릭: ADMIN만 접근 가능
                .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                // 에이전트 명령 폴링: AgentTokenInterceptor에서 토큰 검증 (Spring Security 인증 불필요)
                .requestMatchers("/api/v1/agent/commands/**").permitAll()
                // 조회용 GET API: ADMIN, USER, VIEWER 모두 허용
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("ADMIN", "USER", "VIEWER")
                // 변경/제어 API(POST, PUT, DELETE 등): ADMIN, USER만 허용 (VIEWER 제외)
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "USER")
                // 그 외 모든 요청: 인증 필요
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .cors(withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
