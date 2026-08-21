package com.example.backend.service.dashboard;

import com.example.backend.dto.dashboard.IpRangeBatchRequest;
import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import com.example.backend.service.classroom.ClassroomValidationService;
import com.example.backend.util.DeviceAddressUtil;
import com.example.backend.util.IpRangeUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 강의실 IP 범위 및 교사 장치 관리 서비스.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IpRangeService {

    private static final Logger logger = LoggerFactory.getLogger(IpRangeService.class);

    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final PcMasterRepository pcMasterRepository;
    private final ClassroomResolutionService classroomResolutionService;
    private final ClassroomValidationService classroomValidationService;

    /**
     * 모든 IP 범위를 조회하고 그룹명을 보강한다.
     */
    public List<ClassroomIpRange> findAll() {
        List<ClassroomIpRange> ranges = ipRangeRepository.findAll();
        populateGroupNames(ranges);
        return ranges;
    }

    /**
     * 강의실별 IP 범위를 조회하고 그룹명을 보강한다.
     */
    public List<ClassroomIpRange> getClassroomIpRanges(Long groupId) {
        List<ClassroomIpRange> ranges = ipRangeRepository.findByGroupId(groupId);
        populateGroupNames(ranges);
        return ranges;
    }

    /**
     * 새 IP 범위를 저장한다. 커밋 후 캐시를 무효화한다.
     */
    @Transactional
    public ClassroomIpRange saveClassroomIpRange(ClassroomIpRange range) {
        ClassroomIpRange saved = ipRangeRepository.save(range);
        evictClassroomResolutionCacheAfterCommit();
        logger.info("IP range saved: id={}, groupId={}", saved.getId(), saved.getGroupId());
        return saved;
    }

    /**
     * 여러 IP 범위를 rangeSpec 파싱 후 일괄 저장한다.
     */
    @Transactional
    public List<ClassroomIpRange> saveBatch(IpRangeBatchRequest req) {
        List<ClassroomIpRange> saved = new ArrayList<>();
        String normalizedSpec = req.getRangeSpec()
                .replace("\n", ",").replace(";", ",").replace(" ", ",").replace("\t", ",");
        String[] tokens = normalizedSpec.split(",");

        for (String raw : tokens) {
            String token = raw == null ? "" : raw.trim();
            if (token.isBlank()) continue;

            ClassroomIpRange item = new ClassroomIpRange();
            item.setGroupId(req.getGroupId());
            item.setDescription(req.getDescription());

            if (token.contains("/")) {
                item.setCidr(token);
            } else if (isIpv4RangeToken(token)) {
                String normalized = token.replace("~", "-");
                String[] p = normalized.split("-");
                item.setStartIp(p[0].trim());
                item.setEndIp(p[1].trim());
            } else if (isLastOctetRangeToken(token)) {
                String prefix = req.getBasePrefix();
                if (prefix == null || prefix.isBlank()) {
                    throw new IllegalArgumentException("last-octet 범위 사용 시 basePrefix가 필요합니다. 예: 10.0.1.");
                }
                String normalized = token.replace("~", "-");
                String[] p = normalized.split("-");
                int s = Integer.parseInt(p[0].trim());
                int e = Integer.parseInt(p[1].trim());
                if (s < 0 || s > 255 || e < 0 || e > 255) {
                    throw new IllegalArgumentException("IPv4 마지막 옥텟 범위는 0~255 입니다: " + token);
                }
                item.setStartIp(prefix + s);
                item.setEndIp(prefix + e);
            } else {
                throw new IllegalArgumentException("지원하지 않는 rangeSpec 토큰 형식: " + token);
            }

            if (item.getStartIp() != null && !IpRangeUtil.isIpv4(item.getStartIp())) {
                throw new IllegalArgumentException("startIp 형식이 올바르지 않습니다: " + item.getStartIp());
            }
            if (item.getEndIp() != null && !IpRangeUtil.isIpv4(item.getEndIp())) {
                throw new IllegalArgumentException("endIp 형식이 올바르지 않습니다: " + item.getEndIp());
            }

            classroomValidationService.validateIpRangeForCreate(item);
            saved.add(ipRangeRepository.save(item));
        }

        if (!saved.isEmpty()) {
            classroomResolutionService.evictIpRangeCache();
        }
        return saved;
    }

    /**
     * IP 범위를 삭제하고 캐시를 무효화한다.
     */
    @Transactional
    public void deleteClassroomIpRange(Long id) {
        ipRangeRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
        logger.info("IP range deleted: id={}", id);
    }

    /**
     * 강의실별 교사 장치 목록을 조회하고 그룹명을 보강한다.
     */
    public List<ClassroomTeacherDevice> getTeacherDevices(Long groupId) {
        List<ClassroomTeacherDevice> devices = teacherDeviceRepository.findByGroupId(groupId);
        populateTeacherGroupNames(devices);
        return devices;
    }

    /**
     * 교사 장치를 저장한다.
     */
    @Transactional
    public ClassroomTeacherDevice saveTeacherDevice(ClassroomTeacherDevice device) {
        normalizeTeacherDevice(device);
        ClassroomTeacherDevice saved = teacherDeviceRepository.save(device);
        classroomResolutionService.evictIpRangeCache();
        refreshPcClassificationForTeacherDevice(saved);
        evictClassroomResolutionCacheAfterCommit();
        logger.info("Teacher device saved: id={}, groupId={}", saved.getId(), saved.getGroupId());
        return saved;
    }

    /**
     * 교사 장치를 삭제한다.
     */
    @Transactional
    public void deleteTeacherDevice(Long id) {
        ClassroomTeacherDevice deleted = teacherDeviceRepository.findById(id).orElse(null);
        teacherDeviceRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
        refreshPcClassificationForTeacherDevice(deleted);
        evictClassroomResolutionCacheAfterCommit();
        logger.info("Teacher device deleted: id={}", id);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private void evictClassroomResolutionCacheAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                classroomResolutionService.evictIpRangeCache();
            }
        });
    }

    private void refreshPcClassificationForTeacherDevice(ClassroomTeacherDevice device) {
        if (device == null) {
            return;
        }
        Optional<PcMaster> pc = Optional.empty();
        String normalizedIp = DeviceAddressUtil.normalizeIp(device.getIpAddress());
        if (normalizedIp != null) {
            pc = pcMasterRepository.findByIpAddress(normalizedIp);
        }
        if (pc.isEmpty()) {
            pc = findPcByNormalizedMac(device.getMacAddress());
        }
        pc.ifPresent(existing -> {
            Long groupId = classroomResolutionService.resolveGroupId(existing.getIpAddress(), existing.getMacAddress());
            if (groupId == null) {
                groupId = device.getGroupId();
            }
            existing.setGroupId(groupId);
            existing.setRole(classroomResolutionService
                    .resolveRole(groupId, existing.getIpAddress(), existing.getMacAddress())
                    .name());
            pcMasterRepository.save(existing);
        });
    }

    private void normalizeTeacherDevice(ClassroomTeacherDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("교사 PC 정보가 필요합니다.");
        }
        device.setIpAddress(DeviceAddressUtil.normalizeIp(device.getIpAddress()));
        if (device.getGroupId() == null) {
            throw new IllegalArgumentException("강의실 ID가 필요합니다.");
        }
        if (!IpRangeUtil.isIpv4(device.getIpAddress())) {
            throw new IllegalArgumentException("교사 PC의 올바른 IPv4 주소가 필요합니다.");
        }
        String normalizedMac = DeviceAddressUtil.normalizeMac(device.getMacAddress());
        device.setMacAddress(formatMacForStorage(normalizedMac));
        if (device.getDescription() != null) {
            String trimmedDescription = device.getDescription().trim();
            device.setDescription(trimmedDescription.isEmpty() ? null : trimmedDescription);
        }
    }

    private String formatMacForStorage(String normalizedMac) {
        if (normalizedMac == null || normalizedMac.length() != 12) {
            return normalizedMac;
        }
        return normalizedMac.replaceAll("..(?!$)", "$0:");
    }

    private Optional<PcMaster> findPcByNormalizedMac(String macAddress) {
        String normalizedMac = DeviceAddressUtil.normalizeMac(macAddress);
        if (normalizedMac == null) {
            return Optional.empty();
        }
        return pcMasterRepository.findAll().stream()
                .filter(pc -> normalizedMac.equals(DeviceAddressUtil.normalizeMac(pc.getMacAddress())))
                .findFirst();
    }

    private Map<Long, String> buildGroupNameMap() {
        return classroomGroupRepository.findAll().stream()
                .collect(Collectors.toMap(ClassroomGroup::getId, ClassroomGroup::getName, (a, b) -> a));
    }

    private void populateGroupNames(List<ClassroomIpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        ranges.forEach(r -> r.setGroupName(nameMap.getOrDefault(r.getGroupId(), "강의실#" + r.getGroupId())));
    }

    private void populateTeacherGroupNames(List<ClassroomTeacherDevice> devices) {
        if (devices == null || devices.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        devices.forEach(d -> d.setGroupName(nameMap.getOrDefault(d.getGroupId(), "강의실#" + d.getGroupId())));
    }

    private static boolean isIpv4RangeToken(String token) {
        String normalized = token.replace("~", "-");
        String[] p = normalized.split("-");
        return p.length == 2 && IpRangeUtil.isIpv4(p[0].trim()) && IpRangeUtil.isIpv4(p[1].trim());
    }

    private static boolean isLastOctetRangeToken(String token) {
        return token.matches("^\\d{1,3}\\s*[-~]\\s*\\d{1,3}$");
    }
}
