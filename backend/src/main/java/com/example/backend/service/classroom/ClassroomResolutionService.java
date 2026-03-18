package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.util.IpRangeUtil;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClassroomResolutionService {

    public enum PcRole {
        TEACHER, STUDENT, UNKNOWN
    }

    private final ClassroomIpRangeRepository rangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;

    public ClassroomResolutionService(ClassroomIpRangeRepository rangeRepository,
                                     ClassroomTeacherDeviceRepository teacherDeviceRepository) {
        this.rangeRepository = rangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
    }

    public Long resolveGroupIdByIp(String ip) {
        if (!IpRangeUtil.isIpv4(ip)) return null;
        List<ClassroomIpRange> all = rangeRepository.findAll();
        for (ClassroomIpRange r : all) {
            if (IpRangeUtil.containsIp(ip, r.getCidr(), r.getStartIp(), r.getEndIp())) {
                return r.getGroupId();
            }
        }
        return null;
    }

    public PcRole resolveRole(Long groupId, String ip, String mac) {
        if (groupId == null) return PcRole.UNKNOWN;
        if (ip != null && teacherDeviceRepository.findFirstByGroupIdAndIpAddress(groupId, ip) != null) {
            return PcRole.TEACHER;
        }
        if (mac != null && teacherDeviceRepository.findFirstByGroupIdAndMacAddress(groupId, mac) != null) {
            return PcRole.TEACHER;
        }
        return PcRole.STUDENT;
    }
}

