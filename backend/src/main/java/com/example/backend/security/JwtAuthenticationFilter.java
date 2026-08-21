package com.example.backend.security;

import com.example.backend.service.auth.CustomUserDetailsService;
import com.example.backend.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT Bearer 토큰을 검증하고 Spring Security의 {@code SecurityContext}를 설정하는 서블릿 필터.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li><b>토큰 추출</b>: {@code Authorization: Bearer <token>} 헤더를 파싱한다.
 *       헤더가 없거나 형식이 맞지 않으면 필터 체인을 그대로 통과시킨다(비인증 요청으로 처리).</li>
 *   <li><b>토큰 검증</b>: {@link com.example.backend.util.JwtUtil#validateAndGetClaims(String)}로
 *       서명·만료 여부를 확인하고 {@code Claims}(페이로드)를 추출한다.</li>
 *   <li><b>SecurityContext 설정</b>: 이미 인증이 설정되지 않은 경우에만
 *       {@code UserDetails}를 로드하여 {@link UsernamePasswordAuthenticationToken}을 등록한다.
 *       이중 인증 처리를 방지하기 위해 {@code SecurityContext.getAuthentication() == null} 조건을 확인한다.</li>
 *   <li><b>예외 처리</b>: 토큰 만료({@code ExpiredJwtException})·서명 위조·파싱 오류 등
 *       모든 예외는 DEBUG 로그로 기록하고 SecurityContext를 초기화한 뒤
 *       필터 체인을 계속 진행한다(이후 Spring Security가 401/403으로 처리).</li>
 * </ol>
 *
 * <p>WHY OncePerRequestFilter: 동일 요청이 forward/include로 재진입해도 필터가 중복 실행되지 않도록
 * Spring이 제공하는 기반 클래스를 사용한다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, CustomUserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // ── Step 1: Authorization 헤더에서 Bearer 토큰 추출 ──────────────
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 헤더가 없거나 Bearer 형식이 아니면 JWT 검증 없이 다음 필터로 위임
            filterChain.doFilter(request, response);
            return;
        }

        // "Bearer " 접두사(7자) 제거하여 순수 토큰 문자열 추출
        String token = authHeader.substring(7);
        try {
            // ── Step 2: 토큰 서명·만료 검증 및 클레임(페이로드) 추출 ─────
            Claims claims = jwtUtil.validateAndGetClaims(token);
            String username = claims.getSubject();

            // ── Step 3: SecurityContext에 인증 정보 등록 ─────────────────
            // 이미 인증된 경우(예: 세션 기반 인증) 중복 설정을 방지
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            // ── Step 4: 토큰 만료·위조·파싱 오류 처리 ───────────────────
            // 예외를 전파하지 않고 SecurityContext만 초기화 — 이후 필터/핸들러가 403/401 반환
            log.debug("JWT 인증 실패: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
