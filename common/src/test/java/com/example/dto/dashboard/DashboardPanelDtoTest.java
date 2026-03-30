package com.example.dto.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DashboardPanelDtoTest {

    @Test
    void settersAndGetters() {
        DashboardPanelDto dto = new DashboardPanelDto();
        dto.setId(1L);
        dto.setDashboardId(10L);
        dto.setPanelType("CLASSROOM");
        dto.setRefGroupId(5L);
        dto.setConfigJson("{\"key\":\"val\"}");
        dto.setX(100.0);
        dto.setY(200.0);
        dto.setWidth(400.0);
        dto.setHeight(300.0);
        dto.setZIndex(3);
        dto.setVisible(true);
        dto.setPanelName("테스트 패널");

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getDashboardId()).isEqualTo(10L);
        assertThat(dto.getPanelType()).isEqualTo("CLASSROOM");
        assertThat(dto.getRefGroupId()).isEqualTo(5L);
        assertThat(dto.getConfigJson()).isEqualTo("{\"key\":\"val\"}");
        assertThat(dto.getX()).isEqualTo(100.0);
        assertThat(dto.getY()).isEqualTo(200.0);
        assertThat(dto.getWidth()).isEqualTo(400.0);
        assertThat(dto.getHeight()).isEqualTo(300.0);
        assertThat(dto.getZIndex()).isEqualTo(3);
        assertThat(dto.isVisible()).isTrue();
        assertThat(dto.getPanelName()).isEqualTo("테스트 패널");
    }

    @Test
    void defaultValues() {
        DashboardPanelDto dto = new DashboardPanelDto();
        assertThat(dto.getId()).isNull();
        assertThat(dto.getPanelType()).isNull();
        assertThat(dto.isVisible()).isFalse();
    }
}
