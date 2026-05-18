package com.example.backend.config;

import com.example.backend.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

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
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/api/v1/auth/login",
                    "/api/v1/agent/register",
                    "/api/v1/agent/uploads",
                    "/api/v1/agent/file-chunks",
                    "/api/v1/agent/file-chunks/**",
                    "/api/v1/heartbeat",
                    "/api/v1/heartbeat/**",
                    "/api/v1/scripts/*/download",
                    "/ws/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                ).permitAll()
                .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                // 에이전트 명령 엔드포인트: AgentTokenInterceptor에서 토큰 검증 (인증 불필요 but filter 통과)
                .requestMatchers("/api/v1/agent/commands/**").permitAll()
                // 조회용 API는 ADMIN/USER/VIEWER 허용
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("ADMIN", "USER", "VIEWER")
                // 변경/제어 API는 VIEWER를 제외한 사용자(ADMIN/USER) 허용
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "USER")
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

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers(
                "/api/v1/heartbeat",
                "/api/v1/heartbeat/**"
        );
    }
}
