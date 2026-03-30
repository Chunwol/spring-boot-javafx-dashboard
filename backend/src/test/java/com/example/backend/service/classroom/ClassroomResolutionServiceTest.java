package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import com.example.backend.model.classroom.ClassroomTeacherDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassroomResolutionServiceTest {

    @Mock ClassroomIpRangeRepository rangeRepository;
    @Mock ClassroomTeacherDeviceRepository teacherDeviceRepository;
    @InjectMocks ClassroomResolutionService service;

    @Test
    void resolveGroupIdByIp_matchesCidr() {
        ClassroomIpRange range = new ClassroomIpRange();
        range.setGroupId(10L);
        range.setCidr("192.168.1.0/24");
        when(rangeRepository.findAll()).thenReturn(List.of(range));

        Long groupId = service.resolveGroupIdByIp("192.168.1.50");

        assertThat(groupId).isEqualTo(10L);
    }

    @Test
    void resolveGroupIdByIp_matchesStartEndRange() {
        ClassroomIpRange range = new ClassroomIpRange();
        range.setGroupId(20L);
        range.setStartIp("10.0.0.1");
        range.setEndIp("10.0.0.100");
        when(rangeRepository.findAll()).thenReturn(List.of(range));

        Long groupId = service.resolveGroupIdByIp("10.0.0.50");

        assertThat(groupId).isEqualTo(20L);
    }

    @Test
    void resolveGroupIdByIp_noMatch_returnsNull() {
        ClassroomIpRange range = new ClassroomIpRange();
        range.setGroupId(10L);
        range.setCidr("192.168.1.0/24");
        when(rangeRepository.findAll()).thenReturn(List.of(range));

        Long groupId = service.resolveGroupIdByIp("10.0.0.1");

        assertThat(groupId).isNull();
    }

    @Test
    void resolveGroupIdByIp_invalidIp_returnsNull() {
        assertThat(service.resolveGroupIdByIp("not-an-ip")).isNull();
        assertThat(service.resolveGroupIdByIp(null)).isNull();
    }

    @Test
    void resolveRole_nullGroupId_returnsUnknown() {
        assertThat(service.resolveRole(null, "192.168.1.1", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.UNKNOWN);
    }

    @Test
    void resolveRole_teacherByIp() {
        ClassroomTeacherDevice device = new ClassroomTeacherDevice();
        when(teacherDeviceRepository.findFirstByGroupIdAndIpAddress(1L, "192.168.1.10"))
                .thenReturn(device);

        assertThat(service.resolveRole(1L, "192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveRole_teacherByMac() {
        when(teacherDeviceRepository.findFirstByGroupIdAndIpAddress(1L, "192.168.1.10"))
                .thenReturn(null);
        ClassroomTeacherDevice device = new ClassroomTeacherDevice();
        when(teacherDeviceRepository.findFirstByGroupIdAndMacAddress(1L, "AA:BB:CC:DD:EE:FF"))
                .thenReturn(device);

        assertThat(service.resolveRole(1L, "192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveRole_student() {
        when(teacherDeviceRepository.findFirstByGroupIdAndIpAddress(1L, "192.168.1.50"))
                .thenReturn(null);
        when(teacherDeviceRepository.findFirstByGroupIdAndMacAddress(1L, "AA:BB:CC:DD:EE:FF"))
                .thenReturn(null);

        assertThat(service.resolveRole(1L, "192.168.1.50", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.STUDENT);
    }
}
