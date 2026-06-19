package com.example.backend.service.admin;

import com.example.backend.model.agent.AgentAuthToken;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.agent.AgentAuthTokenRepository;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final PcMasterRepository pcMasterRepository;
    private final AgentAuthTokenRepository agentAuthTokenRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final AuditService auditService;

    public List<Map<String, Object>> findAll() {
        List<PcMaster> pcs = pcMasterRepository.findAll();
        Map<String, PcMaster> pcByMac = new HashMap<>();
        for (PcMaster pc : pcs) {
            if (pc.getMacAddress() != null) pcByMac.put(pc.getMacAddress(), pc);
        }

        // groupId → groupName 매핑 (쿼리 1회)
        Map<Long, String> groupNames = new HashMap<>();
        classroomGroupRepository.findAll().forEach(g -> groupNames.put(g.getId(), g.getName()));

        List<AgentAuthToken> tokens = agentAuthTokenRepository.findAll();
        Map<String, AgentAuthToken> tokenByMac = new LinkedHashMap<>();
        for (AgentAuthToken token : tokens) {
            if (token.getMacAddress() == null) continue;
            AgentAuthToken existing = tokenByMac.get(token.getMacAddress());
            if (existing == null || isPreferredToken(token, existing)) {
                tokenByMac.put(token.getMacAddress(), token);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (PcMaster pc : pcs) {
            AgentAuthToken tok = tokenByMac.get(pc.getMacAddress());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("macAddress", pc.getMacAddress());
            row.put("pcName", pc.getPcName());
            row.put("ipAddress", pc.getIpAddress());
            row.put("status", pc.getStatus());
            row.put("role", pc.getRole());
            row.put("groupId", pc.getGroupId());
            row.put("groupName", pc.getGroupId() != null ? groupNames.getOrDefault(pc.getGroupId(), "-") : "-");
            row.put("lastSeen", pc.getLastSeen());
            row.put("tokenActive", tok != null && !tok.isRevoked());
            row.put("tokenIssuedAt", tok != null ? tok.getIssuedAt() : null);
            row.put("tokenLastUsedAt", tok != null ? tok.getLastUsedAt() : null);
            row.put("orphan", false);
            result.add(row);
        }

        // token만 존재하고 pc_master에는 없는 고아 레코드도 노출
        for (Map.Entry<String, AgentAuthToken> e : tokenByMac.entrySet()) {
            String mac = e.getKey();
            if (pcByMac.containsKey(mac)) continue;
            AgentAuthToken tok = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("macAddress", mac);
            row.put("pcName", "(미등록 PC)");
            row.put("ipAddress", "-");
            row.put("status", "UNREGISTERED");
            row.put("role", "-");
            row.put("groupId", null);
            row.put("groupName", "-");
            row.put("lastSeen", null);
            row.put("tokenActive", tok != null && !tok.isRevoked());
            row.put("tokenIssuedAt", tok != null ? tok.getIssuedAt() : null);
            row.put("tokenLastUsedAt", tok != null ? tok.getLastUsedAt() : null);
            row.put("orphan", true);
            result.add(row);
        }
        return result;
    }

    @Transactional
    public void delete(String mac) {
        agentAuthTokenRepository.findAllByMacAddress(mac).forEach(agentAuthTokenRepository::delete);
        pcMasterRepository.findById(mac).ifPresent(pcMasterRepository::delete);
        auditService.log("ADMIN", "DELETE_AGENT", "AGENT", mac);
    }

    private boolean isPreferredToken(AgentAuthToken candidate, AgentAuthToken existing) {
        if (candidate == null) return false;
        if (existing == null) return true;
        if (candidate.getLastUsedAt() != null && existing.getLastUsedAt() != null) {
            if (candidate.getLastUsedAt().isAfter(existing.getLastUsedAt())) return true;
            if (candidate.getLastUsedAt().isBefore(existing.getLastUsedAt())) return false;
        } else if (candidate.getLastUsedAt() != null) {
            return true;
        } else if (existing.getLastUsedAt() != null) {
            return false;
        }
        if (candidate.getIssuedAt() != null && existing.getIssuedAt() != null) {
            return candidate.getIssuedAt().isAfter(existing.getIssuedAt());
        }
        return candidate.getIssuedAt() != null && existing.getIssuedAt() == null;
    }
}
