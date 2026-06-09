package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.service.classroom.TeacherDeviceLookupService.TeacherDeviceMatch;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
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
    @Mock TeacherDeviceLookupService teacherDeviceLookupService;
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
    void resolveGroupIdByIp_teacherDeviceIpTakesPrecedence() {
        when(teacherDeviceLookupService.findMatch("10.0.0.10", null))
                .thenReturn(new TeacherDeviceMatch(1L, 30L));

        Long groupId = service.resolveGroupIdByIp("10.0.0.10");

        assertThat(groupId).isEqualTo(30L);
        verify(rangeRepository, never()).findAll();
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
    void resolveRole_teacherByIpWithoutGroupId() {
        when(teacherDeviceLookupService.findMatch("192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .thenReturn(new TeacherDeviceMatch(2L, 1L));

        assertThat(service.resolveRole(null, "192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveRole_teacherByIp() {
        when(teacherDeviceLookupService.findMatch("192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .thenReturn(new TeacherDeviceMatch(3L, 1L));

        assertThat(service.resolveRole(1L, "192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveRole_teacherByMac() {
        when(teacherDeviceLookupService.findMatch("192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .thenReturn(new TeacherDeviceMatch(4L, 1L));

        assertThat(service.resolveRole(1L, "192.168.1.10", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveRole_student() {
        assertThat(service.resolveRole(1L, "192.168.1.50", "AA:BB:CC:DD:EE:FF"))
                .isEqualTo(ClassroomResolutionService.PcRole.STUDENT);
    }

    @Test
    void resolveGroupIdByIp_trimsTeacherDeviceLookupInput() {
        when(teacherDeviceLookupService.findMatch(" 10.0.0.11 ", null))
                .thenReturn(new TeacherDeviceMatch(5L, 31L));

        Long groupId = service.resolveGroupIdByIp(" 10.0.0.11 ");

        assertThat(groupId).isEqualTo(31L);
        verify(rangeRepository, never()).findAll();
    }

    @Test
    void resolveRole_teacherByMac_ignoresCaseAndSeparators() {
        when(teacherDeviceLookupService.findMatch("192.168.1.50", "aa-bb-cc-dd-ee-ff"))
                .thenReturn(new TeacherDeviceMatch(6L, 1L));

        assertThat(service.resolveRole(null, "192.168.1.50", "aa-bb-cc-dd-ee-ff"))
                .isEqualTo(ClassroomResolutionService.PcRole.TEACHER);
    }

    @Test
    void resolveGroupId_teacherDeviceMacTakesPrecedence() {
        when(teacherDeviceLookupService.findMatch("10.0.9.9", "AA-BB-CC-DD-EE-FF"))
                .thenReturn(new TeacherDeviceMatch(7L, 44L));

        Long groupId = service.resolveGroupId("10.0.9.9", "AA-BB-CC-DD-EE-FF");

        assertThat(groupId).isEqualTo(44L);
        verify(rangeRepository, never()).findAll();
    }
}
