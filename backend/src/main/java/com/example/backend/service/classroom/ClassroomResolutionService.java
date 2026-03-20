package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.util.IpRangeUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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

    /**
     * IP → 그룹 ID 변환. Caffeine 캐시로 반복적인 full table scan을 방지.
     * 만료 정책은 application.yml(app.cache.ip-range.*)에서 제어한다.
     */
    @Cacheable(value = "ipRangeCache", key = "#ip")
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

    /** 강의실 IP 범위가 변경될 때 캐시를 전체 무효화 */
    @CacheEvict(value = "ipRangeCache", allEntries = true)
    public void evictIpRangeCache() {
        // Spring Cache가 evict 처리
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

