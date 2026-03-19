package com.example.backend.controller.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 관련 API
 * 사용자 로그인 및 토큰 발급을 담당합니다.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "사용자 인증 및 로그인 API")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 사용자 로그인
     * 사용자명과 비밀번호로 로그인하고 토큰을 받습니다.
     * 
     * @param loginRequest 사용자명과 비밀번호
     * @return 인증 토큰과 메시지
     */
    @PostMapping("/login")
    @Operation(
        summary = "사용자 로그인",
        description = "사용자명과 비밀번호로 인증하고 JWT 토큰을 받습니다. " +
                      "반환된 토큰은 이후 모든 API 요청의 Authorization 헤더에 포함시켜야 합니다."
    )
    @ApiResponse(
        responseCode = "200",
        description = "로그인 성공",
        content = @Content(schema = @Schema(implementation = LoginResponse.class))
    )
    @ApiResponse(responseCode = "400", description = "잘못된 자격증명 (사용자명 또는 비밀번호 오류)")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest) {
        // Legacy plaintext fallback is kept temporarily for backward compatibility with old seeded users.
        var userOpt = userRepository.findByUsername(loginRequest.getUsername());
        if (userOpt.isPresent()) {
            var user = userOpt.get();
            boolean matches = passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())
                    || loginRequest.getPassword().equals(user.getPassword());
            if (matches) {
                String token = jwtUtil.generateToken(user.getUsername());
                return ResponseEntity.ok(new LoginResponse(user.getUsername(), token));
            }
        }
        throw new UnauthorizedException("Invalid credentials");
    }
}