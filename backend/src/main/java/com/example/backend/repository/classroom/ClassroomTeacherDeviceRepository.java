package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ClassroomTeacherDevice 엔티티 Repository.
 * 강의실 그룹 내 교사 디바이스(교사 PC 등)의 IP/MAC 기반 조회 및 삭제를 제공한다.
 */
@Repository
public interface ClassroomTeacherDeviceRepository extends JpaRepository<ClassroomTeacherDevice, Long> {

    /**
     * 특정 그룹의 교사 디바이스 목록을 조회한다.
     *
     * @param groupId 강의실 그룹 ID
     * @return 교사 디바이스 목록
     */
    List<ClassroomTeacherDevice> findByGroupId(Long groupId);

    /**
     * 특정 그룹에서 IP 주소로 교사 디바이스를 조회한다.
     *
     * @param groupId   강의실 그룹 ID
     * @param ipAddress IP 주소
     * @return 교사 디바이스
     */
    ClassroomTeacherDevice findFirstByGroupIdAndIpAddress(Long groupId, String ipAddress);

    /**
     * 특정 그룹에서 MAC 주소로 교사 디바이스를 조회한다.
     *
     * @param groupId    강의실 그룹 ID
     * @param macAddress MAC 주소
     * @return 교사 디바이스
     */
    ClassroomTeacherDevice findFirstByGroupIdAndMacAddress(Long groupId, String macAddress);

    /**
     * 특정 그룹의 모든 교사 디바이스를 삭제한다.
     *
     * @param groupId 강의실 그룹 ID
     */
    void deleteByGroupId(Long groupId);
}
