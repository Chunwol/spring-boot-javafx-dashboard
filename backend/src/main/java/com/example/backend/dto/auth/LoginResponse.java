package com.example.backend.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private String username;
    private String token;
    private String role;

    public static LoginResponse of(String username, String token, String role) {
        return new LoginResponse(username, token, role);
    }
}
