package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * IP 주소 기반 강의실 자동 그룹핑 서비스.
 * <p>
 * DB에 저장된 IP 범위(startIp~endIp)와 비교하여 PC가 속한 강의실 그룹 ID를 반환한다.
 * CIDR 기반 매칭이 필요한 경우 {@link ClassroomResolutionService}를 사용한다.
 * </p>
 *
 * @see ClassroomResolutionService CIDR + startIp/endIp 혼합 방식을 지원하며 캐싱 적용
 */
@Service
public class AutoGroupingService {

    @Autowired
    private ClassroomIpRangeRepository ipRangeRepository;

    /**
     * IP 주소를 기반으로 적절한 강의실 Group ID를 반환한다.
     * startIp~endIp 범위 순차 비교로 매칭하며, 매칭되는 대역이 없으면 null을 반환한다.
     *
     * @param ipAddress 조회할 IPv4 주소
     * @return 매핑된 그룹 ID, 없으면 null
     */
    @Transactional(readOnly = true)
    public Long resolveGroupId(String ipAddress) {
        List<ClassroomIpRange> ranges = ipRangeRepository.findAll();

        for (ClassroomIpRange range : ranges) {
            if (isIpInRange(ipAddress, range.getStartIp(), range.getEndIp())) {
                return range.getGroupId();
            }
        }
        return null;
    }

    /**
     * IPv4 주소가 start~end 범위 내에 있는지 확인한다.
     * 파싱 실패 시 false를 반환하여 안전하게 처리한다.
     *
     * @param ip    검사할 IP 주소
     * @param start 범위 시작 IP
     * @param end   범위 종료 IP
     * @return 범위 내에 있으면 true
     */
    private boolean isIpInRange(String ip, String start, String end) {
        try {
            long ipLo = ipToLong(start);
            long ipHi = ipToLong(end);
            long ipToTest = ipToLong(ip);
            return ipToTest >= ipLo && ipToTest <= ipHi;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * IPv4 주소 문자열을 long 값으로 변환한다.
     *
     * @param ip IPv4 주소 문자열 (예: "192.168.1.1")
     * @return 32비트 정수를 long으로 표현한 값
     */
    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (String part : parts) {
            result = result << 8;
            result |= Integer.parseInt(part);
        }
        return result;
    }
}