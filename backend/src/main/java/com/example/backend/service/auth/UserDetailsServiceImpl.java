package com.example.backend.service.auth;

import com.example.backend.model.user.User;
import com.example.backend.repository.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link UserDetailsService} 구현체 (레거시).
 *
 * <p><b>사용 중지</b>: {@code CustomUserDetailsService}가 실제 인증에 사용된다.
 * 이 구현체는 다음 두 가지 문제로 인해 비활성화되었다:
 * <ol>
 *   <li>{@code findAll()} + stream 방식: 사용자 수가 많을 때 전체 테이블을 로드해 메모리·쿼리 비효율</li>
 *   <li>역할(role) 고정: {@code ROLE_USER}만 반환하여 ADMIN/VIEWER 구분 불가</li>
 * </ol>
 *
 * @deprecated {@code CustomUserDetailsService}를 사용하세요. 이 클래스는 향후 삭제 예정입니다.
 * @since 2026-04-09
 */
@Deprecated(since = "2026-04-09", forRemoval = true)
// @Service  -- 비활성화: CustomUserDetailsService와 중복 빈 방지
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Autowired
    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 사용자명으로 {@link UserDetails}를 조회한다.
     *
     * <p><b>주의</b>: 전체 사용자 목록을 메모리에 올린 후 stream으로 필터링하는 비효율 패턴.
     * {@code CustomUserDetailsService}는 {@code findByUsername()} 쿼리로 대체함.
     *
     * @param username 조회할 사용자명
     * @return Spring Security {@link UserDetails} (ROLE_USER 고정)
     * @throws UsernameNotFoundException 사용자를 찾을 수 없는 경우
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findAll().stream().filter(x -> x.getUsername().equals(username)).findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Not found"));
        List<GrantedAuthority> auth = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new org.springframework.security.core.userdetails.User(u.getUsername(), u.getPassword(), auth);
    }
}
