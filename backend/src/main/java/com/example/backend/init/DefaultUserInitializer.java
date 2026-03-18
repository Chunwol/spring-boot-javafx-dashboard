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

    public DefaultUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        userRepository.findByUsername(defaultAdminUsername).ifPresentOrElse(user -> {
            logger.info("Default admin user already exists: {}", defaultAdminUsername);
        }, () -> {
            String encodedPassword = passwordEncoder.encode(defaultAdminPassword);
            User admin = new User(defaultAdminUsername, encodedPassword, defaultAdminEmail);
            userRepository.save(admin);
            logger.info("Created default admin user from environment: {} (email={})", defaultAdminUsername, defaultAdminEmail);
        });
    }
}
