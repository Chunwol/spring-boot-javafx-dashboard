package com.example.backend.dto.user;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UserUpdateRequestDto {
    @Size(max = 50, message = "username은 50자를 초과할 수 없습니다.")
    private String username;

    @Size(max = 200, message = "email은 200자를 초과할 수 없습니다.")
    private String email;

    @Pattern(regexp = "^(?i)(ADMIN|USER|VIEWER)?$", message = "role은 ADMIN/USER/VIEWER 중 하나여야 합니다.")
    private String role;
}
