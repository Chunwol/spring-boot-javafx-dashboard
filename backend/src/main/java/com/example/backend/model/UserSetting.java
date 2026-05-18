package com.example.backend.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사용자 환경 설정 엔티티.
 * 키-값 쌍으로 시스템 전반의 설정값을 저장한다 (예: 테마, 언어, 알림 여부 등).
 */
@Schema(description = "사용자 환경 설정 (키-값 쌍)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_settings")
public class UserSetting {

    /** 설정 키 (PK, 예: "theme", "language") */
    @Schema(description = "설정 키 (PK)")
    @Id
    @Column(nullable = false)
    private String key;

    /** 설정 값 */
    @Schema(description = "설정 값")
    @Column(nullable = false)
    private String value = "";

    public UserSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
