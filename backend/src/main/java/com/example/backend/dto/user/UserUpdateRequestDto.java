package com.example.backend.dto.user;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class UserUpdateRequestDto {
    @Size(max = 50, message = "username은 50자를 초과할 수 없습니다.")
    private String username;

    @Size(max = 200, message = "email은 200자를 초과할 수 없습니다.")
    private String email;

    @Pattern(regexp = "^(?i)(ADMIN|USER|VIEWER)?$", message = "role은 ADMIN/USER/VIEWER 중 하나여야 합니다.")
    private String role;

    @Size(max = 500, message = "비고는 500자를 초과할 수 없습니다.")
    private String note;

    @Size(max = 100, message = "기능 권한은 최대 100개까지 선택할 수 있습니다.")
    private List<@Pattern(regexp = "^[A-Z_]+$", message = "기능 권한 값이 올바르지 않습니다.") String> permissions;
}
