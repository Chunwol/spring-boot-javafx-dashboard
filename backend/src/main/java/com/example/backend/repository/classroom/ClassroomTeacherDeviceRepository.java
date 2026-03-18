package com.example.backend.repository.classroom;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClassroomTeacherDeviceRepository extends JpaRepository<ClassroomTeacherDevice, Long> {
    List<ClassroomTeacherDevice> findByGroupId(Long groupId);
    ClassroomTeacherDevice findFirstByGroupIdAndIpAddress(Long groupId, String ipAddress);
    ClassroomTeacherDevice findFirstByGroupIdAndMacAddress(Long groupId, String macAddress);
}

