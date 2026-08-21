package com.example.backend.service.user;

import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.dto.user.UserResponseDto;
import com.example.backend.dto.user.UserSaveRequestDto;
import com.example.backend.dto.user.UserUpdateRequestDto;
import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.security.WebSocketSessionExpiryRegistry;
import com.example.backend.service.auth.UserRoleResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleResolver userRoleResolver;
    private final WebSocketSessionExpiryRegistry webSocketSessions;

    public List<UserResponseDto> findAll() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public UserResponseDto findOne(Long id) {
        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional
    public UserResponseDto save(UserSaveRequestDto dto) {
        User user = new User(
                dto.getUsername(),
                passwordEncoder.encode(dto.getPassword()),
                dto.getEmail(),
                normalizeRole(dto.getRole()));
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponseDto update(Long id, UserUpdateRequestDto dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        String previousUsername = user.getUsername();
        if (dto.getUsername() != null) user.changeUsername(dto.getUsername());
        if (dto.getEmail() != null) user.changeEmail(dto.getEmail());
        if (dto.getRole() != null) user.changeRole(normalizeRole(dto.getRole()));
        User saved = userRepository.save(user);
        webSocketSessions.closeUserSessions(previousUsername);
        if (!previousUsername.equalsIgnoreCase(saved.getUsername())) {
            webSocketSessions.closeUserSessions(saved.getUsername());
        }
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        userRepository.delete(user);
        webSocketSessions.closeUserSessions(user.getUsername());
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ADMIN", "USER", "VIEWER" -> normalized;
            default -> "USER";
        };
    }

    private UserResponseDto toResponse(User user) {
        return UserResponseDto.from(user, userRoleResolver.resolveRole(user));
    }
}
