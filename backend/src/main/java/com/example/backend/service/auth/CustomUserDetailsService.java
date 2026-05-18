package com.example.backend.service.auth;

import com.example.backend.repository.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security {@link UserDetailsService} 구현체.
 * <p>
 * 사용자 이름으로 DB를 조회하여 {@link UserDetails}를 반환한다.
 * 역할(ADMIN/VIEWER/USER) 해석은 {@link UserRoleResolver}에 위임한다.
 * </p>
 * <p>
 * {@link UserDetailsServiceImpl}이 동일 인터페이스를 구현했지만,
 * {@code findAll()} + stream 비효율 패턴 및 단일 ROLE_USER만 지원하는 한계로
 * 이 클래스가 대체한다. {@code UserDetailsServiceImpl}은 @Deprecated 처리되어 빈으로 등록되지 않는다.
 * </p>
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleResolver userRoleResolver;

    public CustomUserDetailsService(UserRepository userRepository,
                                    UserRoleResolver userRoleResolver) {
        this.userRepository = userRepository;
        this.userRoleResolver = userRoleResolver;
    }

    /**
     * 사용자 이름으로 {@link UserDetails}를 로드한다.
     * 조회 전용 트랜잭션으로 처리하여 불필요한 쓰기 락을 방지한다.
     *
     * @param username 로그인 사용자 이름
     * @return Spring Security UserDetails 객체
     * @throws UsernameNotFoundException 해당 사용자가 없는 경우
     */
    @Override
    @Transactional(readOnly = true)
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

    /**
     * 애플리케이션 역할(ADMIN/VIEWER/USER)을 Spring Security roles 배열로 변환한다.
     * ADMIN은 ADMIN + USER 권한을 모두 부여한다.
     *
     * @param username 사용자 이름
     * @return Spring Security roles 배열
     */
    private String[] resolveSpringRoles(String username) {
        String role = userRoleResolver.resolveRole(username);
        return switch (role) {
            case "ADMIN" -> new String[]{"ADMIN", "USER"};
            case "VIEWER" -> new String[]{"VIEWER"};
            default -> new String[]{"USER"};
        };
    }
}
