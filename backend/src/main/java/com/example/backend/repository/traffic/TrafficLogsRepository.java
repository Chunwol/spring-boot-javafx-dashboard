package com.example.backend.repository.traffic;

import com.example.backend.model.traffic.TrafficLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrafficLogsRepository extends JpaRepository<TrafficLog, Long> {
    List<TrafficLog> findByTimestampBetween(LocalDateTime startDate, LocalDateTime endDate);

    Optional<TrafficLog> findByTimestamp(LocalDateTime timestamp);

    List<TrafficLog> findByMacAddressAndTimestampBetween(String macAddress, LocalDateTime start, LocalDateTime end);

    List<TrafficLog> findByMacAddressInAndTimestampBetween(List<String> macAddresses, LocalDateTime start, LocalDateTime end);
}