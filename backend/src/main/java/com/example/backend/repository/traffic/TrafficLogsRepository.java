package com.example.backend.repository.traffic;

import com.example.backend.model.traffic.TrafficLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * TrafficLog 엔티티 주 Repository.
 * 네트워크 트래픽 로그의 페이징 조회, 기간 필터링, 특정 에이전트 집계 등
 * 다양한 트래픽 분석 쿼리를 제공한다.
 *
 * <p>{@link TrafficLogRepository}는 메서드 수가 적은 구버전으로, 이 Repository로 대체 예정입니다.</p>
 */
@Repository
public interface TrafficLogsRepository extends JpaRepository<TrafficLog, Long> {

    /**
     * 지정 기간 내의 트래픽 로그를 조회한다.
     *
     * @param startDate 시작 일시 (포함)
     * @param endDate   종료 일시 (포함)
     * @return 트래픽 로그 목록
     */
    List<TrafficLog> findByTimestampBetween(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 전체 트래픽 로그를 최신순으로 페이징 조회한다.
     *
     * @param pageable 페이지 정보
     * @return 페이징된 트래픽 로그
     */
    Page<TrafficLog> findAllByOrderByTimestampDesc(Pageable pageable);

    /**
     * 특정 타임스탬프의 트래픽 로그를 조회한다.
     *
     * @param timestamp 조회할 타임스탬프
     * @return 트래픽 로그 (없으면 empty)
     */
    Optional<TrafficLog> findByTimestamp(LocalDateTime timestamp);

    /**
     * 특정 에이전트의 지정 기간 트래픽 로그를 조회한다.
     *
     * @param macAddress 에이전트 MAC 주소
     * @param start      시작 일시
     * @param end        종료 일시
     * @return 트래픽 로그 목록
     */
    List<TrafficLog> findByMacAddressAndTimestampBetween(String macAddress, LocalDateTime start, LocalDateTime end);

    /**
     * 여러 에이전트의 지정 기간 트래픽 로그를 조회한다.
     *
     * @param macAddresses 에이전트 MAC 주소 목록
     * @param start        시작 일시
     * @param end          종료 일시
     * @return 트래픽 로그 목록
     */
    List<TrafficLog> findByMacAddressInAndTimestampBetween(List<String> macAddresses, LocalDateTime start, LocalDateTime end);
}
