package com.example.frontend.controller.tab;

import com.example.frontend.controller.dashboard.DashboardManagementModule;
import com.example.frontend.controller.dashboard.DashboardPcManagementModule;
import com.example.frontend.controller.panel.CommandStatusPanelController;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PC 제어 탭 — PC관리 + 명령 + 에이전트 + 고장PC 를 좌측 sub-내비로 통합.
 *
 * <p>각 sub-섹션은 클릭 시 lazy 로드되어 콘텐츠 슬롯으로 교체. 컨트롤러 인스턴스는 캐시.</p>
 */
public class PcControlTabController {

    private static final Logger log = LoggerFactory.getLogger(PcControlTabController.class);

    @FXML private StackPane contentSlot;

    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;
    private final java.util.function.Supplier<javafx.scene.Scene> sceneSupplier;

    /** Sub-섹션 → 콘텐츠 생성기 (lazy). */
    private final Map<String, Supplier<Node>> factories = new HashMap<>();
    /** Sub-섹션 → 캐시된 노드. */
    private final Map<String, Node> cache = new HashMap<>();
    private String currentSectionId = "pc-list";

    // 재사용 인스턴스
    private DashboardPcManagementModule pcModule;
    private CommandStatusPanelController commandController;
    private DashboardManagementModule mgmtModule; // buildBroken에서 사용
    private TokenMgmtTabController tokenController;

    public PcControlTabController(ObjectMapper objectMapper,
                                  String apiBaseUrl,
                                  java.util.function.Supplier<javafx.scene.Scene> sceneSupplier) {
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl;
        this.sceneSupplier = sceneSupplier;
    }

    public Node createView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/pc-control-tab.fxml"));
            loader.setController(this);
            Node root = loader.load();
            // 팩토리 등록
            factories.put("pc-list",  this::buildPcList);
            factories.put("command",  this::buildCommand);
            factories.put("token",    this::buildToken);
            factories.put("broken",   this::buildBroken);

            // 기본 선택
            select("pc-list");
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("PC 제어 탭 로딩 실패", e);
        }
    }

    /** 외부(MainController 사이드바)에서 sub-내비 항목 선택. */
    public void selectSub(String id) { select(id); }

    private void select(String id) {
        currentSectionId = id;
        Node node = cache.computeIfAbsent(id, k -> factories.get(k).get());
        contentSlot.getChildren().setAll(node);
    }

    public void refreshCurrentSection() {
        switch (currentSectionId) {
            case "command" -> {
                if (commandController != null) {
                    commandController.refresh();
                }
            }
            case "token" -> {
                if (tokenController != null) {
                    tokenController.reloadData();
                }
            }
            case "broken" -> {
                if (mgmtModule != null) {
                    mgmtModule.refreshBrokenPcData();
                }
            }
            case "pc-list" -> {
                if (pcModule != null) {
                    pcModule.reloadData();
                }
            }
            default -> {
                if (pcModule != null) {
                    pcModule.reloadData();
                }
            }
        }
    }

    private Node buildPcList() {
        if (pcModule == null) pcModule = new DashboardPcManagementModule(objectMapper);
        return pcModule.createView();
    }

    private Node buildCommand() {
        if (commandController == null) commandController = new CommandStatusPanelController();
        return commandController.getPanel();
    }

    private Node buildBroken() {
        if (mgmtModule == null) mgmtModule = new DashboardManagementModule(apiBaseUrl, sceneSupplier);
        return mgmtModule.createBrokenPcView();
    }

    private Node buildToken() {
        if (tokenController == null) tokenController = new TokenMgmtTabController();
        return tokenController.createView();
    }

    public void shutdown() {
        // 각 모듈에 shutdown이 있으면 호출
        try {
            if (commandController != null) commandController.shutdown();
        } catch (Exception ignored) {}
    }
}
