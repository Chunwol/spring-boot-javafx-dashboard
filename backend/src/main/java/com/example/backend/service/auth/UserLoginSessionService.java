package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserLoginSessionService {

    private static final long ACTIVITY_WRITE_INTERVAL_SECONDS = 30;

    private final UserRepository userRepository;

    @Transactional
    public boolean validateAndTouch(String username, String sessionId) {
        if (username == null || sessionId == null || sessionId.isBlank()) return false;
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isActive() || !user.hasLoginSession(sessionId)) return false;
        LocalDateTime now = LocalDateTime.now();
        if (user.getLastActivityAt() == null
                || user.getLastActivityAt().isBefore(now.minusSeconds(ACTIVITY_WRITE_INTERVAL_SECONDS))) {
            user.touchLoginSession(sessionId, now);
        }
        return true;
    }

    @Transactional
    public boolean revoke(String username, String sessionId) {
        if (username == null || sessionId == null || sessionId.isBlank()) return false;
        User user = userRepository.findByUsername(username)
                .filter(candidate -> candidate.hasLoginSession(sessionId))
                .orElse(null);
        if (user == null) return false;
        user.clearLoginSession();
        return true;
    }
}
