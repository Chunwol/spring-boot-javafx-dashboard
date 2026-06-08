package com.example.frontend.controller.tab;

import com.example.frontend.controller.dashboard.DashboardManagementModule;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 설정 탭 — 좌측 sub-내비로 IP 대역 / 교사 PC / 시스템 섹션 전환.
 * 에이전트·고장PC·보고서·운영매트릭은 다른 탭으로 이동됨.
 */
public class SettingsTabController {

    @FXML private StackPane contentSlot;

    private final DashboardManagementModule mgmtModule;

    private final Map<String, Supplier<Node>> factories = new HashMap<>();
    private final Map<String, Node> cache = new HashMap<>();
    private String currentSectionId = "ip-range";

    public SettingsTabController(DashboardManagementModule mgmtModule) {
        this.mgmtModule = mgmtModule;
    }

    public Node createView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/settings-tab.fxml"));
            loader.setController(this);
            Node root = loader.load();

            factories.put("ip-range", mgmtModule::createIpRangeView);
            factories.put("teacher",  mgmtModule::createTeacherView);
            factories.put("system",   mgmtModule::createSystemView);

            select("ip-range");
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("설정 탭 로딩 실패", e);
        }
    }

    /** 외부에서 sub-내비 항목 선택. */
    public void selectSub(String id) { select(id); }

    private void select(String id) {
        currentSectionId = id;
        Node node = cache.computeIfAbsent(id, k -> factories.get(k).get());
        contentSlot.getChildren().setAll(node);
    }

    public void refreshCurrentSection() {
        switch (currentSectionId) {
            case "teacher" -> mgmtModule.refreshTeacherData();
            case "ip-range" -> mgmtModule.refreshIpRangeData();
            default -> {
            }
        }
    }
}
