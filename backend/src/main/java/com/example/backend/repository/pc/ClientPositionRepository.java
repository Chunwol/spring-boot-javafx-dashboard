package com.example.backend.repository.pc;

import com.example.backend.model.pc.ClientPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClientPositionRepository extends JpaRepository<ClientPosition, Long> {
    List<ClientPosition> findByGroupId(Long groupId);
    ClientPosition findByMacAddress(String macAddress);
    ClientPosition findByIpAddress(String ipAddress);
}
