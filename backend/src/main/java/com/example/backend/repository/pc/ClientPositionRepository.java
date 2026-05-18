package com.example.backend.repository.pc;

import com.example.backend.model.pc.ClientPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ClientPosition 엔티티 Repository.
 * 대시보드 UI에서 사용하는 PC별 좌표 정보를 그룹, MAC 주소, IP 주소 기준으로 조회한다.
 */
@Repository
public interface ClientPositionRepository extends JpaRepository<ClientPosition, Long> {

    /**
     * 특정 그룹의 모든 PC 위치를 조회한다.
     *
     * @param groupId 강의실 그룹 ID
     * @return PC 위치 목록
     */
    List<ClientPosition> findByGroupId(Long groupId);

    /**
     * MAC 주소로 PC 위치를 조회한다.
     *
     * @param macAddress MAC 주소
     * @return PC 위치
     */
    ClientPosition findByMacAddress(String macAddress);

    /**
     * IP 주소로 PC 위치를 조회한다.
     *
     * @param ipAddress IP 주소
     * @return PC 위치
     */
    ClientPosition findByIpAddress(String ipAddress);
}
