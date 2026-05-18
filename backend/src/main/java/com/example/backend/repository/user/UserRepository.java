package com.example.backend.repository.user;

import com.example.backend.model.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * User 엔티티 Repository.
 * 시스템 사용자 계정의 CRUD 및 사용자명 기반 조회를 제공한다.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 사용자명으로 계정을 조회한다 (로그인 인증에 사용).
     *
     * @param username 사용자명
     * @return 사용자 (없으면 empty)
     */
    Optional<User> findByUsername(String username);
}
