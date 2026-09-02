package com.example.backend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class UserSaveRequestDto {
    @NotBlank(message = "username은 필수입니다.")
    @Size(max = 50, message = "username은 50자를 초과할 수 없습니다.")
    private String username;

    @NotBlank(message = "password는 필수입니다.")
    @Size(min = 8, max = 100, message = "password는 8자 이상 100자 이하로 입력해야 합니다.")
    private String password;

    @NotBlank(message = "email은 필수입니다.")
    private String email;

    @Pattern(regexp = "^(?i)(ADMIN|USER|VIEWER)?$", message = "role은 ADMIN/USER/VIEWER 중 하나여야 합니다.")
    private String role;

    @Size(max = 500, message = "비고는 500자를 초과할 수 없습니다.")
    private String note;

    @Size(max = 100, message = "기능 권한은 최대 100개까지 선택할 수 있습니다.")
    private List<@Pattern(regexp = "^[A-Z_]+$", message = "기능 권한 값이 올바르지 않습니다.") String> permissions;
}
