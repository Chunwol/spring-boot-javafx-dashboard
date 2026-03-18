package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AutoGroupingService {

    @Autowired
    private ClassroomIpRangeRepository ipRangeRepository;

    /**
     * IP 주소를 기반으로 적절한 강의실 Group ID를 반환합니다.
     * 매칭되는 대역이 없으면 null을 반환합니다.
     */
    public Long resolveGroupId(String ipAddress) {
        List<ClassroomIpRange> ranges = ipRangeRepository.findAll();
        
        for (ClassroomIpRange range : ranges) {
            if (isIpInRange(ipAddress, range.getStartIp(), range.getEndIp())) {
                return range.getGroupId();
            }
        }
        return null;
    }

    // 간단한 IPv4 범위 체크 로직
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