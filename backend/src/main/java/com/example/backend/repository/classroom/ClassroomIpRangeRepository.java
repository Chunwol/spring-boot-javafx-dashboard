package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ClassroomIpRange 엔티티 Repository.
 * 강의실 그룹에 속한 IP 범위의 조회 및 삭제를 제공한다.
 * IP 기반 PC 자동 그룹 매핑 시 활용된다.
 */
@Repository
public interface ClassroomIpRangeRepository extends JpaRepository<ClassroomIpRange, Long> {

    /**
     * 특정 그룹의 IP 범위 목록을 조회한다.
     *
     * @param groupId 강의실 그룹 ID
     * @return IP 범위 목록
     */
    List<ClassroomIpRange> findByGroupId(Long groupId);

    /**
     * 특정 그룹의 모든 IP 범위를 삭제한다 (그룹 재구성 시 사용).
     *
     * @param groupId 강의실 그룹 ID
     */
    void deleteByGroupId(Long groupId);
}
