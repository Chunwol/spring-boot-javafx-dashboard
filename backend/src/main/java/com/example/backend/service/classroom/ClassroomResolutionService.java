package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.util.IpRangeUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 강의실 IP 범위 기반 그룹 해석 및 PC 역할(TEACHER/STUDENT/UNKNOWN) 판별 서비스.
 * <p>
 * IP → 그룹 ID 변환 결과는 Spring Cache(Caffeine/Redis)에 캐싱한다.
 * 만료 정책은 application.yml {@code app.cache.ip-range.*}에서 제어한다.
 * </p>
 */
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
     * IP 주소를 강의실 그룹 ID로 변환한다.
     * <p>
     * Spring Cache(환경별 Redis/Caffeine)로 반복적인 full table scan을 방지한다.
     * 만료 정책은 application.yml({@code app.cache.ip-range.*})에서 제어한다.
     * </p>
     * <p>
     * <b>WHY unless="#result == null":</b> 매핑되지 않는 IP에 대해 null을 캐싱하면
     * 새 강의실이 등록돼도 캐시가 무효화될 때까지 계속 null을 반환하는 문제가 생긴다.
     * null 결과는 캐싱하지 않아 항상 최신 DB를 조회하도록 한다.
     * </p>
     *
     * @param ip IPv4 주소 문자열
     * @return 매핑된 그룹 ID, 없으면 null (캐싱 제외)
     */
    @Cacheable(value = "ipRangeCache", key = "#ip", unless = "#result == null")
    @Transactional(readOnly = true)
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

    /**
     * 강의실 IP 범위가 변경될 때 캐시를 전체 무효화한다.
     * IP 범위 추가/수정/삭제 이후 반드시 호출해야 한다.
     */
    @CacheEvict(value = "ipRangeCache", allEntries = true)
    public void evictIpRangeCache() {
        // Spring Cache가 evict 처리
    }

    /**
     * 그룹 ID, IP, MAC을 기반으로 PC 역할을 판별한다.
     *
     * @param groupId 그룹 ID (null이면 UNKNOWN 반환)
     * @param ip      PC의 IP 주소
     * @param mac     PC의 MAC 주소
     * @return TEACHER, STUDENT, UNKNOWN 중 하나
     */
    @Transactional(readOnly = true)
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

