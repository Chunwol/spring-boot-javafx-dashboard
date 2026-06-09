package com.example.frontend.controller.dashboard;

import com.example.dto.dashboard.ClassroomSummaryDto;

import java.util.List;
import java.util.Objects;

/**
 * 상단 상태바 요약 텍스트/상태 상수를 제공한다.
 */
public final class DashboardStatusSummarySupport {

    public static final String CONNECTED_LABEL = "연결됨";
    public static final String DISCONNECTED_LABEL = "연결 끊김";
    public static final String SESSION_EXPIRED_LABEL = "세션 만료";
    public static final String CONNECTED_COLOR_HEX = "#86efac";
    public static final String ERROR_COLOR_HEX = "#fca5a5";

    private DashboardStatusSummarySupport() {
    }

    public static String formatTopSummary(List<ClassroomSummaryDto> classrooms) {
        List<ClassroomSummaryDto> safeClassrooms = classrooms == null ? List.of() : classrooms;
        int classroomCount = safeClassrooms.size();
        long online = safeClassrooms.stream()
                .filter(Objects::nonNull)
                .mapToLong(ClassroomSummaryDto::getOnlinePcs)
                .sum();
        long total = safeClassrooms.stream()
                .filter(Objects::nonNull)
                .mapToLong(ClassroomSummaryDto::getTotalPcs)
                .sum();
        return "강의실 " + classroomCount + ", 전체/온라인 " + total + "/" + online;
    }
}
