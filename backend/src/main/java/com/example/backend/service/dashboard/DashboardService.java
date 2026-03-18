package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.ClassroomSummaryDto;
import com.example.backend.dto.dashboard.NetworkTrafficDto;
import com.example.backend.dto.dashboard.PcStatusDto;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;

    public DashboardService(ClassroomGroupRepository classroomGroupRepository,
                           PcMasterRepository pcMasterRepository,
                           ClassroomIpRangeRepository ipRangeRepository,
                           ClassroomTeacherDeviceRepository teacherDeviceRepository) {
        this.classroomGroupRepository = classroomGroupRepository;
        this.pcMasterRepository = pcMasterRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
    }

    public List<ClassroomSummaryDto> getClassroomSummaries() {
        try {
            List<ClassroomGroup> groups = classroomGroupRepository.findAll();
            List<ClassroomSummaryDto> summaries = new ArrayList<>();

            for (ClassroomGroup group : groups) {
                List<PcMaster> pcs = pcMasterRepository.findByGroupId(group.getId());
                long onlineCount = pcs.stream()
                    .filter(pc -> pc.getLastSeen() != null &&
                            pc.getLastSeen().isAfter(LocalDateTime.now().minusMinutes(5)))
                    .count();

                List<String> ipRanges = ipRangeRepository.findByGroupId(group.getId()).stream()
                        .map(r -> {
                            if (r.getCidr() != null && !r.getCidr().isBlank()) return r.getCidr();
                            if (r.getStartIp() != null && r.getEndIp() != null) return r.getStartIp() + "~" + r.getEndIp();
                            return "";
                        })
                        .filter(s -> s != null && !s.isBlank())
                        .toList();

                String teacherStatus = "OFFLINE";
                List<ClassroomTeacherDevice> teachers = teacherDeviceRepository.findByGroupId(group.getId());
                if (!teachers.isEmpty()) {
                    boolean anyOnlineTeacher = teachers.stream().anyMatch(t -> {
                        List<PcMaster> candidates = pcs.stream().filter(pc -> {
                            boolean macMatch = t.getMacAddress() != null && pc.getMacAddress() != null
                                    && t.getMacAddress().equalsIgnoreCase(pc.getMacAddress());
                            boolean ipMatch = t.getIpAddress() != null && pc.getIpAddress() != null
                                    && t.getIpAddress().equals(pc.getIpAddress());
                            return macMatch || ipMatch;
                        }).toList();
                        return candidates.stream().anyMatch(pc -> pc.getLastSeen() != null
                                && pc.getLastSeen().isAfter(LocalDateTime.now().minusMinutes(5)));
                    });
                    teacherStatus = anyOnlineTeacher ? "ONLINE" : "OFFLINE";
                }

                ClassroomSummaryDto summary = new ClassroomSummaryDto();
                summary.setGroupId(group.getId());
                summary.setGroupName(group.getName());
                summary.setOnlinePcs((int) onlineCount);
                summary.setTotalPcs(pcs.size());
                summary.setOnlinePcCount((int) onlineCount);
                summary.setTotalPcCount(pcs.size());
                summary.setIpRanges(ipRanges);
                summary.setTeacherPcStatus(teacherStatus);
                summary.setVisible(true);
                summaries.add(summary);
            }

            return summaries;
        } catch (Exception e) {
            logger.error("Failed to fetch classroom summaries", e);
            return new ArrayList<>();
        }
    }

    public List<PcStatusDto> getPcStatuses(Long groupId) {
        List<PcMaster> pcs = pcMasterRepository.findByGroupId(groupId);
        return pcs.stream().map(pc -> {
            PcStatusDto dto = new PcStatusDto();
            dto.setMacAddress(pc.getMacAddress());
            dto.setPcName(pc.getPcName());
            dto.setIpAddress(pc.getIpAddress());
            dto.setGroupId(pc.getGroupId());
            dto.setStatus(pc.getStatus());
            dto.setRole(pc.getRole());
            dto.setLastSeen(pc.getLastSeen());
            return dto;
        }).collect(Collectors.toList());
    }

    public NetworkTrafficDto getNetworkTraffic() {
        // TODO: 실제 트래픽 집계 로직 구현 (현재는 더미 값 반환)
        return new NetworkTrafficDto(0.0, 0.0);
    }

    public List<ClassroomIpRange> getClassroomIpRanges(Long groupId) {
        return ipRangeRepository.findByGroupId(groupId);
    }

    public ClassroomIpRange saveClassroomIpRange(ClassroomIpRange range) {
        return ipRangeRepository.save(range);
    }

    public void deleteClassroomIpRange(Long id) {
        ipRangeRepository.deleteById(id);
    }

    public List<ClassroomTeacherDevice> getTeacherDevices(Long groupId) {
        return teacherDeviceRepository.findByGroupId(groupId);
    }

    public ClassroomTeacherDevice saveTeacherDevice(ClassroomTeacherDevice device) {
        return teacherDeviceRepository.save(device);
    }

    public void deleteTeacherDevice(Long id) {
        teacherDeviceRepository.deleteById(id);
    }

    public PcStatusDto markPcAsBroken(String macAddress, String note) {
        PcMaster pc = pcMasterRepository.findById(macAddress).orElseThrow(() ->
                new IllegalArgumentException("PC가 존재하지 않습니다: " + macAddress));
        pc.setStatus("BROKEN");
        pcMasterRepository.save(pc);

        PcStatusDto dto = new PcStatusDto();
        dto.setMacAddress(pc.getMacAddress());
        dto.setPcName(pc.getPcName());
        dto.setIpAddress(pc.getIpAddress());
        dto.setGroupId(pc.getGroupId());
        dto.setStatus(pc.getStatus());
        dto.setRole(pc.getRole());
        dto.setLastSeen(pc.getLastSeen());
        return dto;
    }
}
