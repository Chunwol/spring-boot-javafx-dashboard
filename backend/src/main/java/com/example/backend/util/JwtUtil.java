package com.example.backend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JWT 토큰 생성 및 검증 유틸리티.
 *
 * <p>HMAC-SHA256 알고리즘을 사용하여 사용자명 기반의 JWT를 생성하고,
 * 파싱·검증하여 Claims를 반환한다.</p>
 *
 * <h3>보안 주의사항</h3>
 * <ul>
 *   <li>시크릿 키({@code app.jwt.secret})는 반드시 32바이트(256비트) 이상이어야 한다.</li>
 *   <li>시크릿 키를 소스 코드나 버전 관리 시스템에 커밋하지 말 것.
 *       환경 변수({@code JWT_SECRET}) 또는 Vault를 통해 주입하라.</li>
 *   <li>"changeme", "default" 등 취약한 시크릿은 서버 시작 시 예외를 발생시킨다.</li>
 *   <li>토큰 만료 시간({@code app.jwt.expiration})은 운영 환경에서 적절히 단축할 것 (기본 1시간).</li>
 * </ul>
 */
@Component
public class JwtUtil {

    /**
     * JWT 서명 시크릿 키.
     * 환경 변수 {@code JWT_SECRET} 또는 프로퍼티 {@code app.jwt.secret}로 설정.
     * 기본값은 취약하므로 운영 환경에서는 반드시 교체해야 한다.
     */
    @Value("${app.jwt.secret:defaultsecretchangemechangemechangemechangeme}")
    private String secret;

    /**
     * JWT 토큰 만료 시간 (밀리초).
     * 기본값: 3600000ms (1시간).
     */
    @Value("${app.jwt.expiration:3600000}")
    private long expirationMs;

    private Key key;

    /**
     * 빈 초기화 시 시크릿 키 유효성을 검사하고 서명 키를 생성한다.
     *
     * <p>시크릿 키가 취약하거나 32바이트 미만인 경우 {@link IllegalStateException}을 발생시킨다.</p>
     *
     * @throws IllegalStateException 시크릿 키가 안전하지 않은 경우
     */
    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()
                || secret.toLowerCase().contains("change-me")
                || secret.toLowerCase().contains("changeme")
                || secret.toLowerCase().contains("default")
                || secret.length() < 32) {
            throw new IllegalStateException(
                "JWT secret is not configured securely. " +
                "Set 'JWT_SECRET' (at least 32 bytes) in environment before starting.");
        }
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 사용자명을 subject로 하는 JWT 토큰을 생성한다.
     *
     * @param username 사용자명 (JWT subject)
     * @return 서명된 JWT 문자열
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * JWT 토큰의 서명을 검증하고 Claims를 반환한다.
     *
     * <p>만료되거나 서명이 유효하지 않은 경우 {@code io.jsonwebtoken.JwtException}이 발생한다.</p>
     *
     * @param token 검증할 JWT 문자열
     * @return 파싱된 Claims (subject, iat, exp 등)
     * @throws io.jsonwebtoken.JwtException 서명 불일치 또는 만료 등
     */
    public Claims validateAndGetClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }
}
