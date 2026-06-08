package com.example.backend.service.classroom;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.event.AlertEvent;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.util.IpRangeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 강의실 그룹 및 IP 범위 유효성 검증 서비스.
 * <p>
 * 강의실 이름 중복, IP 대역 중복 검사를 수행하며,
 * 충돌 감지 시 AlertEvent를 발행하고 {@link ConflictException}을 던진다.
 * </p>
 */
@Service
public class ClassroomValidationService {

    private static final Logger log = LoggerFactory.getLogger(ClassroomValidationService.class);

    private final ClassroomGroupRepository groupRepository;
    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ClassroomValidationService(ClassroomGroupRepository groupRepository,
                                      ClassroomIpRangeRepository ipRangeRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.groupRepository = groupRepository;
        this.ipRangeRepository = ipRangeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 강의실 생성 시 이름 중복을 검증한다.
     * 대소문자를 구분하지 않는다.
     *
     * @param name 등록할 강의실 이름
     * @throws IllegalArgumentException 이름이 null 또는 공백인 경우
     * @throws ConflictException        동일한 이름이 이미 존재하는 경우
     */
    public void validateGroupNameForCreate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("강의실 이름은 필수입니다.");
        }
        boolean duplicated = groupRepository.existsByNameIgnoreCase(name.trim());
        if (duplicated) {
            String msg = "강의실 이름이 이미 존재합니다: " + name;
            publishAlert(msg);
            throw new ConflictException(msg);
        }
    }

    /**
     * 강의실 수정 시 이름 중복을 검증한다. 자기 자신(id)은 제외하고 검사한다.
     *
     * @param id   수정 대상 강의실 ID
     * @param name 변경할 강의실 이름
     * @throws IllegalArgumentException 이름이 null 또는 공백인 경우
     * @throws ConflictException        다른 강의실에 동일한 이름이 이미 사용 중인 경우
     */
    public void validateGroupNameForUpdate(Long id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("강의실 이름은 필수입니다.");
        }
        boolean duplicated = groupRepository.existsByNameIgnoreCaseAndIdNot(name.trim(), id);
        if (duplicated) {
            String msg = "수정하려는 강의실 이름이 이미 사용 중입니다: " + name;
            publishAlert(msg);
            throw new ConflictException(msg);
        }
    }

    /**
     * IP 범위 등록 시 기존 대역과의 중복(overlap)을 검증한다.
     * CIDR 또는 startIp/endIp 방식 모두 지원한다.
     * 중복된 경우 같은 강의실인지 다른 강의실인지를 메시지에 포함한다.
     *
     * @param request 등록 요청 IP 범위 객체
     * @throws IllegalArgumentException 요청 데이터가 불완전하거나 형식이 잘못된 경우
     * @throws ConflictException        기존 대역과 중복되는 경우
     */
    public void validateIpRangeForCreate(ClassroomIpRange request) {
        if (request == null) {
            throw new IllegalArgumentException("IP 범위 요청이 비어 있습니다.");
        }
        if (request.getGroupId() == null) {
            throw new IllegalArgumentException("groupId는 필수입니다.");
        }

        long[] incoming = toInterval(request);
        List<ClassroomIpRange> existing = ipRangeRepository.findAll();
        for (ClassroomIpRange r : existing) {
            long[] interval;
            try {
                interval = toInterval(r);
            } catch (Exception e) {
                log.debug("기존 IP 대역 파싱 실패 (id={}): {}", r.getId(), e.getMessage());
                continue;
            }

            if (isOverlapped(incoming[0], incoming[1], interval[0], interval[1])) {
                String scope = request.getGroupId().equals(r.getGroupId()) ? "동일 강의실" : "다른 강의실";
                String msg = "IP 대역이 " + scope + " 기존 대역과 중복됩니다."
                        + " incoming=" + displayRange(request)
                        + ", existing(id=" + r.getId() + ", groupId=" + r.getGroupId() + ")=" + displayRange(r);
                publishAlert(msg);
                throw new ConflictException(msg);
            }
        }
    }

    private long[] toInterval(ClassroomIpRange r) {
        if (r.getCidr() != null && !r.getCidr().isBlank()) {
            String[] p = r.getCidr().trim().split("/");
            if (p.length != 2 || !IpRangeUtil.isIpv4(p[0])) {
                throw new IllegalArgumentException("CIDR 형식이 올바르지 않습니다: " + r.getCidr());
            }
            int prefix;
            try {
                prefix = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CIDR prefix가 올바르지 않습니다: " + r.getCidr());
            }
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("CIDR prefix 범위는 0~32 입니다: " + r.getCidr());
            }
            long base = IpRangeUtil.ipv4ToLong(p[0]);
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            long start = base & mask;
            long end = start | (~mask & 0xFFFFFFFFL);
            return new long[]{start, end};
        }

        if (r.getStartIp() == null || r.getEndIp() == null
                || !IpRangeUtil.isIpv4(r.getStartIp())
                || !IpRangeUtil.isIpv4(r.getEndIp())) {
            throw new IllegalArgumentException("startIp/endIp 또는 cidr 중 하나는 유효해야 합니다.");
        }
        long s = IpRangeUtil.ipv4ToLong(r.getStartIp());
        long e = IpRangeUtil.ipv4ToLong(r.getEndIp());
        if (s > e) {
            long tmp = s;
            s = e;
            e = tmp;
        }
        return new long[]{s, e};
    }

    private static boolean isOverlapped(long s1, long e1, long s2, long e2) {
        return s1 <= e2 && s2 <= e1;
    }

    private static String displayRange(ClassroomIpRange r) {
        if (r.getCidr() != null && !r.getCidr().isBlank()) {
            return r.getCidr();
        }
        return (r.getStartIp() == null ? "" : r.getStartIp()) + "~"
                + (r.getEndIp() == null ? "" : r.getEndIp());
    }

    private void publishAlert(String message) {
        eventPublisher.publishEvent(new AlertEvent(this, "SYSTEM", "[중복/충돌 감지] " + message));
    }
}
