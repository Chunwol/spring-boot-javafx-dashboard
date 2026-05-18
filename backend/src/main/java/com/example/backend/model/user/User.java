package com.example.backend.model.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 시스템 사용자 엔티티.
 * 관리자(ADMIN), 일반 사용자(USER), 조회 전용(VIEWER) 등
 * 역할 기반 접근 제어에 사용되는 계정 정보를 저장한다.
 */
@Schema(description = "시스템 사용자 계정")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    /** 사용자 ID (자동 생성) */
    @Schema(description = "사용자 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 이름 (로그인 ID, 유일) */
    @Schema(description = "사용자 이름 (로그인 ID)")
    @Column(nullable = false, unique = true)
    private String username;

    /** 비밀번호 (BCrypt 해시, 응답 직렬화 제외) */
    @Schema(description = "비밀번호 (BCrypt 해시, 응답 미포함)")
    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 이메일 주소 */
    @Schema(description = "이메일 주소")
    @Column(nullable = false)
    private String email;

    /**
     * 모든 필드를 포함하는 생성자.
     */
    public User(String username, String password, String email) {
        this.username = username;
        this.password = password;
        this.email = email;
    }
}
