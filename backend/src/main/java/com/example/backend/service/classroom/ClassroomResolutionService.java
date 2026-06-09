package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.util.DeviceAddressUtil;
import com.example.backend.util.IpRangeUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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
    private final TeacherDeviceLookupService teacherDeviceLookupService;

    public ClassroomResolutionService(ClassroomIpRangeRepository rangeRepository,
                                      TeacherDeviceLookupService teacherDeviceLookupService) {
        this.rangeRepository = rangeRepository;
        this.teacherDeviceLookupService = teacherDeviceLookupService;
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
        return resolveGroupId(ip, null);
    }

    /**
     * IP 또는 MAC 주소를 기준으로 강의실 그룹 ID를 변환한다.
     * 교사 PC 등록 정보가 있으면 IP/MAC 둘 중 하나만 일치해도 해당 그룹을 우선 반환한다.
     *
     * @param ip  IPv4 주소 문자열
     * @param mac MAC 주소 문자열
     * @return 매핑된 그룹 ID, 없으면 null
     */
    @Cacheable(value = "ipRangeCache",
            key = "T(java.lang.String).valueOf(#ip).concat('|').concat(T(java.lang.String).valueOf(#mac))",
            unless = "#result == null")
    @Transactional(readOnly = true)
    public Long resolveGroupId(String ip, String mac) {
        TeacherDeviceLookupService.TeacherDeviceMatch teacherMatch =
                teacherDeviceLookupService.findMatch(ip, mac);
        if (teacherMatch != null && teacherMatch.groupId() != null) {
            return teacherMatch.groupId();
        }
        String normalizedIp = DeviceAddressUtil.normalizeIp(ip);
        if (!IpRangeUtil.isIpv4(normalizedIp)) return null;
        List<ClassroomIpRange> all = rangeRepository.findAll();
        for (ClassroomIpRange r : all) {
            if (IpRangeUtil.containsIp(normalizedIp, r.getCidr(), r.getStartIp(), r.getEndIp())) {
                return r.getGroupId();
            }
        }
        return null;
    }

    /**
     * 강의실 IP 범위가 변경될 때 캐시를 전체 무효화한다.
     * IP 범위 추가/수정/삭제 이후 반드시 호출해야 한다.
     */
    @Caching(evict = {
            @CacheEvict(value = "ipRangeCache", allEntries = true),
            @CacheEvict(value = "pcRoleCache", allEntries = true)
    })
    public void evictIpRangeCache() {
        teacherDeviceLookupService.evictCache();
    }

    /**
     * 그룹 ID, IP, MAC을 기반으로 PC 역할을 판별한다.
     *
     * @param groupId 그룹 ID (null이면 UNKNOWN 반환)
     * @param ip      PC의 IP 주소
     * @param mac     PC의 MAC 주소
     * @return TEACHER, STUDENT, UNKNOWN 중 하나
     */
    @Cacheable(
            value = "pcRoleCache",
            key = "T(java.lang.String).valueOf(#groupId).concat('|').concat(T(java.lang.String).valueOf(#ip)).concat('|').concat(T(java.lang.String).valueOf(#mac))",
            sync = true)
    @Transactional(readOnly = true)
    public PcRole resolveRole(Long groupId, String ip, String mac) {
        if (teacherDeviceLookupService.findMatch(ip, mac) != null) {
            return PcRole.TEACHER;
        }
        if (groupId == null) return PcRole.UNKNOWN;
        return PcRole.STUDENT;
    }
}
