package com.example.backend.service.user;

import com.example.backend.common.exception.ResourceNotFoundException;
import com.example.backend.common.exception.InvalidRequestException;
import com.example.backend.dto.user.UserResponseDto;
import com.example.backend.dto.user.UserSaveRequestDto;
import com.example.backend.dto.user.UserUpdateRequestDto;
import com.example.backend.dto.user.UserAccessHistoryResponseDto;
import com.example.backend.model.user.User;
import com.example.backend.model.user.FeaturePermission;
import com.example.backend.remotecontrol.RemoteControlSessionService;
import com.example.backend.repository.user.UserRepository;
import com.example.backend.security.WebSocketSessionExpiryRegistry;
import com.example.backend.service.auth.UserRoleResolver;
import com.example.backend.service.auth.UserFeaturePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final List<String> ACCESS_HISTORY_ACTIONS = List.of(
            "LOGIN_SUCCESS", "LOGOUT", "LOGIN_FAILED", "LOGIN_BLOCKED",
            "LOGIN_TEMPORARILY_LOCKED", "LOGIN_ACCOUNT_DISABLED");
    private static final String ACCESS_HISTORY_SQL = """
            SELECT id, actor, action, ip_address, user_agent, detail_json, created_at
            FROM audit_logs
            WHERE action IN (?, ?, ?, ?, ?, ?)
            ORDER BY id DESC
            LIMIT 200
            """;

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleResolver userRoleResolver;
    private final UserFeaturePermissionService featurePermissionService;
    private final WebSocketSessionExpiryRegistry webSocketSessions;
    private final RemoteControlSessionService remoteControlSessions;

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

    public List<UserAccessHistoryResponseDto> findAccessHistory() {
        return jdbcTemplate.query(ACCESS_HISTORY_SQL, (resultSet, rowNumber) ->
                        UserAccessHistoryResponseDto.builder()
                                .id(resultSet.getLong("id"))
                                .username(resultSet.getString("actor"))
                                .eventType(resultSet.getString("action"))
                                .ipAddress(resultSet.getString("ip_address"))
                                .userAgent(resultSet.getString("user_agent"))
                                .detailJson(resultSet.getString("detail_json"))
                                .occurredAt(parseAuditTimestamp(resultSet.getObject("created_at")))
                                .build(),
                ACCESS_HISTORY_ACTIONS.toArray());
    }

    private LocalDateTime parseAuditTimestamp(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof Number number) {
            long raw = number.longValue();
            long epochMillis = Math.abs(raw) < 10_000_000_000L ? raw * 1000 : raw;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
        }
        String raw = String.valueOf(value).trim();
        if (raw.matches("-?\\d+")) {
            return parseAuditTimestamp(Long.parseLong(raw));
        }
        try {
            return LocalDateTime.parse(raw.replace(' ', 'T'));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    @Transactional
    public UserResponseDto save(UserSaveRequestDto dto) {
        User user = new User(
                dto.getUsername(),
                passwordEncoder.encode(dto.getPassword()),
                dto.getEmail(),
                normalizeRole(dto.getRole()));
        user.changeNote(normalizeNote(dto.getNote()));
        user.changeFeaturePermissions(normalizePermissions(user.getRole(), dto.getPermissions()));
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponseDto update(Long id, UserUpdateRequestDto dto) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        String previousUsername = user.getUsername();
        String previousRole = userRoleResolver.resolveRole(user);
        List<String> previousPermissions = featurePermissionService.resolveCodes(user, previousRole);
        if (dto.getUsername() != null) user.changeUsername(dto.getUsername());
        if (dto.getEmail() != null) user.changeEmail(dto.getEmail());
        if (dto.getRole() != null) user.changeRole(normalizeRole(dto.getRole()));
        if (dto.getNote() != null) user.changeNote(normalizeNote(dto.getNote()));
        if (dto.getPermissions() != null || dto.getRole() != null) {
            user.changeFeaturePermissions(normalizePermissions(user.getRole(), dto.getPermissions()));
        }
        String updatedRole = userRoleResolver.resolveRole(user);
        List<String> updatedPermissions = featurePermissionService.resolveCodes(user, updatedRole);
        if (!previousUsername.equalsIgnoreCase(user.getUsername())
                || !previousRole.equals(updatedRole)
                || !previousPermissions.equals(updatedPermissions)) user.clearLoginSession();
        User saved = userRepository.save(user);
        webSocketSessions.closeUserSessions(previousUsername);
        remoteControlSessions.closeOwnerSessions(previousUsername);
        if (!previousUsername.equalsIgnoreCase(saved.getUsername())) {
            webSocketSessions.closeUserSessions(saved.getUsername());
            remoteControlSessions.closeOwnerSessions(saved.getUsername());
        }
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        userRepository.delete(user);
        webSocketSessions.closeUserSessions(user.getUsername());
        remoteControlSessions.closeOwnerSessions(user.getUsername());
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

    private String normalizeNote(String note) {
        if (note == null) return null;
        String normalized = note.replaceAll("[\\r\\n\\u0000]", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private UserResponseDto toResponse(User user) {
        String role = userRoleResolver.resolveRole(user);
        return UserResponseDto.from(user, role, featurePermissionService.resolveCodes(user, role));
    }

    private String normalizePermissions(String role, List<String> values) {
        if (!"USER".equalsIgnoreCase(role)) return null;
        if (values == null) return null;
        try {
            Set<FeaturePermission> permissions = FeaturePermission.requireValid(values);
            return FeaturePermission.serialize(permissions);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(exception.getMessage(), exception);
        }
    }
}
