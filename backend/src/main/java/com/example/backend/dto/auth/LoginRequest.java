package com.example.backend.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 요청 DTO
 */
@Getter
@NoArgsConstructor
@Schema(description = "사용자 로그인 요청")
public class LoginRequest {
    @NotBlank(message = "username은 필수입니다.")
    @Size(max = 50, message = "username은 50자를 초과할 수 없습니다.")
    @Schema(description = "사용자명", example = "admin")
    private String username;
    
    @NotBlank(message = "password는 필수입니다.")
    @Size(max = 100, message = "password는 100자를 초과할 수 없습니다.")
    @Schema(description = "비밀번호", example = "password")
    private String password;
}