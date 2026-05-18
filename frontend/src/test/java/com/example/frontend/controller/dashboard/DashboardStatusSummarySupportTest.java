package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardStatusSummarySupportTest {

    @Test
    void formatTopSummary_shouldAggregateClassroomAndPcCounts() {
        ClassroomSummaryDto roomA = new ClassroomSummaryDto();
        roomA.setOnlinePcs(7);
        roomA.setTotalPcs(10);

        ClassroomSummaryDto roomB = new ClassroomSummaryDto();
        roomB.setOnlinePcs(5);
        roomB.setTotalPcs(8);

        String text = DashboardStatusSummarySupport.formatTopSummary(List.of(roomA, roomB));

        assertThat(text).isEqualTo("강의실 2, 온라인 12/18");
    }

    @Test
    void formatTopSummary_whenNull_shouldReturnZeroSummary() {
        assertThat(DashboardStatusSummarySupport.formatTopSummary(null))
                .isEqualTo("강의실 0, 온라인 0/0");
    }
}
