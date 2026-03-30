package com.example.backend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-that-is-at-least-32-bytes-long!!");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3600000L);
        jwtUtil.init();
    }

    @Test
    void generateToken_returnNonEmptyString() {
        String token = jwtUtil.generateToken("admin");
        assertThat(token).isNotBlank();
    }

    @Test
    void validateAndGetClaims_validToken() {
        String token = jwtUtil.generateToken("testuser");
        Claims claims = jwtUtil.validateAndGetClaims(token);
        assertThat(claims.getSubject()).isEqualTo("testuser");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void validateAndGetClaims_invalidToken_throws() {
        assertThatThrownBy(() -> jwtUtil.validateAndGetClaims("invalid.token.here"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void validateAndGetClaims_expiredToken_throws() {
        JwtUtil shortLived = new JwtUtil();
        ReflectionTestUtils.setField(shortLived, "secret", "test-secret-key-that-is-at-least-32-bytes-long!!");
        ReflectionTestUtils.setField(shortLived, "expirationMs", -1000L); // already expired
        shortLived.init();

        String token = shortLived.generateToken("expired-user");
        assertThatThrownBy(() -> jwtUtil.validateAndGetClaims(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void differentUsers_differentTokens() {
        String token1 = jwtUtil.generateToken("user1");
        String token2 = jwtUtil.generateToken("user2");
        assertThat(token1).isNotEqualTo(token2);
    }
}
