package com.example.backend.model.classroom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 강의실 교사 디바이스 엔티티.
 * 강의실 그룹 내 교사 PC(또는 교사용 태블릿 등)의 IP/MAC 주소를 등록하여
 * 학생 PC 제어 권한 확인 및 교사 세션 감지에 활용된다.
 */
@Schema(description = "강의실 교사 디바이스 (교사 PC/태블릿 등)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "classroom_teacher_devices")
public class ClassroomTeacherDevice {

    /** 디바이스 ID (자동 생성) */
    @Schema(description = "디바이스 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 강의실 그룹 ID */
    @Schema(description = "소속 강의실 그룹 ID")
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** 그룹 이름 (조회 시 임시 매핑, DB 저장 안 됨) */
    @Schema(description = "그룹 이름 (비영속 필드)")
    @Transient
    private String groupName;

    /** 교사 디바이스 IP 주소 */
    @Schema(description = "교사 디바이스 IP 주소")
    @Column(name = "ip_address")
    private String ipAddress;

    /** 교사 디바이스 MAC 주소 */
    @Schema(description = "교사 디바이스 MAC 주소")
    @Column(name = "mac_address")
    private String macAddress;

    /** 설명 */
    @Schema(description = "설명")
    @Column(name = "description")
    private String description;
}
