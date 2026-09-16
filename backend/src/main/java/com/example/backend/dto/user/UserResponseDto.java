package com.example.backend.dto.user;

import com.example.backend.model.user.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class UserResponseDto {
    private Long id;
    private String username;
    private String email;
    private String role;
    private String note;
    private List<String> permissions;
    private boolean active;
    private int failedLoginAttempts;
    private boolean loggedIn;
    private String loginIpAddress;
    private String loginUserAgent;
    private LocalDateTime loginAt;
    private LocalDateTime lastActivityAt;

    public static UserResponseDto from(User user) {
        return from(user, user.getRole(), List.of());
    }

    public static UserResponseDto from(User user, String role, List<String> permissions) {
        return from(user, role, permissions, null);
    }

    public static UserResponseDto from(User user, String role, List<String> permissions,
                                       LocalDateTime browserOnlineSince) {
        boolean loggedIn = user.getActiveSessionId() != null
                && (browserOnlineSince == null
                || (user.getLastActivityAt() != null && !user.getLastActivityAt().isBefore(browserOnlineSince)));
        return UserResponseDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(role)
                .note(user.getNote())
                .permissions(permissions)
                .active(user.isActive())
                .failedLoginAttempts(user.getFailedLoginAttempts())
                .loggedIn(loggedIn)
                .loginIpAddress(user.getLoginIpAddress())
                .loginUserAgent(user.getLoginUserAgent())
                .loginAt(user.getLoginAt())
                .lastActivityAt(user.getLastActivityAt())
                .build();
    }
}
