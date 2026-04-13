package com.example.backend.service.auth;

import com.example.backend.repository.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleResolver userRoleResolver;

    public CustomUserDetailsService(UserRepository userRepository,
                                    UserRoleResolver userRoleResolver) {
        this.userRepository = userRepository;
        this.userRoleResolver = userRoleResolver;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .builder()
                        .username(user.getUsername())
                        .password(user.getPassword())
                        .roles(resolveSpringRoles(user.getUsername()))
                        .build()
                )
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private String[] resolveSpringRoles(String username) {
        String role = userRoleResolver.resolveRole(username);
        return switch (role) {
            case "ADMIN" -> new String[]{"ADMIN", "USER"};
            case "VIEWER" -> new String[]{"VIEWER"};
            default -> new String[]{"USER"};
        };
    }
}
