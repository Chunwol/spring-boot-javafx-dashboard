package com.example.backend.service.classroom;

import com.example.backend.common.exception.ConflictException;
import com.example.backend.model.classroom.ClassroomIpRange;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.classroom.ClassroomIpRangeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassroomValidationServiceTest {

    @Mock ClassroomGroupRepository groupRepository;
    @Mock ClassroomIpRangeRepository ipRangeRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks ClassroomValidationService service;

    // ── validateGroupNameForCreate ─────────────────────────────────────

    @Test
    void validateGroupNameForCreate_null_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.validateGroupNameForCreate(null))
                .withMessageContaining("필수");
    }

    @Test
    void validateGroupNameForCreate_blank_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.validateGroupNameForCreate("  "))
                .withMessageContaining("필수");
    }

    @Test
    void validateGroupNameForCreate_duplicate_throwsConflict() {
        when(groupRepository.existsByNameIgnoreCase("강의실A")).thenReturn(true);

        assertThatThrownBy(() -> service.validateGroupNameForCreate("강의실A"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 존재");
    }

    @Test
    void validateGroupNameForCreate_unique_noException() {
        when(groupRepository.existsByNameIgnoreCase("새강의실")).thenReturn(false);

        assertThatCode(() -> service.validateGroupNameForCreate("새강의실"))
                .doesNotThrowAnyException();
    }

    // ── validateGroupNameForUpdate ─────────────────────────────────────

    @Test
    void validateGroupNameForUpdate_duplicateOther_throwsConflict() {
        when(groupRepository.existsByNameIgnoreCaseAndIdNot("강의실B", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.validateGroupNameForUpdate(1L, "강의실B"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void validateGroupNameForUpdate_sameId_ok() {
        when(groupRepository.existsByNameIgnoreCaseAndIdNot("강의실A", 1L)).thenReturn(false);

        assertThatCode(() -> service.validateGroupNameForUpdate(1L, "강의실A"))
                .doesNotThrowAnyException();
    }

    // ── validateIpRangeForCreate ───────────────────────────────────────

    @Test
    void validateIpRangeForCreate_null_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.validateIpRangeForCreate(null));
    }

    @Test
    void validateIpRangeForCreate_nullGroupId_throws() {
        ClassroomIpRange range = new ClassroomIpRange();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.validateIpRangeForCreate(range))
                .withMessageContaining("groupId");
    }

    @Test
    void validateIpRangeForCreate_noOverlap_ok() {
        ClassroomIpRange existing = new ClassroomIpRange();
        existing.setGroupId(1L);
        existing.setStartIp("192.168.1.1");
        existing.setEndIp("192.168.1.100");
        when(ipRangeRepository.findAll()).thenReturn(List.of(existing));

        ClassroomIpRange incoming = new ClassroomIpRange();
        incoming.setGroupId(2L);
        incoming.setStartIp("192.168.2.1");
        incoming.setEndIp("192.168.2.100");

        assertThatCode(() -> service.validateIpRangeForCreate(incoming))
                .doesNotThrowAnyException();
    }

    @Test
    void validateIpRangeForCreate_overlap_throwsConflict() {
        ClassroomIpRange existing = new ClassroomIpRange();
        existing.setId(1L);
        existing.setGroupId(1L);
        existing.setStartIp("192.168.1.1");
        existing.setEndIp("192.168.1.100");
        when(ipRangeRepository.findAll()).thenReturn(List.of(existing));

        ClassroomIpRange incoming = new ClassroomIpRange();
        incoming.setGroupId(2L);
        incoming.setStartIp("192.168.1.50");
        incoming.setEndIp("192.168.1.150");

        assertThatThrownBy(() -> service.validateIpRangeForCreate(incoming))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void validateIpRangeForCreate_cidrOverlap_throwsConflict() {
        ClassroomIpRange existing = new ClassroomIpRange();
        existing.setId(1L);
        existing.setGroupId(1L);
        existing.setCidr("192.168.1.0/24");
        when(ipRangeRepository.findAll()).thenReturn(List.of(existing));

        ClassroomIpRange incoming = new ClassroomIpRange();
        incoming.setGroupId(2L);
        incoming.setCidr("192.168.1.0/25");

        assertThatThrownBy(() -> service.validateIpRangeForCreate(incoming))
                .isInstanceOf(ConflictException.class);
    }
}
