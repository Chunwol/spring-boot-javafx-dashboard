package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.util.DeviceAddressUtil;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 교사 장치(IP/MAC) 조회를 메모리 스냅샷으로 제공한다.
 * <p>
 * 강의실별 교사 장치 수는 매우 작고 하트비트 수는 많으므로,
 * 매 heartbeat마다 DB를 조회하지 않고 전체 교사 장치 목록을 한 번 적재해 재사용한다.
 * teacher device 변경 시 {@link #evictCache()}로 스냅샷을 무효화한다.
 * </p>
 */
@Service
public class TeacherDeviceLookupService {

    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;

    private volatile Snapshot snapshot;

    public TeacherDeviceLookupService(ClassroomTeacherDeviceRepository teacherDeviceRepository) {
        this.teacherDeviceRepository = teacherDeviceRepository;
    }

    public TeacherDeviceMatch findMatch(String ipAddress, String macAddress) {
        Snapshot current = getSnapshot();

        String normalizedIp = DeviceAddressUtil.normalizeIp(ipAddress);
        if (normalizedIp != null) {
            TeacherDeviceMatch match = current.byIp().get(normalizedIp);
            if (match != null) {
                return match;
            }
        }

        String normalizedMac = DeviceAddressUtil.normalizeMac(macAddress);
        if (normalizedMac != null) {
            return current.byMac().get(normalizedMac);
        }
        return null;
    }

    public void evictCache() {
        snapshot = null;
    }

    private Snapshot getSnapshot() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (snapshot == null) {
                snapshot = loadSnapshot();
            }
            return snapshot;
        }
    }

    private Snapshot loadSnapshot() {
        List<ClassroomTeacherDevice> devices =
                teacherDeviceRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Map<String, TeacherDeviceMatch> byIp = new HashMap<>();
        Map<String, TeacherDeviceMatch> byMac = new HashMap<>();

        for (ClassroomTeacherDevice device : devices) {
            TeacherDeviceMatch match = new TeacherDeviceMatch(device.getId(), device.getGroupId());

            String normalizedIp = DeviceAddressUtil.normalizeIp(device.getIpAddress());
            if (normalizedIp != null) {
                byIp.put(normalizedIp, match);
            }

            String normalizedMac = DeviceAddressUtil.normalizeMac(device.getMacAddress());
            if (normalizedMac != null) {
                byMac.put(normalizedMac, match);
            }
        }

        return new Snapshot(Map.copyOf(byIp), Map.copyOf(byMac));
    }

    public record TeacherDeviceMatch(Long teacherDeviceId, Long groupId) {
    }

    private record Snapshot(Map<String, TeacherDeviceMatch> byIp,
                            Map<String, TeacherDeviceMatch> byMac) {
    }
}
