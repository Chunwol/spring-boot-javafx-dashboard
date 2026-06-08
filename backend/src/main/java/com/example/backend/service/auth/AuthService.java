package com.example.backend.service.auth;

import com.example.backend.common.error.UnauthorizedException;
import com.example.backend.dto.auth.LoginRequest;
import com.example.backend.dto.auth.LoginResponse;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserRoleResolver userRoleResolver;

    public LoginResponse login(LoginRequest request) {
        var user = userRepository.findByUsername(request.getUsername())
                .filter(u -> passwordEncoder.matches(request.getPassword(), u.getPassword()))
                .orElseThrow(() -> new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다."));
        String token = jwtUtil.generateToken(user.getUsername());
        String role = userRoleResolver.resolveRole(user.getUsername());
        return LoginResponse.of(user.getUsername(), token, role);
    }
}
