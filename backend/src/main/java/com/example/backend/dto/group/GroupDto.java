package com.example.backend.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 강의실 그룹 응답 DTO.
 *
 * <p>강의실 그룹 목록 조회 API의 응답에 사용된다.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "강의실 그룹 응답 DTO")
public class GroupDto {

    @Schema(description = "그룹 고유 ID", example = "1")
    private Long id;

    @Schema(description = "그룹 이름", example = "1강의실")
    private String name;

    @Schema(description = "그룹 설명", example = "본관 3층 컴퓨터실")
    private String description;
}
