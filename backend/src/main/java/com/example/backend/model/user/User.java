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

import java.time.LocalDateTime;

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

    /** 애플리케이션 역할 (ADMIN / USER / VIEWER) */
    @Schema(description = "애플리케이션 역할")
    @Column(name = "role")
    private String role;

    /** 계정 사용처와 실제 사용자 이름 등을 적는 관리자 비고. */
    @Column(name = "note", length = 500)
    private String note;

    /** 운영자에게 명시적으로 부여한 화면 단위 기능 권한(CSV). null은 역할 기본값을 뜻한다. */
    @Column(name = "feature_permissions", length = 500)
    private String featurePermissions;

    /** 연속 로그인 실패 누적 횟수. 성공 로그인 시 0으로 초기화한다. */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    /** 5회 실패 후 적용되는 임시 로그인 제한 종료 시각. */
    @Column(name = "login_locked_until")
    private LocalDateTime loginLockedUntil;

    /** 10회 실패 시 false로 전환되는 계정 활성 상태. */
    @Column(nullable = false)
    private boolean active = true;

    /** 현재 유효한 단일 로그인 세션 ID. 새 로그인 시 교체된다. */
    @Column(name = "active_session_id", length = 36)
    private String activeSessionId;

    /** 현재 로그인 세션을 시작한 클라이언트 IP. */
    @Column(name = "login_ip_address", length = 64)
    private String loginIpAddress;

    /** 현재 로그인 세션의 브라우저 User-Agent. */
    @Column(name = "login_user_agent", length = 500)
    private String loginUserAgent;

    @Column(name = "login_at")
    private LocalDateTime loginAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /**
     * 신규 사용자 생성자.
     */
    public User(String username, String password, String email) {
        this(username, password, email, "USER");
    }

    public User(String username, String password, String email, String role) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
    }

    // ── 도메인 메서드 ──────────────────────────────────────────────────────────

    /** 비밀번호를 변경한다. (이미 인코딩된 값을 전달해야 한다) */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** 이메일을 변경한다. */
    public void changeEmail(String email) {
        this.email = email;
    }

    /** 역할을 변경한다. */
    public void changeRole(String role) {
        this.role = role;
    }

    public void changeNote(String note) {
        this.note = note;
    }

    public void changeFeaturePermissions(String featurePermissions) {
        this.featurePermissions = featurePermissions;
    }

    /** 사용자명을 변경한다. */
    public void changeUsername(String username) {
        this.username = username;
    }

    public boolean isLoginLockedAt(LocalDateTime now) {
        return loginLockedUntil != null && now.isBefore(loginLockedUntil);
    }

    public int recordFailedLogin() {
        return ++failedLoginAttempts;
    }

    public void lockLoginUntil(LocalDateTime until) {
        this.loginLockedUntil = until;
    }

    public void resetLoginFailures() {
        this.failedLoginAttempts = 0;
        this.loginLockedUntil = null;
    }

    public void deactivate() {
        this.active = false;
        this.loginLockedUntil = null;
        clearLoginSession();
    }

    public void startLoginSession(String sessionId, String ipAddress, String userAgent, LocalDateTime now) {
        this.activeSessionId = sessionId;
        this.loginIpAddress = ipAddress;
        this.loginUserAgent = userAgent;
        this.loginAt = now;
        this.lastActivityAt = now;
    }

    public boolean hasLoginSession(String sessionId) {
        return activeSessionId != null && activeSessionId.equals(sessionId);
    }

    public void touchLoginSession(String sessionId, LocalDateTime now) {
        if (hasLoginSession(sessionId)) {
            this.lastActivityAt = now;
        }
    }

    public void clearLoginSession() {
        this.activeSessionId = null;
        this.loginIpAddress = null;
        this.loginUserAgent = null;
        this.loginAt = null;
        this.lastActivityAt = null;
    }
}
