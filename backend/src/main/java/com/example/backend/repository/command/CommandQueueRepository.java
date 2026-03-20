package com.example.backend.repository.command;

import com.example.backend.model.command.CommandQueue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommandQueueRepository extends JpaRepository<CommandQueue, Long> {

    List<CommandQueue> findByMacAddressAndIsExecutedFalse(String macAddress);

    CommandQueue findFirstByMacAddressAndStatusOrderByTaskIdAsc(String macAddress, String status);

    /** 진행 중인 명령 (QUEUED + DELIVERED + ACKED) 최근 100건 */
    @Query("SELECT c FROM CommandQueue c WHERE c.status IN ('QUEUED','DELIVERED','ACKED') " +
           "ORDER BY c.createdAt DESC")
    List<CommandQueue> findActiveCommands();

    /** 상태 목록 기반 명령 조회 (CommandController에서 사용) */
    List<CommandQueue> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

       Page<CommandQueue> findByStatusInOrderByCreatedAtDesc(List<String> statuses, Pageable pageable);

    /** 날짜 범위 내 전체 명령 조회 (ReportService에서 사용) */
    List<CommandQueue> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    /** 날짜 범위 + taskId 조회 */
    List<CommandQueue> findByCreatedAtBetweenAndStatusOrderByCreatedAtDesc(
            LocalDateTime from, LocalDateTime to, String status);

           /** 전달 후 ACK 없이 타임아웃된 명령 조회 */
           List<CommandQueue> findByStatusAndDeliveredAtBefore(String status, LocalDateTime threshold);

           List<CommandQueue> findTop200ByStatusAndDeliveredAtBeforeOrderByDeliveredAtAsc(
                  String status, LocalDateTime threshold);

           /** ACK 후 결과 없이 타임아웃된 명령 조회 */
           List<CommandQueue> findByStatusAndAckedAtBefore(String status, LocalDateTime threshold);

           List<CommandQueue> findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                  String status, LocalDateTime threshold);

           long countByStatus(String status);

           long countByStatusIn(List<String> statuses);

           long countByStatusAndDeliveredAtBefore(String status, LocalDateTime threshold);

           long countByStatusAndAckedAtBefore(String status, LocalDateTime threshold);
}
