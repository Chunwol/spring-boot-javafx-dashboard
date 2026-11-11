package com.example.backend.controller.dashboard;

import com.example.backend.config.TrafficThresholdConfig;
import com.example.backend.service.classroom.ClassroomValidationService;
import com.example.backend.service.dashboard.ClassroomService;
import com.example.backend.service.dashboard.DashboardService;
import com.example.backend.service.dashboard.IpRangeService;
import com.example.backend.service.dashboard.PcStatusService;
import com.example.dto.dashboard.PcStatusDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DashboardControllerDeviceNoteTest {

    @Mock DashboardService dashboardService;
    @Mock ClassroomService classroomService;
    @Mock PcStatusService pcStatusService;
    @Mock IpRangeService ipRangeService;
    @Mock ClassroomValidationService classroomValidationService;
    @Mock TrafficThresholdConfig trafficThresholdConfig;
    @InjectMocks DashboardController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void updateDeviceNote_decodesMacAndReturnsUpdatedPc() throws Exception {
        String macAddress = "AA:BB:CC:DD:EE:FF";
        PcStatusDto updated = new PcStatusDto();
        updated.setMacAddress(macAddress);
        updated.setDeviceNote("사용 가능·점검 필요");
        when(pcStatusService.updateDeviceNote(macAddress, "사용 가능·점검 필요")).thenReturn(updated);

        mockMvc.perform(put("/api/v1/dashboard/pcs/{macAddress}/note", macAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"사용 가능·점검 필요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.macAddress").value(macAddress))
                .andExpect(jsonPath("$.deviceNote").value("사용 가능·점검 필요"));

        verify(pcStatusService).updateDeviceNote(macAddress, "사용 가능·점검 필요");
    }

    @Test
    void updateDeviceNote_acceptsNullToClearTheMemo() throws Exception {
        String macAddress = "AA:BB:CC:DD:EE:FF";
        PcStatusDto updated = new PcStatusDto();
        updated.setMacAddress(macAddress);
        when(pcStatusService.updateDeviceNote(macAddress, null)).thenReturn(updated);

        mockMvc.perform(put("/api/v1/dashboard/pcs/{macAddress}/note", macAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.macAddress").value(macAddress));

        verify(pcStatusService).updateDeviceNote(macAddress, null);
    }
}
