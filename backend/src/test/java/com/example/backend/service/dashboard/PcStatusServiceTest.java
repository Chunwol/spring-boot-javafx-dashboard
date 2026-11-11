package com.example.backend.service.dashboard;

import com.example.backend.model.pc.PcMaster;
import com.example.backend.repository.classroom.ClassroomGroupRepository;
import com.example.backend.repository.pc.PcMasterRepository;
import com.example.backend.service.classroom.ClassroomResolutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import com.example.backend.common.exception.ConflictException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcStatusServiceTest {

    @Mock PcMasterRepository pcMasterRepository;
    @Mock ClassroomResolutionService classroomResolutionService;
    @Mock ClassroomGroupRepository classroomGroupRepository;
    @InjectMocks PcStatusService pcStatusService;

    @Test
    void updateDeviceNote_trimsAndMapsNoteWithoutChangingOperationalState() {
        String macAddress = "AA:BB:CC:DD:EE:FF";
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setStatus("ONLINE");
        pc.setBrokenNote(null);
        when(pcMasterRepository.findById(macAddress)).thenReturn(Optional.of(pc));

        var updated = pcStatusService.updateDeviceNote(macAddress, "  사용 가능·점검 필요  ");

        assertThat(pc.getDeviceNote()).isEqualTo("사용 가능·점검 필요");
        assertThat(pc.getStatus()).isEqualTo("ONLINE");
        assertThat(pc.getBrokenNote()).isNull();
        assertThat(updated.getDeviceNote()).isEqualTo("사용 가능·점검 필요");
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void updateDeviceNote_blankInputClearsNote() {
        String macAddress = "AA:BB:CC:DD:EE:FF";
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setDeviceNote("기존 메모");
        when(pcMasterRepository.findById(macAddress)).thenReturn(Optional.of(pc));

        var updated = pcStatusService.updateDeviceNote(macAddress, "   ");

        assertThat(pc.getDeviceNote()).isNull();
        assertThat(updated.getDeviceNote()).isNull();
        verify(pcMasterRepository).save(pc);
    }

    @Test
    void updateDeviceNote_rejectsMoreThanFiveHundredCharactersBeforeMutation() {
        String macAddress = "AA:BB:CC:DD:EE:FF";
        PcMaster pc = new PcMaster();
        pc.setMacAddress(macAddress);
        pc.setDeviceNote("기존 메모");
        when(pcMasterRepository.findById(macAddress)).thenReturn(Optional.of(pc));

        assertThatThrownBy(() -> pcStatusService.updateDeviceNote(macAddress, "가".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500자");

        assertThat(pc.getDeviceNote()).isEqualTo("기존 메모");
        verify(pcMasterRepository, never()).save(pc);
    }

    @Test
    void markPcAsBroken_withSeatNumber_createsPlaceholderAtThatSeat() {
        String ip = "172.16.13.128";
        when(pcMasterRepository.findByIpAddress(ip)).thenReturn(Optional.empty());
        when(classroomResolutionService.resolveGroupIdByIp(ip)).thenReturn(17L);
        when(classroomGroupRepository.existsById(17L)).thenReturn(true);
        when(classroomResolutionService.resolveRole(17L, ip, null))
                .thenReturn(ClassroomResolutionService.PcRole.STUDENT);

        pcStatusService.markPcAsBroken(null, ip, "메인보드 고장", 9);

        ArgumentCaptor<PcMaster> captor = ArgumentCaptor.forClass(PcMaster.class);
        verify(pcMasterRepository).save(captor.capture());
        PcMaster saved = captor.getValue();
        assertThat(saved.getPcName()).isEqualTo("미사용 PC 9");
        assertThat(saved.getBrokenNote()).isEqualTo("메인보드 고장");
        assertThat(saved.getStatus()).isEqualTo("BROKEN");
        assertThat(saved.getGroupId()).isEqualTo(17L);
    }

    @Test
    void markPcAsBroken_withSelectedGroup_placesUnusedSeatInThatClassroom() {
        String ip = "172.16.13.128";
        when(pcMasterRepository.findByIpAddress(ip)).thenReturn(Optional.empty());
        when(classroomGroupRepository.existsById(21L)).thenReturn(true);
        when(classroomResolutionService.resolveRole(21L, ip, null))
                .thenReturn(ClassroomResolutionService.PcRole.STUDENT);

        pcStatusService.markPcAsBroken(null, ip, "모니터 고장", 12, 21L);

        ArgumentCaptor<PcMaster> captor = ArgumentCaptor.forClass(PcMaster.class);
        verify(pcMasterRepository).save(captor.capture());
        assertThat(captor.getValue().getGroupId()).isEqualTo(21L);
        assertThat(captor.getValue().getPcName()).isEqualTo("미사용 PC 12");
    }

    @Test
    void markPcAsBroken_withSeatNumber_repairsExistingPlaceholderName() {
        String ip = "172.16.13.128";
        PcMaster placeholder = new PcMaster();
        placeholder.setMacAddress("BROKEN-IP::172-16-13-128");
        placeholder.setPcName("BROKEN " + ip);
        placeholder.setIpAddress(ip);
        when(pcMasterRepository.findByIpAddress(ip)).thenReturn(Optional.of(placeholder));

        pcStatusService.markPcAsBroken(null, ip, "미사용", 9);

        assertThat(placeholder.getPcName()).isEqualTo("미사용 PC 9");
        verify(pcMasterRepository).save(placeholder);
    }

    @Test
    void markPcAsBroken_unusedSeatRejectsIpOwnedByRealPc() {
        String ip = "172.16.13.128";
        PcMaster realPc = new PcMaster();
        realPc.setMacAddress("AA:BB:CC:DD:EE:FF");
        realPc.setIpAddress(ip);
        realPc.setGroupId(17L);
        when(pcMasterRepository.findByIpAddress(ip)).thenReturn(Optional.of(realPc));

        assertThatThrownBy(() -> pcStatusService.markPcAsBroken(null, ip, "미사용", 9, 21L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 등록된 PC");
    }

    @Test
    void scmateActions_offerBaselinePreparationRebootAfterOperatorModeChange() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setScmateAvailable(true);
        pc.setScmateMode("OPERATOR");
        pc.setScmateSchedule("MANUAL");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("OPERATOR_REQUESTED");
        pc.setAgentVersion("1.1.35");
        pc.setSystemBootTimeEpochSeconds(200L);

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);

        assertThat(actions).contains("REFRESH_STATUS_DETAIL", "REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED",
                "REQUEST_OPERATOR", "REQUEST_PROTECTED", "RESTORE_BASELINE");
        assertThat(actions).doesNotContain("UPDATE_BASELINE");
    }

    @Test
    void scmateActions_offerRestoreOnlyToSupportedStableProtectedPc() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("COMPLETED");
        pc.setAgentVersion("1.1.35");
        pc.setSystemBootTimeEpochSeconds(200L);

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);

        assertThat(actions).contains("REFRESH_STATUS_DETAIL", "REQUEST_OPERATOR", "REQUEST_PROTECTED",
                "REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED", "RESTORE_BASELINE");

        pc.setAgentVersion("1.1.34");
        List<String> flawedModeAgentActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(flawedModeAgentActions).containsExactly("REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED");

        pc.setAgentVersion("1.1.23");
        List<String> oldAgentActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(oldAgentActions).isEmpty();
    }

    @Test
    void scmateActions_offerOperatorTransitionAfterBaselineWasSavedAndProtectionIsActive() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setScmateAvailable(true);
        pc.setScmateMode("PROTECTED");
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("BASELINE_UPDATED");
        pc.setAgentVersion("1.1.35");
        pc.setSystemBootTimeEpochSeconds(200L);

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);

        assertThat(actions).contains("REFRESH_STATUS_DETAIL", "REQUEST_OPERATOR", "REQUEST_PROTECTED",
                "REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED", "RESTORE_BASELINE");
    }

    @Test
    void scmateActions_stableModesIgnoreStaleWorkflowStage() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setScmateAvailable(true);
        pc.setScmateTransitionPending(false);
        pc.setScmateWorkflowStage("BASELINE_REQUESTED");
        pc.setAgentVersion("1.1.35");
        pc.setSystemBootTimeEpochSeconds(200L);

        pc.setScmateMode("PROTECTED");
        List<String> protectedActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(protectedActions).contains("REFRESH_STATUS_DETAIL", "REQUEST_OPERATOR", "REQUEST_PROTECTED",
                "REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED", "RESTORE_BASELINE");

        pc.setScmateMode("OPERATOR");
        List<String> operatorActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(operatorActions).contains("REFRESH_STATUS_DETAIL", "REQUEST_OPERATOR", "REQUEST_PROTECTED",
                "REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED", "RESTORE_BASELINE");
    }

    @Test
    void scmateActions_offerRecoveryInstallOnlyForSupportedUnavailablePc() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setRole("STUDENT");
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("NOT_INSTALLED");
        pc.setAgentVersion("1.1.28");
        pc.setSystemBootTimeEpochSeconds(200L);

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(actions).contains("REBOOT_TO_OPERATOR",
                "REBOOT_TO_PROTECTED", "INSTALL_RECOVERY_MODULE");
        assertThat(actions).doesNotContain("REFRESH_STATUS_DETAIL");

        pc.setScmateWorkflowStage("INSTALL_PENDING_REBOOT");
        List<String> waitingActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(waitingActions).contains("REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED");
        assertThat(waitingActions).doesNotContain("REFRESH_STATUS_DETAIL");
        assertThat(waitingActions).doesNotContain("INSTALL_RECOVERY_MODULE");

        pc.setScmateWorkflowStage(null);
        pc.setAgentVersion("1.1.27");
        List<String> oldAgentActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(oldAgentActions).containsExactly("REBOOT_TO_OPERATOR", "REBOOT_TO_PROTECTED");

        pc.setAgentVersion("1.1.35");
        pc.setRole("TEACHER");
        List<String> teacherActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(teacherActions).containsExactly("REFRESH_STATUS_DETAIL");
    }

    @Test
    void scmateActions_offerUpdateOnlyAfterStableManualBootChange() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setRole("STUDENT");
        pc.setScmateAvailable(true);
        pc.setScmateSchedule("MANUAL");
        pc.setScmateTransitionPending(false);
        pc.setAgentVersion("1.1.35");
        pc.setScmateWorkflowStartBootTimeEpochSeconds(100L);
        pc.setSystemBootTimeEpochSeconds(200L);

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(actions).contains("UPDATE_BASELINE");

        pc.setScmateSchedule("WEEKLY");
        List<String> protectedScheduleActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(protectedScheduleActions).doesNotContain("UPDATE_BASELINE");
    }

    @Test
    void scmateActions_doNotOfferInstallForGenericUnavailableReason() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setRole("STUDENT");
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("LICENSE_EXPIRED");
        pc.setAgentVersion("1.1.35");

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(actions).doesNotContain("INSTALL_RECOVERY_MODULE");
        assertThat(actions).contains("REFRESH_STATUS_DETAIL");
    }

    @Test
    void scmateActions_offerProtectionEnableOnlyForDisabledCurrentAgent() {
        PcMaster pc = new PcMaster();
        pc.setStatus("ONLINE");
        pc.setRole("STUDENT");
        pc.setScmateAvailable(false);
        pc.setScmateAvailabilityReason("PROTECT_DISABLED");
        pc.setAgentVersion("1.1.40");

        List<String> actions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(actions).contains("ENABLE_PROTECTION");

        pc.setAgentVersion("1.1.39");
        List<String> oldAgentActions = ReflectionTestUtils.invokeMethod(
                pcStatusService, "resolveScmateAllowedActions", pc);
        assertThat(oldAgentActions).doesNotContain("ENABLE_PROTECTION");
    }
}
