package com.example.frontend.service;

import com.example.dto.dashboard.ClassroomSummaryDto;
import com.example.frontend.model.panel.PanelInfo;
import javafx.scene.layout.VBox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class DashboardPanelManager {
    // 동기화된 리스트: 멀티 스레드 접근 안전
    private final List<PanelInfo> panels = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong idGenerator = new AtomicLong(1);

    public List<PanelInfo> getPanels() {
        return panels;
    }

    public PanelInfo addPanel(PanelInfo.PanelType type, VBox panel) {
        long id = idGenerator.getAndIncrement();
        PanelInfo info = new PanelInfo(id, type, panel);
        panels.add(info);
        return info;
    }

    /** 명시적 id를 사용하는 오버로드 (강의실 패널처럼 API id를 panel id로 사용할 때). */
    public PanelInfo addPanel(long id, PanelInfo.PanelType type, VBox panel) {
        PanelInfo info = new PanelInfo(id, type, panel);
        panels.add(info);
        return info;
    }

    public void removePanel(long id) {
        panels.removeIf(p -> p.getId() == id);
    }

    public void clearPanels() {
        panels.clear();
    }

    // 기존 강의실 패널 일괄 갱신 (호환성 유지)
    public void updatePanels(List<ClassroomSummaryDto> classrooms) {
        clearPanels();
        for (ClassroomSummaryDto dto : classrooms) {
            VBox panel = new VBox();
            // ...패널 UI 구성 코드...
            addPanel(PanelInfo.PanelType.CLASSROOM, panel);
        }
    }
}
