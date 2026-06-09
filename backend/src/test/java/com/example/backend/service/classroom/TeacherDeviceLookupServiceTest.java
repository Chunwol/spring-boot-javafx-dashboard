package com.example.backend.service.classroom;

import com.example.backend.model.classroom.ClassroomTeacherDevice;
import com.example.backend.repository.classroom.ClassroomTeacherDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherDeviceLookupServiceTest {

    @Mock
    ClassroomTeacherDeviceRepository teacherDeviceRepository;

    @InjectMocks
    TeacherDeviceLookupService teacherDeviceLookupService;

    @Test
    void findMatch_matchesByIpAndCachesSnapshot() {
        ClassroomTeacherDevice device = new ClassroomTeacherDevice();
        device.setId(10L);
        device.setGroupId(29L);
        device.setIpAddress("223.194.168.18");

        when(teacherDeviceRepository.findAll(Sort.by(Sort.Direction.ASC, "id")))
                .thenReturn(List.of(device));

        var first = teacherDeviceLookupService.findMatch("223.194.168.18", null);
        var second = teacherDeviceLookupService.findMatch("223.194.168.18", null);

        assertThat(first).isNotNull();
        assertThat(first.groupId()).isEqualTo(29L);
        assertThat(second).isEqualTo(first);
        verify(teacherDeviceRepository, times(1)).findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void findMatch_matchesByNormalizedMac() {
        ClassroomTeacherDevice device = new ClassroomTeacherDevice();
        device.setId(11L);
        device.setGroupId(30L);
        device.setMacAddress("10:E7:C6:4A:21:B8");

        when(teacherDeviceRepository.findAll(Sort.by(Sort.Direction.ASC, "id")))
                .thenReturn(List.of(device));

        var match = teacherDeviceLookupService.findMatch(null, "10-e7-c6-4a-21-b8");

        assertThat(match).isNotNull();
        assertThat(match.groupId()).isEqualTo(30L);
    }

    @Test
    void evictCache_reloadsLatestTeacherDeviceSnapshot() {
        ClassroomTeacherDevice oldDevice = new ClassroomTeacherDevice();
        oldDevice.setId(12L);
        oldDevice.setGroupId(21L);
        oldDevice.setIpAddress("223.194.166.20");

        ClassroomTeacherDevice newDevice = new ClassroomTeacherDevice();
        newDevice.setId(13L);
        newDevice.setGroupId(22L);
        newDevice.setIpAddress("223.194.166.20");

        when(teacherDeviceRepository.findAll(Sort.by(Sort.Direction.ASC, "id")))
                .thenReturn(List.of(oldDevice))
                .thenReturn(List.of(newDevice));

        assertThat(teacherDeviceLookupService.findMatch("223.194.166.20", null).groupId()).isEqualTo(21L);

        teacherDeviceLookupService.evictCache();

        assertThat(teacherDeviceLookupService.findMatch("223.194.166.20", null).groupId()).isEqualTo(22L);
        verify(teacherDeviceRepository, times(2)).findAll(Sort.by(Sort.Direction.ASC, "id"));
    }
}
