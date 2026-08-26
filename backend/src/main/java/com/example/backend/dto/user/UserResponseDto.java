package com.example.backend.dto.user;

import com.example.backend.model.user.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class UserResponseDto {
    private Long id;
    private String username;
    private String email;
    private String role;
    private boolean active;
    private int failedLoginAttempts;

    public static UserResponseDto from(User user) {
        return from(user, user.getRole());
    }

    public static UserResponseDto from(User user, String role) {
        return UserResponseDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(role)
                .active(user.isActive())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .build();
    }
}
