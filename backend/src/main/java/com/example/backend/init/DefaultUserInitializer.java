package com.example.backend.init;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DefaultUserInitializer implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(DefaultUserInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${DEFAULT_ADMIN_USERNAME:admin}")
    private String defaultAdminUsername;

    @Value("${DEFAULT_ADMIN_PASSWORD:password}")
    private String defaultAdminPassword;

    @Value("${DEFAULT_ADMIN_EMAIL:admin@example.com}")
    private String defaultAdminEmail;

    @Value("${DEFAULT_ADMIN_SYNC_ON_STARTUP:true}")
    private boolean defaultAdminSyncOnStartup;

    @Value("${DEFAULT_VIEWER_USERNAME:}")
    private String defaultViewerUsername;

    @Value("${DEFAULT_VIEWER_PASSWORD:}")
    private String defaultViewerPassword;

    @Value("${DEFAULT_VIEWER_EMAIL:viewer@example.com}")
    private String defaultViewerEmail;

    @Value("${DEFAULT_VIEWER_SYNC_ON_STARTUP:false}")
    private boolean defaultViewerSyncOnStartup;

    public DefaultUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (defaultAdminSyncOnStartup) {
            syncUserFromEnv(defaultAdminUsername, defaultAdminPassword, defaultAdminEmail, "admin");
        } else {
            logger.info("DEFAULT_ADMIN_SYNC_ON_STARTUP is false. Skipping admin sync.");
        }

        if (defaultViewerSyncOnStartup) {
            syncUserFromEnv(defaultViewerUsername, defaultViewerPassword, defaultViewerEmail, "viewer");
        } else {
            logger.info("DEFAULT_VIEWER_SYNC_ON_STARTUP is false. Skipping viewer sync.");
        }
    }

    private void syncUserFromEnv(String username, String rawPassword, String email, String label) {
        if (username == null || username.isBlank()) {
            logger.warn("DEFAULT_{}_USERNAME is blank. Skipping.", label.toUpperCase());
            return;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            logger.warn("DEFAULT_{}_PASSWORD is blank. Skipping.", label.toUpperCase());
            return;
        }

        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            boolean changed = false;

            boolean passwordMatches = passwordEncoder.matches(rawPassword, user.getPassword());
            if (!passwordMatches) {
                user.setPassword(passwordEncoder.encode(rawPassword));
                changed = true;
                logger.warn("Default {} password mismatch. Updated from ENV for user={}", label, username);
            }

            if (email != null && !email.isBlank() && !email.equals(user.getEmail())) {
                user.setEmail(email);
                changed = true;
                logger.info("Default {} email updated from ENV for user={}", label, username);
            }

            if (changed) {
                userRepository.save(user);
            } else {
                logger.info("Default {} user is already in sync with ENV: {}", label, username);
            }
        }, () -> {
            String encodedPassword = passwordEncoder.encode(rawPassword);
            User account = new User(username, encodedPassword, email);
            userRepository.save(account);
            logger.info("Created default {} user from environment: {} (email={})", label, username, email);
        });
    }
}
