package com.example.backend.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private String username;
    private String token;
    private String role;
    private List<String> permissions;

    public static LoginResponse of(String username, String token, String role, List<String> permissions) {
        return new LoginResponse(username, token, role, permissions);
    }

    public static LoginResponse of(String username, String token, String role) {
        return of(username, token, role, List.of());
    }
}
