package com.example.backend.repository.traffic;

import com.example.backend.model.traffic.TrafficLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrafficLogRepository extends JpaRepository<TrafficLog, Long> {
    List<TrafficLog> findByMacAddressOrderByTimestampDesc(String macAddress);
}
