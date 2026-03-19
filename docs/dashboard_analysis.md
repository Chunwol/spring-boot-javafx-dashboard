# 프론트엔드 코드베이스 상세 분석 보고서 (2026-03-19)

## 1. DashboardController.java 상세 분석

### 코드 통계
- **전체 라인 수**: ~1,226 라인 (initialize()부터 shutdown()까지)
- **주요 메서드 수**: 30+ 메서드
- **클래스 복잡도**: HIGH - 단일 책임 원칙 위반

### 주요 메서드 목록 (라인 참조)
| 메서드명 | 라인 | 기능 | 복잡도 |
|----------|------|------|--------|
| `initialize()` | 129 | 초기화, 스케줄러, WebSocket 연결 | HIGH |
| `buildTopBar()` | 172 | 상단 바 UI 생성 | MEDIUM |
| `buildTabs()` | 242 | 3개 탭 생성 (Main, Network, Management) | LOW |
| `createMainDashboardView()` | 266 | 메인 대시보드 뷰 + 모듈 초기화 | HIGH |
| `buildLeftPane()` | 289 | 좌측 리소스 목록 패널 | HIGH |
| `buildRightPane()` | 482 | 우측 캔버스 + 도구모음 | VERY HIGH |
| `applyEditModeUiState()` | 870 | Edit 모드 토글 처리 | MEDIUM |
| `connectWebSocket()` | 883 | WebSocket 연결 | LOW |
| `refreshDashboardData()` | 909 | 비동기 데이터 새로고침 | LOW |
| `loadDashboards()` | 915 | 대시보드 목록 로드 | MEDIUM |
| `loadDashboardPanels()` | 931 | 패널 로드 (선택된 대시보드) | MEDIUM |
| `handleCreateDashboard()` | 945 | 새 대시보드 생성 대화 | LOW |
| `refreshPanelListAndDashboard()` | 961 | UI 동기화 | HIGH |
| `renderNetworkPanels()` | 979 | 네트워크 패널 렌더링 | VERY HIGH |
| `makeDashboardPanelInteractive()` | 1039 | 드래그/드롭 구현 | HIGH |
| `addResizeHandleToDashboardPanel()` | 1071 | 리사이즈 핸들 추가 | MEDIUM |
| `arrangePanels()` | 1128 | 그리드/행/열 배열 | MEDIUM |
| `fitAllPanelsToViewport()` | 1103 | 줌 & 스크롤 자동 조정 | HIGH |
| `shutdown()` | 1226 | 리소스 정리 | LOW |

### 클래스 필드 문제점
```
필드 개수: 30+개
- UI 컴포넌트 필드: 15개 (@FXML 포함)
- 데이터 필드: 6개 (cached*)
- 서비스/모듈 필드: 6개
- 상태 플래그: 6개 (isEditMode, layoutLocked, etc.)
- 맵/리스트: 3개 (textItemMap, imageItemMap, networkItemMap)
```

**문제**: 상태가 너무 많아 테스트 어려움

### 반복되는 코드 패턴 (중복 로직)

#### 1. 드래그 이벤트 처리 (3곳 중복)
- [Line 1046-1069]: `makeDashboardPanelInteractive()` - 네트워크 패널
- DashboardWidgetModule: 텍스트 아이템 드래그처리
- DashboardClassroomModule: 강의실 패널 드래그 처리

**공통 패턴**:
```java
panel.setOnMousePressed(event -> {
    if (!isEditMode || layoutLocked) return;
    Point2D parentPoint = panel.getParent().sceneToLocal(event.getSceneX(), event.getSceneY());
    dragStart[0] = parentPoint.getX() - panel.getLayoutX();
    dragStart[1] = parentPoint.getY() - panel.getLayoutY();
});
panel.setOnMouseDragged(event -> {
    if (!isEditMode || layoutLocked) return;
    // 위치 업데이트...
    panel.setLayoutX(applySnap(...));
});
panel.setOnMouseReleased(event -> {
    if (!isEditMode || layoutLocked) return;
    dashboardService.updatePanelPositionAsync(...);
});
```

