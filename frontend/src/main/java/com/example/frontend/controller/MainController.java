package com.example.frontend.controller;

import com.example.frontend.controller.dashboard.DashboardAlertApiClient;
import com.example.frontend.controller.dashboard.DashboardAlertPopupBuilder;
import com.example.frontend.controller.dashboard.DashboardManagementModule;
import com.example.frontend.controller.dashboard.DashboardPcManagementModule;
import com.example.frontend.controller.panel.CommandStatusPanelController;
import com.example.frontend.controller.panel.NetworkTabController;
import com.example.frontend.controller.tab.ClassroomStatusController;
import com.example.frontend.model.dashboard.DashboardAlertItem;
import com.example.frontend.service.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.stage.PopupWindow;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 신규 메인 화면 컨트롤러 (좌측 사이드바 메뉴 + 상단 헤더 + 탭 컨텐츠).
 *
 * <h2>주요 책임</h2>
 * <ul>
 *   <li>좌측 메뉴 항목 클릭 → 해당 탭 열기 (이미 열려 있으면 활성화만)</li>
 *   <li>탭은 닫기(X) 가능, 좌우 화살표로 스크롤</li>
 *   <li>메뉴 검색 → 메뉴 항목 필터링</li>
 * </ul>
 *
 * <p>구 DashboardController는 controller/legacy 패키지로 격리되어 더 이상 사용되지 않음.</p>
 */
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private javafx.scene.layout.HBox mainHeader;
    @FXML private javafx.scene.layout.VBox mainSidebar;
    @FXML private Label appTitleLabel;
    @FXML private Button alertsButton;
    @FXML private Label alertsBadgeLabel;
    @FXML private Button settingsButton;
    @FXML private TextField globalSearchField;
    @FXML private Button userMenuButton;

    @FXML private Label sidebarUserLabel;
    @FXML private TextField menuSearchField;
    @FXML private VBox menuContainer;

    @FXML private TabPane mainTabPane;
    @FXML private VBox toastContainer;

    /** 전역 토스트 인스턴스 — 자식 탭 컨트롤러가 호출. Scene 그래프 기준 1개만 존재. */
    private static MainController INSTANCE;
    public static MainController getInstance() { return INSTANCE; }

    private final ObservableList<MenuItem> menuItems = FXCollections.observableArrayList();
    private final Map<String, Tab> openTabs = new LinkedHashMap<>();

    // 각 탭의 진입점 — Supplier<Node>로 추상화해 FXML/직접 생성을 통일.
    private final Map<String, Supplier<Node>> tabFactories = new LinkedHashMap<>();
    private final ApiClient apiClient = ApiClient.getInstance();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // 패널 컨트롤러 참조 (재선택 시 재사용 + shutdown 정리용)
    private NetworkTabController networkController;
    private CommandStatusPanelController commandController;
    private DashboardManagementModule settingsModule;
    private DashboardPcManagementModule pcManagementModule;
    private com.example.frontend.controller.tab.PcControlTabController pcControlController;
    private com.example.frontend.controller.tab.SettingsTabController settingsTabController;
    private ClassroomStatusController classroomStatusController;
    private com.example.frontend.controller.tab.TokenMgmtTabController tokenMgmtController;
    private com.example.frontend.controller.tab.SystemSettingsController systemSettingsController;

    // 알림
    private final DashboardAlertApiClient alertApi = new DashboardAlertApiClient(ApiClient.getInstance());
    private ContextMenu alertMenu;
    private List<DashboardAlertItem> currentAlerts = new ArrayList<>();
    private javafx.animation.Timeline alertTimer;

    @FXML
    public void initialize() {
        INSTANCE = this;
        defineMenu();
        defineTabFactories();
        renderMenu();
        wireSearch();
        wireTabSync();
        wireHeaderActions();

        // 시작 시 강의실 현황 탭 자동 오픈
        openMenu(findMenuById("classroom-status"));

        // 알림 초기 로드 + 60초 주기 갱신
        loadAlerts();
        alertTimer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(60), e -> loadAlerts()));
        alertTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        alertTimer.play();

        // Stage close 시 Timeline 중지 (Scene 부착 후 한 번만 등록)
        mainTabPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && newScene.getWindow() != null) {
                newScene.getWindow().setOnCloseRequest(ev -> shutdown());
            }
        });
    }

    /** 우하단 토스트 표시 (3.5초 후 자동 사라짐). 어느 탭에서든 호출 가능. */
    public void showToast(String message, boolean error) {
        if (toastContainer == null || message == null || message.isBlank()) return;
        Label toast = new Label(message);
        toast.getStyleClass().addAll("toast-message", error ? "toast-error" : "toast-info");
        toast.setMaxWidth(360);
        toast.setWrapText(true);
        toastContainer.getChildren().add(toast);

        // Fade in + 자동 제거 (3.5s 후 0.4s fade out)
        toast.setOpacity(0);
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(180), toast);
        fadeIn.setFromValue(0); fadeIn.setToValue(1); fadeIn.play();

        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(3500));
        delay.setOnFinished(e -> {
            javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(
                    javafx.util.Duration.millis(400), toast);
            fadeOut.setFromValue(1); fadeOut.setToValue(0);
            fadeOut.setOnFinished(x -> toastContainer.getChildren().remove(toast));
            fadeOut.play();
        });
        delay.play();
    }

    /** 사용자 아이콘 클릭 — 역할 표시 + 로그아웃 메뉴. */
    private void showUserMenu() {
        ContextMenu menu = new ContextMenu();
        String role = apiClient.getCurrentUserRole();
        javafx.scene.control.MenuItem roleItem = new javafx.scene.control.MenuItem(
                "역할: " + (role == null ? "-" : role));
        roleItem.setDisable(true);
        javafx.scene.control.MenuItem logout = new javafx.scene.control.MenuItem("로그아웃");
        logout.setOnAction(e -> handleLogout());
        menu.getItems().addAll(roleItem, new javafx.scene.control.SeparatorMenuItem(), logout);
        menu.show(userMenuButton, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private void handleLogout() {
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "로그아웃 하시겠습니까?",
                javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != javafx.scene.control.ButtonType.YES) return;
            shutdown();
            apiClient.setBearerToken(null);
            returnToLogin();
        });
    }

    /** 로그인 화면으로 복귀 (Stage Scene 교체). */
    private void returnToLogin() {
        try {
            javafx.stage.Stage stage = (javafx.stage.Stage) mainTabPane.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            javafx.scene.Parent root = loader.load();
            Object controller = loader.getController();
            // LoginController.setPrimaryStage 호출 (리플렉션 회피 — 직접 캐스팅)
            if (controller instanceof LoginController lc) {
                lc.setPrimaryStage(stage);
            }
            javafx.scene.Scene scene = new javafx.scene.Scene(root, 480, 540);
            scene.getStylesheets().add(getClass().getResource("/dashboard-theme.css").toExternalForm());
            stage.setTitle("스마트 강의실 대시보드 - 로그인");
            // 대시보드 진입 시 키워둔 최소 크기(1000×680)를 먼저 로그인 크기로 낮춰야
            // 이후 setWidth/Height가 적용된다. (min 제약이 더 크면 축소가 막힘)
            stage.setMinWidth(400);
            stage.setMinHeight(480);
            stage.setScene(scene);
            stage.setWidth(480);
            stage.setHeight(540);
            stage.centerOnScreen();
        } catch (Exception ex) {
            log.error("로그인 화면 복귀 실패", ex);
        }
    }

    /**
     * 헤더 + 사이드바 표시/숨김 토글 (강의실 현황 전체화면용).
     * @param hide true = 숨김(전체화면), false = 복원
     */
    public void setChromeHidden(boolean hide) {
        if (mainHeader != null) {
            mainHeader.setVisible(!hide);
            mainHeader.setManaged(!hide);
        }
        if (mainSidebar != null) {
            mainSidebar.setVisible(!hide);
            mainSidebar.setManaged(!hide);
        }
        if (mainTabPane != null) {
            mainTabPane.getStyleClass().remove("main-tabpane-chrome-hidden");
            if (hide) {
                mainTabPane.getStyleClass().add("main-tabpane-chrome-hidden");
            }
        }
    }

    /** Stage 종료/탭 모듈 해제 시 호출 — 리소스 정리. */
    public void shutdown() {
        if (alertTimer != null) {
            alertTimer.stop();
            alertTimer = null;
        }
        if (networkController != null) {
            try { networkController.shutdown(); } catch (Exception ignored) {}
            networkController = null;
        }
    }

    /**
     * 사이드바 메뉴 정의 — 아코디언 구조.
     * 부모를 클릭하면 펼침/접힘만 토글, 자식 클릭 시 해당 sub-id로 탭 열기.
     *
     * <p>sub-id 규칙: 부모 탭 컨트롤러가 sub-내비를 이미 갖고 있으므로
     * 사이드바에서 자식 클릭 → 같은 탭으로 진입 + 해당 sub-섹션 자동 선택.</p>
     */
    private void defineMenu() {
        menuItems.add(new MenuItem("classroom-status", "강의실 현황", "mdi2m-monitor"));

        MenuItem pcCtl = new MenuItem("pc-control", "PC 관리", "mdi2d-desktop-classic");
        pcCtl.add(new MenuItem("pc-control:pc-list", "PC 관제",          "mdi2v-view-list-outline"));
        pcCtl.add(new MenuItem("pc-control:command", "명령 발행 / 이력", "mdi2c-console-line"));
        pcCtl.add(new MenuItem("pc-control:token",   "토큰 관리",        "mdi2k-key-outline"));
        pcCtl.add(new MenuItem("pc-control:broken",  "고장 PC",          "mdi2a-alert-circle-outline"));
        menuItems.add(pcCtl);

        menuItems.add(new MenuItem("network", "네트워크", "mdi2l-lan"));
        menuItems.add(new MenuItem("report",  "보고서",   "mdi2f-file-chart"));

        MenuItem settings = new MenuItem("settings", "설정", "mdi2c-cog-outline");
        settings.add(new MenuItem("settings:ip-range", "강의실 IP 대역", "mdi2i-ip-network-outline"));
        settings.add(new MenuItem("settings:teacher",  "교사 PC 관리",   "mdi2a-account-tie-outline"));
        settings.add(new MenuItem("settings:system",   "시스템",         "mdi2c-cog-outline"));
        menuItems.add(settings);
    }

    /** 각 메뉴 ID → 탭 콘텐츠 생성기 매핑. 기존 컨트롤러를 재사용. */
    private void defineTabFactories() {
        tabFactories.put("classroom-status", () -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/classroom-status-tab.fxml"));
                Node node = loader.load();
                classroomStatusController = loader.getController();
                return node;
            } catch (Exception e) {
                log.error("강의실 현황 탭 로드 실패", e);
                return new Label("로드 실패: " + e.getMessage());
            }
        });
        tabFactories.put("network", () -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/network-tab.fxml"));
                Node node = loader.load();
                networkController = loader.getController();
                networkController.startAutoRefresh(15); // 15초 폴링 (강의실별 트래픽)
                return node;
            } catch (Exception e) {
                log.error("네트워크 탭 로드 실패", e);
                return new Label("로드 실패: " + e.getMessage());
            }
        });
        tabFactories.put("pc-control", () -> {
            try {
                if (pcControlController == null) {
                    pcControlController = new com.example.frontend.controller.tab.PcControlTabController(
                            objectMapper, apiClient.getBaseUrl(),
                            () -> mainTabPane.getScene());
                }
                return pcControlController.createView();
            } catch (Throwable e) {
                log.error("PC 제어 탭 로드 실패", e);
                javafx.scene.control.Label err = new javafx.scene.control.Label(
                        "PC 제어 탭 오류:\n" + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (e.getCause() != null ? "\n원인: " + e.getCause().getMessage() : ""));
                err.setWrapText(true);
                err.setStyle("-fx-padding: 20; -fx-text-fill: #b91c1c;");
                return err;
            }
        });
        tabFactories.put("settings", () -> {
            try {
                if (settingsModule == null) {
                    settingsModule = new DashboardManagementModule(
                            apiClient.getBaseUrl(),
                            () -> mainTabPane.getScene());
                }
                if (settingsTabController == null) {
                    settingsTabController = new com.example.frontend.controller.tab.SettingsTabController(settingsModule);
                }
                return settingsTabController.createView();
            } catch (Throwable e) {
                log.error("설정 탭 로드 실패", e);
                javafx.scene.control.Label err = new javafx.scene.control.Label(
                        "설정 탭 오류:\n" + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (e.getCause() != null ? "\n원인: " + e.getCause().getMessage() : ""));
                err.setWrapText(true);
                err.setStyle("-fx-padding: 20; -fx-text-fill: #b91c1c;");
                return err;
            }
        });
        tabFactories.put("report", () -> {
            try {
                initSettingsModule();
                return settingsModule.createReportView();
            } catch (Throwable e) {
                log.error("보고서 탭 로드 실패", e);
                javafx.scene.control.Label err = new javafx.scene.control.Label(
                        "보고서 탭 오류:\n" + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + (e.getCause() != null ? "\n원인: " + e.getCause().getMessage() : ""));
                err.setWrapText(true);
                err.setStyle("-fx-padding: 20; -fx-text-fill: #b91c1c;");
                return err;
            }
        });

        // ─── PC 관리 sub-tabs (각자 독립 탭으로 열림) ────────────────────────
        tabFactories.put("pc-control:pc-list", () -> {
            // FXML 루트가 이미 tab-content HBox이므로 inTabContent() 불필요
            if (pcManagementModule == null) pcManagementModule = new DashboardPcManagementModule(objectMapper);
            return pcManagementModule.createView();
        });
        tabFactories.put("pc-control:command", () -> {
            if (commandController == null) commandController = new CommandStatusPanelController();
            return inTabContent(commandController.getPanel());
        });
        tabFactories.put("pc-control:token", () ->
                inTabContent((tokenMgmtController = new com.example.frontend.controller.tab.TokenMgmtTabController()).createView()));
        tabFactories.put("pc-control:broken", () -> {
            initSettingsModule();
            return inTabContent(settingsModule.createBrokenPcView());
        });

        // ─── 설정 sub-tabs ───────────────────────────────────────────────────
        tabFactories.put("settings:ip-range", () -> {
            initSettingsModule();
            return inTabContent(settingsModule.createIpRangeView());
        });
        tabFactories.put("settings:teacher", () -> {
            initSettingsModule();
            return inTabContent(settingsModule.createTeacherView());
        });
        tabFactories.put("settings:system", () ->
                (systemSettingsController = new com.example.frontend.controller.tab.SystemSettingsController()).createView());
    }

    /** content를 tab-content StackPane으로 감싸기 — 외부 배경 + 16px 여백 부여. */
    private static javafx.scene.layout.StackPane inTabContent(Node content) {
        javafx.scene.layout.StackPane sp = new javafx.scene.layout.StackPane(content);
        sp.getStyleClass().add("tab-content");
        return sp;
    }

    /** settingsModule 지연 초기화 헬퍼. */
    private void initSettingsModule() {
        if (settingsModule == null)
            settingsModule = new DashboardManagementModule(apiClient.getBaseUrl(), () -> mainTabPane.getScene());
    }

    private Node loadFxml(String path) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(path));
            return loader.load();
        } catch (Exception e) {
            log.error("FXML 로드 실패: {}", path, e);
            Label err = new Label("로드 실패: " + path + "\n" + e.getMessage());
            err.setWrapText(true);
            return err;
        }
    }

    /** 관리자 전용 탭 id 목록 — VIEWER는 숨김. */
    private static final java.util.Set<String> ADMIN_ONLY = java.util.Set.of(
            "pc-control", "report", "settings",
            "pc-control:pc-list", "pc-control:command", "pc-control:token", "pc-control:broken",
            "settings:ip-range", "settings:teacher", "settings:system");

    /**
     * 메뉴 항목을 좌측 사이드바에 렌더 — 아코디언 구조.
     * 부모 + 펼침 시 자식 행도 출력. 검색어가 있으면 자식까지 일치 검색하고 자동 펼침.
     */
    private void renderMenu() {
        menuContainer.getChildren().clear();
        String query = menuSearchField.getText() == null ? "" : menuSearchField.getText().trim().toLowerCase();
        String activeSubId = currentActiveSubId();   // 예: "pc-control:command"
        String activeTabId = currentActiveMenuId();  // 예: "pc-control"
        boolean viewer = apiClient.isViewerRole();

        for (MenuItem item : menuItems) {
            if (viewer && ADMIN_ONLY.contains(item.id)) continue;

            // 검색어가 있으면 자기 또는 자식이 일치할 때만 출력
            boolean selfMatch = query.isEmpty() || item.label.toLowerCase().contains(query);
            boolean childMatch = item.children.stream()
                    .anyMatch(c -> c.label.toLowerCase().contains(query));
            if (!query.isEmpty() && !selfMatch && !childMatch) continue;

            boolean parentActive = item.id.equals(activeTabId);
            menuContainer.getChildren().add(buildMenuRow(item, parentActive));

            // 검색 중이면 자식 매치 발견 시 강제 펼침
            boolean expanded = item.isParent() && (item.expanded || (!query.isEmpty() && childMatch));
            if (expanded) {
                for (MenuItem child : item.children) {
                    if (!query.isEmpty() && !selfMatch && !child.label.toLowerCase().contains(query)) continue;
                    menuContainer.getChildren().add(
                            buildMenuChildRow(item, child, child.id.equals(activeSubId)));
                }
            }
        }
    }

    /** 현재 활성 sub-id (직접 탭 키가 "parent:child" 형태면 그대로, 아니면 null). */
    private String currentActiveSubId() {
        Tab sel = mainTabPane == null ? null : mainTabPane.getSelectionModel().getSelectedItem();
        if (sel == null) return null;
        for (Map.Entry<String, Tab> e : openTabs.entrySet()) {
            if (e.getValue() == sel && e.getKey().contains(":")) return e.getKey();
        }
        return null;
    }

    /** 현재 선택된 탭에 해당하는 최상위 메뉴 id (사이드바 부모 행 활성 표시용). */
    private String currentActiveMenuId() {
        Tab sel = mainTabPane == null ? null : mainTabPane.getSelectionModel().getSelectedItem();
        if (sel == null) return null;
        for (Map.Entry<String, Tab> e : openTabs.entrySet()) {
            if (e.getValue() == sel) {
                String id = e.getKey();
                int colon = id.indexOf(':');
                return colon > 0 ? id.substring(0, colon) : id;
            }
        }
        return null;
    }

    /**
     * 메뉴 행 1개. 부모면 펼침/접힘 토글 + 캐럿 아이콘. leaf면 클릭 시 탭 열기.
     */
    private HBox buildMenuRow(MenuItem item, boolean active) {
        HBox row = new HBox(10);
        row.getStyleClass().add("sidebar-menu-row");
        if (active) row.getStyleClass().add("sidebar-menu-row-active");
        row.setPadding(new javafx.geometry.Insets(9, 10, 9, 10));
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        FontIcon icon = new FontIcon(item.iconLiteral);
        icon.setIconSize(16);
        icon.getStyleClass().add("sidebar-menu-icon");
        Label label = new Label(item.label);
        label.getStyleClass().add("sidebar-menu-label");

        row.getChildren().addAll(icon, label);

        if (item.isParent()) {
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            FontIcon caret = new FontIcon(item.expanded ? "mdi2c-chevron-up" : "mdi2c-chevron-down");
            caret.setIconSize(14);
            caret.getStyleClass().add("sidebar-menu-caret");
            row.getChildren().addAll(spacer, caret);
            row.setOnMouseClicked(e -> { item.expanded = !item.expanded; renderMenu(); });
        } else {
            row.setOnMouseClicked(this::handleMenuRowClick);
            row.getProperties().put("menuId", item.id);
        }
        return row;
    }

    /**
     * 자식 메뉴 행 (들여쓰기 + 들여쓴 텍스트). 클릭 시 부모 탭 열고 sub-id로 sub-내비 전환.
     */
    private HBox buildMenuChildRow(MenuItem parent, MenuItem child, boolean active) {
        HBox row = new HBox(6);
        row.getStyleClass().add("sidebar-submenu-row");
        if (active) row.getStyleClass().add("sidebar-submenu-row-active");
        row.setPadding(new javafx.geometry.Insets(6, 10, 6, 36));   // 들여쓰기
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label label = new Label(child.label);
        label.getStyleClass().add("sidebar-submenu-label");
        row.getChildren().add(label);
        row.setOnMouseClicked(e -> openSubMenu(parent, child));
        return row;
    }

    /** sub-id를 독립 탭으로 직접 열기. 이미 열려 있으면 활성화만. */
    private void openSubMenu(MenuItem parent, MenuItem child) {
        if (child == null) return;
        String tabId = child.id;
        Tab existing = openTabs.get(tabId);
        if (existing != null) {
            boolean alreadySelected = mainTabPane.getSelectionModel().getSelectedItem() == existing;
            mainTabPane.getSelectionModel().select(existing);
            if (alreadySelected) refreshSelectedTab(existing);
            renderMenu();
            return;
        }
        Supplier<Node> factory = tabFactories.get(tabId);
        if (factory == null) { log.warn("sub-탭 팩토리 미정의: {}", tabId); return; }
        try {
            Node content = factory.get();
            Tab tab = new Tab(child.label, content);
            tab.setClosable(true);
            tab.setOnClosed(e -> {
                openTabs.remove(tabId);
                cleanupClosedTab(tabId);
                renderMenu();
            });
            openTabs.put(tabId, tab);
            mainTabPane.getTabs().add(tab);
            mainTabPane.getSelectionModel().select(tab);
        } catch (Throwable ex) {
            log.error("sub-탭 로드 실패: {}", tabId, ex);
        }
        renderMenu();
    }

    private void handleMenuRowClick(MouseEvent e) {
        Node src = (Node) e.getSource();
        Object id = src.getProperties().get("menuId");
        if (id instanceof String s) openMenu(findMenuById(s));
    }

    /** 메뉴 항목을 탭으로 열기 (이미 열려있으면 활성화만). */
    private void openMenu(MenuItem item) {
        if (item == null) return;
        Tab existing = openTabs.get(item.id);
        if (existing != null) {
            boolean alreadySelected = mainTabPane.getSelectionModel().getSelectedItem() == existing;
            mainTabPane.getSelectionModel().select(existing);
            if (alreadySelected) refreshSelectedTab(existing);
            return;
        }
        Supplier<Node> factory = tabFactories.get(item.id);
        if (factory == null) {
            log.warn("탭 팩토리 미정의: {}", item.id);
            return;
        }
        try {
            Node content = factory.get();
            Tab tab = new Tab(item.label, content);
            tab.setClosable(true);
            tab.setOnClosed(e -> {
                openTabs.remove(item.id);
                cleanupClosedTab(item.id);
                renderMenu(); // 사이드바 활성 표시 갱신
            });
            openTabs.put(item.id, tab);
            mainTabPane.getTabs().add(tab);
            mainTabPane.getSelectionModel().select(tab);
            renderMenu(); // 사이드바 활성 표시 갱신
        } catch (Throwable ex) {
            log.error("탭 로딩 실패: {}", item.label, ex);
            // 예외가 발생해도 에러 탭으로 열어 사용자가 확인 가능하게
            try {
                javafx.scene.control.Label err = new javafx.scene.control.Label(
                        item.label + " 탭 오류:\n" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                err.setWrapText(true);
                err.setStyle("-fx-padding: 20; -fx-text-fill: #b91c1c;");
                Tab errTab = new Tab(item.label + " (오류)", err);
                errTab.setClosable(true);
                openTabs.put(item.id, errTab);
                mainTabPane.getTabs().add(errTab);
                mainTabPane.getSelectionModel().select(errTab);
            } catch (Exception ignore) {}
        }
    }

    private MenuItem findMenuById(String id) {
        for (MenuItem item : menuItems) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private void wireSearch() {
        menuSearchField.textProperty().addListener((obs, old, val) -> renderMenu());
        // globalSearchField는 향후 Phase 2 (강의실/PC 통합 검색)
    }

    private void wireTabSync() {
        // 탭 전환 시 사이드바 활성 표시 동기화
        mainTabPane.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            renderMenu();
            if (nv != null) refreshSelectedTab(nv);
        });
    }

    private void refreshSelectedTab(Tab tab) {
        String tabId = resolveOpenTabId(tab);
        if (tabId == null) return;
        switch (tabId) {
            case "classroom-status" -> {
                if (classroomStatusController != null) classroomStatusController.reloadData();
            }
            case "network" -> {
                if (networkController != null) networkController.refreshData();
            }
            case "report" -> {
                initSettingsModule();
                settingsModule.refreshReportData();
            }
            case "pc-control" -> {
                if (pcControlController != null) pcControlController.refreshCurrentSection();
            }
            case "pc-control:pc-list" -> {
                if (pcManagementModule != null) pcManagementModule.reloadData();
            }
            case "pc-control:command" -> {
                if (commandController != null) commandController.refresh();
            }
            case "pc-control:token" -> {
                if (tokenMgmtController != null) tokenMgmtController.reloadData();
            }
            case "pc-control:broken" -> {
                initSettingsModule();
                settingsModule.refreshBrokenPcData();
            }
            case "settings" -> {
                if (settingsTabController != null) settingsTabController.refreshCurrentSection();
            }
            case "settings:ip-range" -> {
                initSettingsModule();
                settingsModule.refreshIpRangeData();
            }
            case "settings:teacher" -> {
                initSettingsModule();
                settingsModule.refreshTeacherData();
            }
            case "settings:system" -> {
                if (systemSettingsController != null) systemSettingsController.reloadData();
            }
            default -> {
            }
        }
    }

    private String resolveOpenTabId(Tab tab) {
        if (tab == null) return null;
        for (Map.Entry<String, Tab> entry : openTabs.entrySet()) {
            if (entry.getValue() == tab) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void cleanupClosedTab(String tabId) {
        switch (tabId) {
            case "classroom-status" -> classroomStatusController = null;
            case "network" -> {
                if (networkController != null) networkController.shutdown();
                networkController = null;
            }
            case "pc-control" -> {
                if (pcControlController != null) pcControlController.shutdown();
                pcControlController = null;
            }
            case "pc-control:command" -> {
                if (commandController != null) commandController.shutdown();
                commandController = null;
            }
            case "pc-control:pc-list" -> pcManagementModule = null;
            case "pc-control:token" -> tokenMgmtController = null;
            case "settings" -> settingsTabController = null;
            case "settings:system" -> systemSettingsController = null;
            default -> {
            }
        }

        if (!hasOpenTabUsingSettingsModule()) {
            settingsModule = null;
        }
    }

    private boolean hasOpenTabUsingSettingsModule() {
        return openTabs.keySet().stream().anyMatch(tabId -> switch (tabId) {
            case "settings", "report", "pc-control:broken", "settings:ip-range", "settings:teacher" -> true;
            default -> false;
        });
    }

    private void wireHeaderActions() {
        settingsButton.setOnAction(e -> openMenu(findMenuById("settings")));
        alertsButton.setOnAction(e -> toggleAlertPopup());
        userMenuButton.setOnAction(e -> showUserMenu());

        // 글로벌 검색 — 입력하면 강의실 현황 탭을 열고 그 검색에 위임
        globalSearchField.textProperty().addListener((o, ov, nv) -> {
            if (nv == null || nv.isBlank()) {
                if (classroomStatusController != null) classroomStatusController.setSearchText("");
                return;
            }
            // 강의실 탭이 열려 있지 않으면 자동 오픈
            if (classroomStatusController == null) openMenu(findMenuById("classroom-status"));
            if (classroomStatusController != null) classroomStatusController.setSearchText(nv);
        });

        // 사용자 역할 표시 (ADMIN/USER/VIEWER → 관리자/사용자/조회자)
        String role = apiClient.getCurrentUserRole();
        if (role != null) {
            sidebarUserLabel.setText(switch (role.toUpperCase()) {
                case "ADMIN" -> "관리자";
                case "VIEWER" -> "조회자";
                case "USER" -> "사용자";
                default -> role;
            });
        }
    }

    // ─── 알림 ──────────────────────────────────────────────────────────────

    private void loadAlerts() {
        alertApi.fetchAlertSnapshot(200).thenAccept(items -> Platform.runLater(() -> {
            currentAlerts = items != null ? items : List.of();
            updateAlertBadge();
        })).exceptionally(ex -> {
            log.debug("알림 조회 실패: {}", ex.getMessage());
            return null;
        });
    }

    private void updateAlertBadge() {
        long unread = DashboardAlertApiClient.filterVisibleAlerts(currentAlerts).stream()
                .filter(a -> a != null && !a.isAcknowledged())
                .count();
        if (unread > 0) {
            alertsBadgeLabel.setText(unread > 99 ? "99+" : String.valueOf(unread));
            alertsBadgeLabel.setVisible(true);
            alertsBadgeLabel.setManaged(true);
        } else {
            alertsBadgeLabel.setVisible(false);
            alertsBadgeLabel.setManaged(false);
        }
    }

    private void toggleAlertPopup() {
        if (alertMenu != null && alertMenu.isShowing()) {
            alertMenu.hide();
            return;
        }
        alertMenu = new ContextMenu();
        alertMenu.setAutoHide(true);
        alertMenu.setHideOnEscape(true);
        alertMenu.setAnchorLocation(PopupWindow.AnchorLocation.CONTENT_TOP_RIGHT);

        CustomMenuItem rootItem = DashboardAlertPopupBuilder.buildRootMenuItem(
                DashboardAlertApiClient.filterVisibleAlerts(currentAlerts),
                ApiClient.getInstance().isViewerRole(),
                this::loadAlerts,
                () -> alertApi.acknowledgeAllAlerts().thenRun(this::loadAlerts),
                item -> alertApi.acknowledgeAlert(item.getId()).thenRun(this::loadAlerts)
        );
        alertMenu.getItems().add(rootItem);
        alertMenu.show(alertsButton, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    /** 사이드바 메뉴 정의 한 줄. */
    /** 사이드바 메뉴 항목. children 있으면 아코디언 부모 (탭 자체는 열지 않음, sub-id로 탭 식별). */
    private static final class MenuItem {
        final String id;
        final String label;
        final String iconLiteral;
        final java.util.List<MenuItem> children = new java.util.ArrayList<>();
        /** 부모일 때 펼침 여부 (UI 상태). */
        boolean expanded = false;
        MenuItem(String id, String label, String iconLiteral) {
            this.id = id; this.label = label; this.iconLiteral = iconLiteral;
        }
        MenuItem add(MenuItem child) { children.add(child); return this; }
        boolean isParent() { return !children.isEmpty(); }
    }
}
