package com.example.backend.repository.command;

import com.example.backend.model.command.CommandQueue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommandQueueRepository extends JpaRepository<CommandQueue, Long> {

    List<CommandQueue> findByMacAddressAndStatusInOrderByCreatedAtAsc(String macAddress, List<String> statuses);

    List<CommandQueue> findTop50ByMacAddressAndCommandTypeOrderByCreatedAtDesc(String macAddress, String commandType);

    List<CommandQueue> findTop50ByMacAddressAndCommandTypeAndStatusInOrderByCreatedAtDesc(
            String macAddress, String commandType, List<String> statuses);

    CommandQueue findFirstByMacAddressAndStatusOrderByTaskIdAsc(String macAddress, String status);

    @Query("SELECT c FROM CommandQueue c " +
           "WHERE c.macAddress = :macAddress AND c.status = 'QUEUED' " +
           "ORDER BY CASE WHEN c.commandType = 'LIST_FILES' THEN 0 ELSE 1 END, c.taskId ASC")
    List<CommandQueue> findNextDispatchCandidates(@Param("macAddress") String macAddress, Pageable pageable);

    /** 진행 중인 명령 (QUEUED + DELIVERED + ACKED) 최근 100건 */
    @Query("SELECT c FROM CommandQueue c WHERE c.status IN ('QUEUED','DELIVERED','ACKED') " +
           "ORDER BY c.createdAt DESC")
    List<CommandQueue> findActiveCommands();

    @Query("""
           SELECT p.groupId AS groupId, COUNT(c.taskId) AS activeCount
           FROM CommandQueue c
           JOIN PcMaster p ON LOWER(p.macAddress) = LOWER(c.macAddress)
           WHERE c.status IN ('QUEUED','DELIVERED','ACKED')
             AND p.groupId IN :groupIds
           GROUP BY p.groupId
           """)
    List<GroupActiveCommandCountProjection> countActiveCommandsByGroupIds(@Param("groupIds") List<Long> groupIds);

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

           /** QUEUED 상태에서 createdAt 기준 타임아웃된 명령 조회 */
           List<CommandQueue> findTop200ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                  String status, LocalDateTime threshold);

           /** ACK 후 결과 없이 타임아웃된 명령 조회 */
           List<CommandQueue> findByStatusAndAckedAtBefore(String status, LocalDateTime threshold);

           List<CommandQueue> findTop200ByStatusAndAckedAtBeforeOrderByAckedAtAsc(
                  String status, LocalDateTime threshold);

           long countByStatus(String status);

           long countByStatusIn(List<String> statuses);

           long countByStatusAndDeliveredAtBefore(String status, LocalDateTime threshold);

           long countByStatusAndAckedAtBefore(String status, LocalDateTime threshold);

           /** SCMATE 초기화 감시: ACK 받은 관리 작업 명령 조회 */
           List<CommandQueue> findByStatusAndIsAdminOperationAndAckedAtAfter(
                   String status, Boolean isAdminOperation, LocalDateTime ackedAtThreshold);

           /** SCMATE 초기화 복구 상태로 명령 조회 */
           List<CommandQueue> findByMacAddressAndScmateResetStatus(String macAddress, String scmateResetStatus);

           List<CommandQueue> findTop200ByVerificationStatusInOrderByVerificationDeadlineAtAsc(List<String> verificationStatuses);

           @Modifying(flushAutomatically = true, clearAutomatically = true)
           @Query("UPDATE CommandQueue c " +
                   "SET c.status = 'ACKED', c.ackedAt = :ackedAt " +
                   "WHERE c.taskId = :taskId AND c.status = 'DELIVERED'")
           int markAckedIfDelivered(@Param("taskId") Long taskId, @Param("ackedAt") LocalDateTime ackedAt);

           /**
            * QUEUED 상태인 경우에만 DELIVERED로 전환 (CAS).
            * 반환값 1 = 성공(우리가 먼저 가져감), 0 = 실패(다른 스레드가 이미 가져감).
            */
           @Modifying(flushAutomatically = true, clearAutomatically = true)
           @Query("UPDATE CommandQueue c " +
                   "SET c.status = 'DELIVERED', c.deliveredAt = :deliveredAt, c.isExecuted = false " +
                   "WHERE c.taskId = :taskId AND c.status = 'QUEUED'")
           int markDeliveredIfQueued(@Param("taskId") Long taskId, @Param("deliveredAt") LocalDateTime deliveredAt);

           @Modifying(flushAutomatically = true, clearAutomatically = true)
           @Query("UPDATE CommandQueue c " +
                   "SET c.status = :newStatus, c.exitCode = :exitCode, c.stdout = :stdout, c.stderr = :stderr, " +
                   "    c.completedAt = :completedAt, c.isExecuted = true " +
                   "WHERE c.taskId = :taskId AND c.status IN ('DELIVERED', 'ACKED')")
           int markResultIfInProgress(@Param("taskId") Long taskId,
                                      @Param("newStatus") String newStatus,
                                      @Param("exitCode") Integer exitCode,
                                      @Param("stdout") String stdout,
                                      @Param("stderr") String stderr,
                                      @Param("completedAt") LocalDateTime completedAt);

           interface GroupActiveCommandCountProjection {
               Long getGroupId();
               Long getActiveCount();
           }
}