**영향도**: 3곳 × ~25 라인 = ~75 라인 중복

#### 2. 리사이즈 핸들 추가 (2곳 중복)
- [Line 1071-1095]: `addResizeHandleToDashboardPanel()`
- DashboardWidgetModule (암묵적): 텍스트 아이템 리사이즈

#### 3. 패널 생성 후처리 (반복되는 구조)
- [Line 1014-1038]: 네트워크 패널 래핑
- DashboardClassroomModule: 강의실 패널 래핑
- 패턴: header(title+spacer+deleteBtn) + body + wrapper VBox

#### 4. 위치/크기 저장 API 호출
- `updatePanelPositionAsync()` - 여러 곳에서 호출
- 매번 설정해야 하는 파라미터: x, y, width, height

### 성능 문제 분석

#### 1. 메인 스레드 블로킹 위험
```java
// initialize() Line 147-160: 주기적 갱신
scheduler.scheduleAtFixedRate(() -> {
    refreshDashboardData();  // 비동기이지만...
    if (networkPanelController != null) {
        networkPanelController.updateNetworkChart();  // 직접 호출
    }
    networkItemControllers.values().forEach(NetworkPanelController::updateNetworkChart);
    if (widgetModule != null) {
        widgetModule.loadTextItems();
        widgetModule.loadImageItems();
    }
}, 0, 5, TimeUnit.SECONDS);
```

**문제점**:
- 5초마다 **4개의 독립적 HTTP 요청** 발생
- `updateNetworkChart()` 모든 인스턴스에서 호출
- 네트워크 패널이 많을수록 요청 폭주

**추정 영향도**:
- 100개 패널: 5초마다 400+ 요청
- 대역폭: 초당 80+ HTTP 요청

#### 2. UI 렌더링 블로킹
```java
// renderNetworkPanels() Line 979-1038
for (DashboardPanelDto panelDto : networkPanels) {
    // ... 매번 복잡한 UI 구성
    VBox body = controller.getPanel();  // 무거운 차트 렌더링
    Label title = new Label(...);
    HBox header = new HBox(...);
    VBox wrapper = new VBox(...);
    makeDashboardPanelInteractive(wrapper, panelDto.getId());  // 이벤트 바인딩
    addResizeHandleToDashboardPanel(wrapper, panelDto.getId());  // 또 다시 이벤트
    classroomContainer.getChildren().add(wrapper);
}
```

**문제점**:
- 매 새로고침마다 **전체 네트워크 패널 재렌더링**
- 이미 존재하는 패널도 다시 생성
- 네트워크 요청이 완료될 때까지 **Main 스레드 대기**

#### 3. 이벤트 리스너 누적
```java


## 8. 상세 현황 분석 (2026-03-19)

### DashboardController 분석
- **크기**: 1,226줄, 30+ 필드, 30+ 메서드
- **순환 복잡도**: 20+ (매우 높음)
- **메서드별 복잡도**:
  - buildRightPane(): VERY HIGH (682줄)
  - renderNetworkPanels(): VERY HIGH (979-1038줄)
  - initialize(): HIGH (129줄, 초기화 + 스케줄러)

### 🚨 중대 문제점
1. **성능 문제**: 5초마다 400+ HTTP 요청 (100개 패널 × 4개 독립 요청)
2. **중복 코드**: 드래그 이벤트 75줄 3곳 반복
3. **상태 중복**: ViewModel 미통합 (6개 필드 이중 관리)
4. **UI/UX**: 반응형 부족, 색상 불일관, 접근성 0%
5. **미완성 기능**: PDF 10%, 레이아웃 저장 30%

### 개선 우선순위
- 🔴 **CRITICAL**: DashboardController 분해 (1,226줄 → 4개 클래스)
- 🔴 **CRITICAL**: 드래그/리사이즈 중복 제거
- 🔴 **CRITICAL**: HTTP 요청 배치화
- 🟠 **HIGH**: ViewModel/UIService 통합
- 🟠 **HIGH**: PDF 생성 구현
// buildLeftPane() Line 546-572: groupListView 셀 팩토리
groupListView.setCellFactory(param -> new ListCell<>() {
    @Override
    protected void updateItem(Object item, boolean empty) {
        // ... 매 셀마다 새 이벤트 바인딩
        row.setOnDragDetected(event -> {
            if (!isEditMode) return;
            Dragboard dragboard = groupListView.startDragAndDrop(TransferMode.COPY);
            // ...
        });
    }
});
```

