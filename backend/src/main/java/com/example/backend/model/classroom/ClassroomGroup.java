package com.example.backend.model.classroom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 강의실 그룹 엔티티.
 * 하나의 강의실(또는 논리적 PC 그룹)을 나타내며,
 * 이름과 설명으로 구분되고 IP 범위 및 교사 디바이스와 연관된다.
 */
@Schema(description = "강의실 그룹 (PC 집합 단위)")
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "classroom_groups")
public class ClassroomGroup {

    /** 그룹 ID (자동 생성) */
    @Schema(description = "그룹 ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 강의실 그룹 이름 (유일) */
    @Schema(description = "강의실 그룹 이름 (유일)")
    @Column(nullable = false, unique = true)
    private String name;

    /** 그룹 설명 */
    @Schema(description = "그룹 설명")
    @Column
    private String description;

    /** 생성 일시 (문자열 ISO 형식) */
    @Schema(description = "생성 일시")
    @Column(name = "created_at")
    private String createdAt;
}
