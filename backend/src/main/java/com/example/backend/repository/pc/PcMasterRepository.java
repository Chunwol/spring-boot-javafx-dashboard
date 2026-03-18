package com.example.backend.repository.pc;

import com.example.backend.model.pc.PcMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PcMasterRepository extends JpaRepository<PcMaster, String> {
    Optional<PcMaster> findByIpAddress(String ipAddress);
    java.util.List<PcMaster> findByGroupIdAndStatus(Long groupId, String status);
    java.util.List<PcMaster> findByGroupId(Long groupId);
    java.util.List<PcMaster> findByGroupIdAndRole(Long groupId, String role);
}