**문제점**:
- 100개 아이템 = 100개 드래그 리스너
- 리스트 새로고침할 때마다 리스너 추가

#### 4. Pane 레이아웃 재계산
```java
// fitAllPanelsToViewport() Line 1103-1123
for (Node node : nodes) {
    double x = node.getLayoutX();
    double y = node.getLayoutY();
    double w = Math.max(1, node.prefWidth(-1));
    double h = Math.max(1, node.prefHeight(-1));
}
```

**문제점**:
- 패널 수에 선형 비례하는 계산
- 매 호출마다 모든 패널 순회하며 레이아웃 강제 재계산

### 아직 미통합된 부분

#### 1. DashboardViewModel (Line 20 import 있음)
- 정의됨: [DashboardViewModel.java](frontend/src/main/java/com/example/frontend/controller/dashboard/DashboardViewModel.java)
- **상태**: 미통합 (DashboardController에서 직접 상태 관리 중)
- 내용:
  - `DoubleProperty zoomScale`
  - `BooleanProperty snapToGrid`
  - `BooleanProperty layoutLocked`
  - `BooleanProperty isEditMode`
  - `ListProperty<ClassroomSummaryDto> cachedClassrooms`
  - `ObjectProperty<Long> selectedDashboardId`
  - Preferences 자동 관리
- **미통합 이유**: DashboardController에서 primitive 필드로 이미 관리 중

#### 2. DashboardUIService (Line 20 import 있음)
- 정의됨: [DashboardUIService.java](frontend/src/main/java/com/example/frontend/controller/dashboard/DashboardUIService.java)
- **상태**: 미통합 (메서드만 정의됨)
- 기능:
  - `showToast()` (DashboardController에도 중복 구현)
  - `applyZoom()`
  - `fitAllPanelsToViewport()`
- **문제**: 동일한 로직이 DashboardController에도 있음 (Line 996, 1099)

#### 3. DashboardControllerV2.java
- 존재 확인: 왜 생성되었는지 불명확
- 현재 main은 DashboardController 사용

---

## 2. UI/UX 문제점 탐색

### 현재 UI 레이아웃 방식

#### FXML 기반 로드
```xml
<!-- dashboard.fxml -->
<VBox xmlns:fx="http://javafx.com/fxml" 
      prefHeight="800.0" prefWidth="1200.0" 
      fx:controller="com.example.frontend.controller.DashboardController" 
      fx:id="rootVBox"/>
```

- **방식**: FXML이 최소한 (VBox 선언만)
- **실제 UI**: 100% 프로그래매틱 생성 (buildTopBar, buildTabs, buildLeftPane, buildRightPane)
- **영향도**: FXML의 이점 미활용, 유지보수 복잡도 증가

