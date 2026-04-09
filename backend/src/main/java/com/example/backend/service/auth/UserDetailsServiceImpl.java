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

import java.util.List;

/**
 * @deprecated CustomUserDetailsService를 사용하세요. 이 클래스는 ROLE_USER만 지원하며
 * findAll() + stream 비효율 패턴을 사용합니다. 향후 삭제 예정.
 */
@Deprecated(since = "2026-04-09", forRemoval = true)
// @Service  -- 비활성화: CustomUserDetailsService와 중복 빈 방지
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Autowired
    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findAll().stream().filter(x -> x.getUsername().equals(username)).findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Not found"));
        List<GrantedAuthority> auth = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new org.springframework.security.core.userdetails.User(u.getUsername(), u.getPassword(), auth);
    }
}
