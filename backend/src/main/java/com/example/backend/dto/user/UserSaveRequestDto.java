package com.example.backend.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UserSaveRequestDto {
    @NotBlank(message = "username은 필수입니다.")
    @Size(max = 50, message = "username은 50자를 초과할 수 없습니다.")
    private String username;

    @NotBlank(message = "password는 필수입니다.")
    @Size(max = 100, message = "password는 100자를 초과할 수 없습니다.")
    private String password;

    @NotBlank(message = "email은 필수입니다.")
    private String email;
}