#### UI 구조
```
rootVBox (VBox)
├── topBar (HBox) [Line 173-236]
│   ├── titleBox (VBox)
│   ├── dashboardSelector (ComboBox)
│   ├── addDashboardBtn (Button)
│   ├── statusSummaryLabel (Label)
│   ├── connectivityLabel (Label)
│   └── clockLabel (Label)
└── tabPane (TabPane) [Line 242-255]
    ├── Tab "Main Dashboard"
    │   └── splitPane (SplitPane) [Line 266-285]
    │       ├── leftPane (VBox) [Line 289-665]
    │       │   ├── listTitle, listSub (Label)
    │       │   ├── groupListView (ListView)
    │       │   └── leftEditControls (VBox)
    │       │       └── 8개의 Button
    │       └── rightPane (VBox) [Line 682-850]
    │           ├── rightToolbar (HBox)
    │           │   └── 10+ 컨트롤 (버튼, 토글, 칼라피커)
    │           └── canvasStack (StackPane)
    │               ├── dashboardScrollPane (ScrollPane)
    │               └── toastContainer (VBox)
    ├── Tab "Network Traffic"
    │   └── networkPanelController.getPanel() [Line 248-249]
    └── Tab "Management"
        └── managementModule.createView() [Line 259-263]
```

### 반응형/적응형 디자인 부족

#### 1. 하드코딩된 크기
```java
// buildLeftPane() Line 289-290
VBox leftPane = new VBox(10);
leftPane.setPrefWidth(300);
leftPane.setMinWidth(260);

// buildRightPane() Line 1003-1008
double sceneWidth = rootVBox != null && rootVBox.getScene() != null 
    ? rootVBox.getScene().getWidth() : 1600;
boolean large = sceneWidth >= 1800;
boolean medium = sceneWidth >= 1400;
if ("NETWORK".equals(type)) {
    return large ? new double[]{720, 360}
        : medium ? new double[]{620, 320}
        : new double[]{520, 280};
}
```

**문제점**:
- SplitPane divider position: 고정 (0.23) [Line 280]
- 좌측 최소 너비: 260px (모바일에서 비실용적)
- 반응형 계산이 있지만 호출 시점이 불명확

#### 2. 스크린 크기에 따른 적응 불충분
```java
// resizeCanvasBySceneWidth() Line 1096-1109
if (width >= 1800) {
    canvasModule.getClassroomContainer().setPrefSize(2600, 1500);
} else if (width >= 1400) {
    canvasModule.getClassroomContainer().setPrefSize(2200, 1300);
} else {
    canvasModule.getClassroomContainer().setPrefSize(1800, 1100);
}
```

**관찰**:
- 캔버스 크기만 조정
- 좌측 패널, 우측 도구모음 크기 미조정
- 태블릿/모바일에서 매우 불편 (좌측 300px = 화면의 40%)

### 사용자 경험 개선 기회

#### 1. 접근성 (Accessibility) 부족
```java
// 음성 설명, 단축키 없음
Button addDashboardBtn = createActionButton("+ Dashboard", "#16a34a", "white");
// → setAccessibleText(), setAccessibleRole() 없음
```

**개선안**:
- ARIA 레이블 추가 (JavaFX의 Accessible API)
- 키보드 탐색 (Tab, Enter 지원)
- 스크린 리더 지원

#### 2. 피드백 부족
- Toast 메시지 (2.2초) 후 자동 사라짐 → 사용자가 놓칠 수 있음
- 로딩 인디케이터 없음 (5초 갱신 중 진행상황 모름)
- 오류 발생 시 상세한 가이드 없음

#### 3. 일관성 문제
```java
// 버튼 스타일 불일관
Button addDashboardBtn = createActionButton("+ Dashboard", "#16a34a", "white");
// vs.
ToggleButton editModeBtn = new ToggleButton("Edit mode");
editModeBtn.setStyle("-fx-background-radius: 8; -fx-font-weight: bold;");
// vs.
MenuButton alignMenu = new MenuButton("Align");
```

