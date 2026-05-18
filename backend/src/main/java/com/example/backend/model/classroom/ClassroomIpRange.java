package com.example.backend.model.classroom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 강의실 IP 범위 엔티티.
 * 특정 강의실 그룹에 속하는 IP 대역을 CIDR 또는 시작·끝 IP 쌍으로 정의한다.
 * IP 범위를 기반으로 PC를 강의실에 자동 매핑하는 데 사용된다.
 */
@Schema(description = "강의실 IP 범위 (CIDR 또는 시작/끝 IP)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "classroom_ip_ranges")
public class ClassroomIpRange {

    /** IP 범위 ID (자동 생성) */
    @Schema(description = "IP 범위 ID")
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

    /** CIDR 표기 IP 범위 (예: 192.168.1.0/24) */
    @Schema(description = "CIDR 표기 (예: 192.168.1.0/24)")
    @Column(name = "cidr")
    private String cidr;

    /** 시작 IP 주소 */
    @Schema(description = "시작 IP 주소")
    @Column(name = "start_ip")
    private String startIp;

    /** 끝 IP 주소 */
    @Schema(description = "끝 IP 주소")
    @Column(name = "end_ip")
    private String endIp;

    /** 설명 */
    @Schema(description = "설명")
    @Column(name = "description")
    private String description;
}
