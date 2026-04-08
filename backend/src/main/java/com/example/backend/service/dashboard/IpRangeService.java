package com.example.backend.service.dashboard;

import com.example.backend.model.classroom.ClassroomGroup;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing classroom IP ranges and teacher device information.
 */
@Service
public class IpRangeService {

    private static final Logger logger = LoggerFactory.getLogger(IpRangeService.class);

    private final ClassroomIpRangeRepository ipRangeRepository;
    private final ClassroomTeacherDeviceRepository teacherDeviceRepository;
    private final ClassroomGroupRepository classroomGroupRepository;
    private final ClassroomResolutionService classroomResolutionService;

    public IpRangeService(ClassroomIpRangeRepository ipRangeRepository,
                          ClassroomTeacherDeviceRepository teacherDeviceRepository,
                          ClassroomGroupRepository classroomGroupRepository,
                          ClassroomResolutionService classroomResolutionService) {
        this.ipRangeRepository = ipRangeRepository;
        this.teacherDeviceRepository = teacherDeviceRepository;
        this.classroomGroupRepository = classroomGroupRepository;
        this.classroomResolutionService = classroomResolutionService;
    }

    /**
     * Get all IP ranges for a classroom
     */
    public List<ClassroomIpRange> getClassroomIpRanges(Long groupId) {
        List<ClassroomIpRange> ranges = ipRangeRepository.findByGroupId(groupId);
        populateGroupNames(ranges);
        return ranges;
    }

    /**
     * Save a new IP range or update existing one
     */
    public ClassroomIpRange saveClassroomIpRange(ClassroomIpRange range) {
        ClassroomIpRange saved = ipRangeRepository.save(range);
        classroomResolutionService.evictIpRangeCache();
        logger.info("IP range saved: id={}, groupId={}", saved.getId(), saved.getGroupId());
        return saved;
    }

    /**
     * Delete an IP range
     */
    public void deleteClassroomIpRange(Long id) {
        ipRangeRepository.deleteById(id);
        classroomResolutionService.evictIpRangeCache();
        logger.info("IP range deleted: id={}", id);
    }

    /**
     * Get all teacher devices for a classroom
     */
    public List<ClassroomTeacherDevice> getTeacherDevices(Long groupId) {
        List<ClassroomTeacherDevice> devices = teacherDeviceRepository.findByGroupId(groupId);
        populateTeacherGroupNames(devices);
        return devices;
    }

    /**
     * Save a new teacher device or update existing one
     */
    public ClassroomTeacherDevice saveTeacherDevice(ClassroomTeacherDevice device) {
        ClassroomTeacherDevice saved = teacherDeviceRepository.save(device);
        logger.info("Teacher device saved: id={}, groupId={}", saved.getId(), saved.getGroupId());
        return saved;
    }

    /**
     * Delete a teacher device
     */
    public void deleteTeacherDevice(Long id) {
        teacherDeviceRepository.deleteById(id);
        logger.info("Teacher device deleted: id={}", id);
    }

    /**
     * Build a map of group ID to group name for efficient lookups
     */
    private Map<Long, String> buildGroupNameMap() {
        return classroomGroupRepository.findAll().stream()
                .collect(Collectors.toMap(
                        ClassroomGroup::getId,
                        ClassroomGroup::getName,
                        (a, b) -> a));
    }

    /**
     * Populate group names for IP ranges
     */
    private void populateGroupNames(List<ClassroomIpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        ranges.forEach(r -> r.setGroupName(nameMap.getOrDefault(r.getGroupId(), "강의실#" + r.getGroupId())));
    }

    /**
     * Populate group names for teacher devices
     */
    private void populateTeacherGroupNames(List<ClassroomTeacherDevice> devices) {
        if (devices == null || devices.isEmpty()) return;
        Map<Long, String> nameMap = buildGroupNameMap();
        devices.forEach(d -> d.setGroupName(nameMap.getOrDefault(d.getGroupId(), "강의실#" + d.getGroupId())));
    }
}