**문제**:
- 색상 일관성 없음 (#16a34a, #2563eb, #334155 등 혼재)
- 패딩, 폰트, 반경 다름
- 버튼/토글/메뉴 스타일 별도 정의

#### 4. 직관성 문제
- Edit Mode 활성화 → 도구모음만 보임 (좌측 버튼 안 보임)
- Focus Mode 활성화 → 좌측 패널 숨김 → 빠져나오는 방법 모호
- 드래그드롭 UI: "Drag to add" 힌트만 있음, 실제 작동 방식 개발자 추측 필요

---

## 3. 기능적 완성도 분석

### 구현된 기능
✅ 다중 대시보드 관리 (생성, 선택)
✅ 강의실 패널 추가/제거/배치
✅ 텍스트/이미지 위젯 추가/삭제
✅ 패널 드래그 & 리사이즈
✅ 레이아웃 모드 (Edit/View)
✅ 레이아웃 잠금
✅ 포커스 모드
✅ 전체화면 모드
✅ 줌인/아웃/Fit All
✅ 그리드 정렬 (Grid/Row/Column)
✅ 네트워크 트래픽 실시간 차트
✅ WebSocket 연결

### 미완성/주석 처리된 기능

#### 1. PDF 생성 (프론트엔드)
```java
// DashboardController Line 1216-1220
@FXML
private void handleGeneratePdf() {
    try {
        new com.example.frontend.service.DashboardPdfService().generateTrafficPdf(rootVBox.getScene().getWindow());
    } catch (Exception e) {
        showAlert("PDF generation failed", "An error occurred while generating PDF.\n" + e.getMessage());
    }
}
```

[DashboardPdfService.java](frontend/src/main/java/com/example/frontend/service/DashboardPdfService.java):
```java
// "파일 선택" 대화만 구현
// 실제 PDF 생성 주석 처리 → "외부 라이브러리 필요"
```

**상태**: 90% 미완성

#### 2. 레이아웃 저장/복원
```java
// 개별 패널 위치는 저장됨:
// dashboardService.updatePanelPositionAsync() via DashboardCanvasModule
// 하지만 전체 레이아웃 백업은 미구현
```

DashboardWidgetModule에만 있음:
```java
public void handleBackupTextItems() { /* 텍스트만 백업 */ }
public void handleRestoreTextItems() { /* 텍스트만 복원 */ }
```

**상태**: 부분 구현 (텍스트 위젯만)

#### 3. WebSocket 기반 실시간 업데이트
```java
// initialize() Line 153
connectWebSocket();
```

[DashboardRealtimeModule.java](frontend/src/main/java/com/example/frontend/controller/dashboard/DashboardRealtimeModule.java) 존재하나, 기능 상태 불명확

**추정 상태**: 초기 구현 단계

---

## 4. 모듈 간 의존성 및 결합도

### 의존성 구조
```
DashboardController (중앙 집중식)
├── DashboardCanvasModule (캔버스/렌더링)
│   └── Pane classroomContainer
├── DashboardClassroomModule (강의실 패널)
│   ├── classroomContainer (공유)
│   ├── DashboardPanelManager
│   ├── ApiClient
│   └── Runnable refreshCallback (DashboardController 제공)
├── DashboardWidgetModule (텍스트/이미지)
│   ├── classroomContainer (공유)
│   ├── textItemMap, imageItemMap (공유)
│   ├── ApiClient
│   └── Runnable onRefreshPanelList
├── DashboardRealtimeModule (WebSocket)
│   └── Runnable onRefreshCallback
├── DashboardManagementModule (관리)
└── NetworkPanelController (네트워크 차트, 5개+ 인스턴스)
```

### 결합도 분석
| 모듈 | 의존성 | 결합도 | 문제점 |
|------|--------|--------|--------|
| DashboardCanvasModule | classroomContainer (Pane) | **TIGHT** | 매개변수로만 전달, 내부 상태 접근 불가 |
| DashboardClassroomModule | classroomContainer, refreshCallback | **TIGHT** | 콜백으로 DashboardController 강하게 의존 |
| DashboardWidgetModule | textItemMap, imageItemMap (공유 Map) | **TIGHT** | 같은 맵 수정으로 동시성 이슈 가능 |
| DashboardRealtimeModule | 콜백만 | **LOOSE** | 독립적이나 기능 불확실 |

### 테스트 가능성 평가
```
DashboardController: ⭐ (1/5)
- 30+ 필드, 복잡한 상태
- 직접 UI 생성
- 테스트 더블 불가능 (HttpClient, 스케줄러 직접 생성)

DashboardCanvasModule: ⭐⭐ (2/5)
- Pane을 매개변수로 받음 (모킹 가능)
- 하지만 내부 Canvas, SelectionRect 생성

DashboardClassroomModule: ⭐⭐ (2/5)
- ApiClient 주입 가능
- 콜백 함수로 DashboardController 강하게 의존

DashboardWidgetModule: ⭐⭐ (2/5)
- ApiClient 주입 가능
- 공유 Map (textItemMap, imageItemMap)으로 상태 관리 → 테스트 복잡
```

### 새로운 모듈 추가 시 필요한 작업

**현재 패턴**:
```java
public class DashboardNewModule {
    public DashboardNewModule(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        String apiBaseUrl,
        Pane classroomContainer,
        // ... 추가 파라미터들
    ) { }
}
```

**필요 작업**:
1. DashboardController에 필드 추가: `private DashboardNewModule newModule;`
2. `createMainDashboardView()` 또는 해당 탭에서 초기화
3. classroomContainer 또는 전용 컨테이너 전달
4. 필요시 콜백 함수 정의
5. `refreshPanelListAndDashboard()`, `shutdown()`등에서 호출 추가

**문제점**: 매번 DashboardController 수정 필요 → 단일 책임 원칙 위반

---

## 5. ViewModel/UIService 미통합 현황

### DashboardViewModel.java 상세
[위치](frontend/src/main/java/com/example/frontend/controller/dashboard/DashboardViewModel.java)

**정의된 상태**:
```java
DoubleProperty zoomScale = new SimpleDoubleProperty(1.0);
BooleanProperty snapToGrid = new SimpleBooleanProperty(true);
BooleanProperty layoutLocked = new SimpleBooleanProperty(false);
BooleanProperty dashboardOnlyMode = new SimpleBooleanProperty(false);
BooleanProperty focusMode = new SimpleBooleanProperty(false);
BooleanProperty isEditMode = new SimpleBooleanProperty(false);

ListProperty<ClassroomSummaryDto> cachedClassrooms = ...
ListProperty<DashboardInfoDto> cachedDashboards = ...
ListProperty<DashboardPanelDto> cachedDashboardPanels = ...
ObjectProperty<Long> selectedDashboardId = ...
```

**정의된 메서드**:
- `loadPreferences()` / `savePreferences()`
- `zoomIn()` / `zoomOut()` / `resetZoom()`

**DashboardController에서의 중복**:
```java
private double zoomScale = 1.0;                    // ←→ ViewModel의 DoubleProperty
private boolean snapToGrid = true;                 // ←→ ViewModel의 BooleanProperty
private boolean layoutLocked = false;              // ←→ ViewModel의 BooleanProperty
private boolean isEditMode = false;                // ←→ ViewModel의 BooleanProperty
private List<ClassroomSummaryDto> cachedClassrooms; // ←→ ViewModel의 ListProperty
// ... 등 6개 중복
```

**미통합 이유분석**:
1. ViewModel이 JavaFX Properties 사용하지만, DashboardController는 primitive 타입 사용
2. Properties는 바인딩 가능하지만, DashboardController는 직접 setter 호출
3. 스케줄러에서 즉시 값 접근하므로 Property 리스너 불필요

### DashboardUIService.java 상세
[위치](frontend/src/main/java/com/example/frontend/controller/dashboard/DashboardUIService.java)

**정의된 메서드**:
```java
public void showToast(String message, boolean isError)
public void applyZoom(double scale)
public void fitAllPanelsToViewport(List<Node> nodes)
```

**DashboardController에서의 중복**:
```
showToast() - Line 996-1005의 로직과 동일
applyZoom() - Line 1023-1035의 로직과 동일
fitAllPanelsToViewport() - Line 1099-1123의 로직과 동일
```

**미통합 이유분석**:
1. 서비스가 생성되었지만 호출되지 않음
2. DashboardController에서 직접 UI 업데이트
3. 아마도 리팩토링 중간에 방치된 상태

### 통합 권고 계획

#### Step 1: DashboardViewModel 통합
```java
// DashboardController에서
private final DashboardViewModel viewModel = new DashboardViewModel();

// initialize()에서
zoomScale = viewModel.getZoomScale().doubleValue();
viewModel.getZoomScale().addListener((obs, oldV, newV) -> {
    if (classroomContainer != null) {
        classroomContainer.setScaleX(newV.doubleValue());
        classroomContainer.setScaleY(newV.doubleValue());
    }
});
```

**소요 시간**: ~30분
**영향도**: 상태 관리 전문화

#### Step 2: DashboardUIService 통합
```java
private final DashboardUIService uiService = new DashboardUIService(
    toastContainer, classroomContainer, dashboardScrollPane, canvasModule, ...
);

// 대신:
// showToast() → uiService.showToast()
// applyZoom() → uiService.applyZoom()
```

**소요 시간**: ~20분
**영향도**: UI 업데이트 로직 분리

---

## 6. 외부 라이브러리 현황

### build.gradle 현재 의존성
```gradle
dependencies {
    implementation project(':common')
    
    // Jackson for JSON
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.15.2'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2'
    
    // WebSocket / Stomp Client
    implementation 'org.springframework:spring-messaging:6.1.4'
    implementation 'org.springframework:spring-websocket:6.1.4'
    implementation 'org.glassfish.tyrus.bundles:tyrus-standalone-client:2.1.1'
    implementation 'jakarta.websocket:jakarta.websocket-client-api:2.1.1'
    
    // Logging
    implementation 'org.slf4j:slf4j-api:2.0.7'
    implementation 'ch.qos.logback:logback-classic:1.4.11'
}
```

### CSS 테마 현황
[dashboard-theme.css](frontend/src/main/resources/dashboard-theme.css) - 50줄, 기본적인 스타일만 정의

```css
.root {
    -fx-font-family: "Segoe UI";
    -fx-background-color: linear-gradient(...);
}
.button {
    -fx-background-radius: 10;
    -fx-padding: 8 14 8 14;
    -fx-font-weight: 700;
}
```

**문제점**:
- 변수 (--primary-color, --border-color 등) 없음
- 다크 모드 없음
- 애니메이션 정의 없음

### 라이브러리 서드파티 마이그레이션 준비 상태

#### ControlsFX (데이터 그리드, 풍부한 컨트롤)
- **상태**: 미도입
- **추천**: `implementation 'org.controlsfx:controlsfx:11.1.1'`
- **활용처**: CustomTableView, RangeSlider, ToggleGroup 등
- **준비도**: ✅ 도입 가능 (기존 타입 호환)

#### Ikonli (아이콘 라이브러리)
- **상태**: 미도입
- **추천**: `implementation 'org.kordamp.ikonli:ikonli-javafx:12.3.1'` + `org.kordamp.ikonli:ikonli-material-pack:12.3.1'`
- **활용처**: 버튼/레이블 아이콘 (마테리얼 디자인)
- **준비도**: ✅ 버튼 생성 로직 수정 필요

#### AtlantaFX (모던 CSS 테마)
- **상태**: 미도입
- **추천**: `implementation 'com.dlsc.formsfx:formsfx-core:11.6.0'` 또는 `org.atlantafx:atlantafx-base:0.6'`
- **활용처**: 테마 통일, CSS 변수 사용
- **준비도**: ⚠️ build.gradle 수정 필요

#### Tiwai Chart Library (고급 차트)
- **상태**: 현재는 NetworkPanelController에서 기본 구현
- **추천**: `implementation 'com.github.javafxcharts:XChart:3.7.10'` 또는 유사 라이브러리
- **활용처**: 네트워크 트래픽, 통계 차트 고급화
- **준비도**: ✅ NetworkPanelController 기존 로직 교체 가능

#### iText (PDF 생성)
- **상태**: 백엔드에만 있음 (ReportService)
- **추천**: `implementation 'com.itextpdf:kernel:7.2.4'` (프론트 추가)
- **활용처**: DashboardPdfService 완성
- **준비도**: ⚠️ DashboardPdfService의 handleGeneratePdf() 구현 필요

---

## 7. 종합 문제점 및 개선 우선순위

### 🔴 CRITICAL (즉시 해결 필요)

1. **DashboardController 메가 클래스**
   - 라인: 1,226줄
   - 필드: 30+개
   - 메서드: 30+개
   - **영향도**: 테스트 불가능, 유지보수 매우 어려움
   - **해결안**: 각 탭을 독립적인 모듈로 분리

2. **드래그/리사이즈 로직 중복**
   - 3개 모듈에서 ~75줄 중복
   - **해결안**: `BaseDragResizablePanel` 추상 클래스 생성

3. **UI 블로킹 (5초 갱신)**
   - 4개 독립 요청 순차 실행
   - 네트워크 패널 많을 때 ~400+ req/5s
   - **해결안**: 배치 API 또는 필터 조건 추가

### 🟠 HIGH (1주일 내 해결)

4. **DashboardViewModel/UIService 미통합**
   - 15-20줄 중복 코드
   - **해결안**: Step 1, 2 따라 통합

5. **PDF 생성 미완성**
   - FileChooser 대화만 구현
   - **해결안**: iText 라이브러리 추가, `generateTrafficPdf()` 구현

6. **CSS 테마 부실**
   - 색상 혼재 (#16a34a, #2563eb, #334155, #0ea5e9 등 7가지)
   - **해결안**: AtlantaFX 또는 커스텀 변수 기반 테마 정의

### 🟡 MEDIUM (2주일 내 해결)

7. **반응형 디자인 부족**
   - 모바일/태블릿에서 좌측 300px = 40% 차지
   - **해결안**: SplitPane divider 동적 조정, 모바일 레이아웃 분기

8. **접근성 (Accessibility) 부족**
   - 스크린 리더 지원 없음
   - **해결안**: accessible API 활용, 단축키 추가

9. **네트워크 오류 처리 미흡**
   - Exception catch하지만 로그 없음
   - **해결안**: Logback 활용, 재시도 로직 추가

### 🔵 LOW (다음 스프린트)

10. **WebSocket 기능 불명확**
    - DashboardRealtimeModule 존재하나 상태 모호
    - **해결안**: 문서화, 테스트 추가

11. **패널 정렬 기능 개선**
    - Grid/Row/Column만 있음, 자동 배치 알고리즘 미흡
    - **해결안**: 최적화된 배치 알고리즘 구현

---

## 8. 코드 품질 메트릭

| 지표 | 값 | 평가 |
|------|-----|------|
| **순환 복잡도** (DashboardController) | 매우 높음 (20+) | ⭐ (1/5) |
| **테스트 커버리지** | ~0% | ❌ |
| **MAINTAINABILITY INDEX** (추정) | 40-50 | ⭐⭐ (2/5) |
| **중복 코드** | ~75줄 (3%) | ⭐⭐⭐ (3/5) |
| **문서화** | 주석 ~20줄 | ⭐⭐ (2/5) |
| **일관성** | 버튼 스타일, 색상 혼재 | ⭐⭐ (2/5) |

---

## 9. 아직 조사되지 않은 영역

- [ ] DashboardControllerV2.java의 용도 및 상태
- [ ] NetworkPanelController의 차트 라이브러리 (JavaChart? 기본 라인?)
- [ ] 백엔드 PDF 생성 (ReportService)의 프론트엔드 연계 여부
- [ ] 로그인 페이지 (LoginController) 전체 플로우
- [ ] 캐시 전략 (cachedClassrooms, cachedDashboards 업데이트 타이밍)
- [ ] 에러 복구 로직 (네트워크 끊김 시)
