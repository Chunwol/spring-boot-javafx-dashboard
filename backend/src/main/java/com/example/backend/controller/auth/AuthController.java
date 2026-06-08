package com.example.backend.controller.auth;

import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "사용자 인증 및 로그인 API")
public class AuthController {

    private final AuthService authService;

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
    @ApiResponse(responseCode = "401", description = "인증 실패")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }
}
