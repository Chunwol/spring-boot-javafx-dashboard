package com.example.frontend.controller.panel;

import com.example.dto.dashboard.ClassroomSummaryDto;
import javafx.scene.layout.VBox;

public class ClassroomPanelController {
    private final VBox panel;
    private final ClassroomSummaryDto summary;

    public ClassroomPanelController(VBox panel, ClassroomSummaryDto summary) {
        this.panel = panel;
        this.summary = summary;
    }

    public VBox getPanel() {
        return panel;
    }

    // 패널 초기화 및 UI 업데이트
    public void updatePanel() {
        // ... 패널 UI 업데이트 로직 ...
    }

    // 기타 강의실 패널 관련 메서드 분리 예정
}
