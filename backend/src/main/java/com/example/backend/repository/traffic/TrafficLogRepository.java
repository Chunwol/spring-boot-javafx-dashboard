package com.example.backend.repository.traffic;

import com.example.backend.model.traffic.TrafficLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * TrafficLog 엔티티 Repository.
 * 에이전트별 네트워크 트래픽 로그를 조회한다.
 *
 * <p><b>주의:</b> 이 Repository는 {@link TrafficLogsRepository}와 중복됩니다.
 * 더 많은 기능을 제공하는 {@code TrafficLogsRepository} 사용을 권장합니다.
 * 이 인터페이스는 향후 제거될 수 있습니다.</p>
 *
 * @deprecated {@link TrafficLogsRepository} 사용 권장
 */
@Deprecated
@Repository
public interface TrafficLogRepository extends JpaRepository<TrafficLog, Long> {

    /**
     * 특정 에이전트의 트래픽 로그를 최신순으로 조회한다.
     *
     * @param macAddress 에이전트 MAC 주소
     * @return 트래픽 로그 목록 (최신순)
     */
    List<TrafficLog> findByMacAddressOrderByTimestampDesc(String macAddress);
}
