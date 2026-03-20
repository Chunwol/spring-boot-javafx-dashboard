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

    public DefaultUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!defaultAdminSyncOnStartup) {
            logger.info("DEFAULT_ADMIN_SYNC_ON_STARTUP is false. Skipping admin sync.");
            return;
        }

        userRepository.findByUsername(defaultAdminUsername).ifPresentOrElse(user -> {
            boolean changed = false;

            // ENV 비밀번호와 DB 해시를 매칭하고, 불일치하면 ENV 값으로 재암호화 갱신
            boolean passwordMatches = passwordEncoder.matches(defaultAdminPassword, user.getPassword());
            if (!passwordMatches) {
                user.setPassword(passwordEncoder.encode(defaultAdminPassword));
                changed = true;
                logger.warn("Default admin password mismatch. Updated from ENV for user={}", defaultAdminUsername);
            }

            if (defaultAdminEmail != null && !defaultAdminEmail.isBlank()
                    && !defaultAdminEmail.equals(user.getEmail())) {
                user.setEmail(defaultAdminEmail);
                changed = true;
                logger.info("Default admin email updated from ENV for user={}", defaultAdminUsername);
            }

            if (changed) {
                userRepository.save(user);
            } else {
                logger.info("Default admin user is already in sync with ENV: {}", defaultAdminUsername);
            }
        }, () -> {
            String encodedPassword = passwordEncoder.encode(defaultAdminPassword);
            User admin = new User(defaultAdminUsername, encodedPassword, defaultAdminEmail);
            userRepository.save(admin);
            logger.info("Created default admin user from environment: {} (email={})", defaultAdminUsername, defaultAdminEmail);
        });
    }
}
