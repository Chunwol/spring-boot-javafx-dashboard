package com.example.backend.repository.traffic;

import com.example.backend.model.traffic.TrafficLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    interface DailyTrafficAggregate {
        Integer getYearPart();
        Integer getMonthPart();
        Integer getDayPart();
        Long getInboundKbps();
        Long getOutboundKbps();
    }

    interface HourlyTrafficAggregate {
        Integer getHourPart();
        Long getInboundKbps();
        Long getOutboundKbps();
    }

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

    @Query("""
            select year(t.timestamp) as yearPart,
                   month(t.timestamp) as monthPart,
                   day(t.timestamp) as dayPart,
                   coalesce(sum(t.inboundKbps), 0) as inboundKbps,
                   coalesce(sum(t.outboundKbps), 0) as outboundKbps
              from TrafficLog t
             where t.timestamp between :start and :end
             group by year(t.timestamp), month(t.timestamp), day(t.timestamp)
             order by year(t.timestamp), month(t.timestamp), day(t.timestamp)
            """)
    List<DailyTrafficAggregate> aggregateDailyBetween(@Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    @Query("""
            select year(t.timestamp) as yearPart,
                   month(t.timestamp) as monthPart,
                   day(t.timestamp) as dayPart,
                   coalesce(sum(t.inboundKbps), 0) as inboundKbps,
                   coalesce(sum(t.outboundKbps), 0) as outboundKbps
              from TrafficLog t
             where t.macAddress in :macAddresses
               and t.timestamp between :start and :end
             group by year(t.timestamp), month(t.timestamp), day(t.timestamp)
             order by year(t.timestamp), month(t.timestamp), day(t.timestamp)
            """)
    List<DailyTrafficAggregate> aggregateDailyForMacAddressesBetween(
            @Param("macAddresses") List<String> macAddresses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("""
            select hour(t.timestamp) as hourPart,
                   coalesce(sum(t.inboundKbps), 0) as inboundKbps,
                   coalesce(sum(t.outboundKbps), 0) as outboundKbps
              from TrafficLog t
             where t.timestamp between :start and :end
             group by hour(t.timestamp)
             order by hour(t.timestamp)
            """)
    List<HourlyTrafficAggregate> aggregateHourlyBetween(@Param("start") LocalDateTime start,
                                                          @Param("end") LocalDateTime end);

    @Query("""
            select hour(t.timestamp) as hourPart,
                   coalesce(sum(t.inboundKbps), 0) as inboundKbps,
                   coalesce(sum(t.outboundKbps), 0) as outboundKbps
              from TrafficLog t
             where t.macAddress in :macAddresses
               and t.timestamp between :start and :end
             group by hour(t.timestamp)
             order by hour(t.timestamp)
            """)
    List<HourlyTrafficAggregate> aggregateHourlyForMacAddressesBetween(
            @Param("macAddresses") List<String> macAddresses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query(value = """
            select cast(strftime('%Y', timestamp / 1000, 'unixepoch', 'localtime') as integer) as yearPart,
                   cast(strftime('%m', timestamp / 1000, 'unixepoch', 'localtime') as integer) as monthPart,
                   cast(strftime('%d', timestamp / 1000, 'unixepoch', 'localtime') as integer) as dayPart,
                   coalesce(sum(inbound_kbps), 0) as inboundKbps,
                   coalesce(sum(outbound_kbps), 0) as outboundKbps
              from traffic_logs
             where timestamp between :start and :end
             group by yearPart, monthPart, dayPart
             order by yearPart, monthPart, dayPart
            """, nativeQuery = true)
    List<DailyTrafficAggregate> aggregateDailyBetweenSqlite(@Param("start") LocalDateTime start,
                                                              @Param("end") LocalDateTime end);

    @Query(value = """
            select cast(strftime('%Y', timestamp / 1000, 'unixepoch', 'localtime') as integer) as yearPart,
                   cast(strftime('%m', timestamp / 1000, 'unixepoch', 'localtime') as integer) as monthPart,
                   cast(strftime('%d', timestamp / 1000, 'unixepoch', 'localtime') as integer) as dayPart,
                   coalesce(sum(inbound_kbps), 0) as inboundKbps,
                   coalesce(sum(outbound_kbps), 0) as outboundKbps
              from traffic_logs
             where mac_address in (:macAddresses)
               and timestamp between :start and :end
             group by yearPart, monthPart, dayPart
             order by yearPart, monthPart, dayPart
            """, nativeQuery = true)
    List<DailyTrafficAggregate> aggregateDailyForMacAddressesBetweenSqlite(
            @Param("macAddresses") List<String> macAddresses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query(value = """
            select cast(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') as integer) as hourPart,
                   coalesce(sum(inbound_kbps), 0) as inboundKbps,
                   coalesce(sum(outbound_kbps), 0) as outboundKbps
              from traffic_logs
             where timestamp between :start and :end
             group by hourPart
             order by hourPart
            """, nativeQuery = true)
    List<HourlyTrafficAggregate> aggregateHourlyBetweenSqlite(@Param("start") LocalDateTime start,
                                                                @Param("end") LocalDateTime end);

    @Query(value = """
            select cast(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') as integer) as hourPart,
                   coalesce(sum(inbound_kbps), 0) as inboundKbps,
                   coalesce(sum(outbound_kbps), 0) as outboundKbps
              from traffic_logs
             where mac_address in (:macAddresses)
               and timestamp between :start and :end
             group by hourPart
             order by hourPart
            """, nativeQuery = true)
    List<HourlyTrafficAggregate> aggregateHourlyForMacAddressesBetweenSqlite(
            @Param("macAddresses") List<String> macAddresses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
