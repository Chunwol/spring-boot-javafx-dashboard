# Engineering Handoff (2026-03-18)

## 현 상태 요약
- Frontend(JavaFX) 대시보드 UI는 수정모드 중심 워크플로우로 정리됨.
- 패널 이동/리사이즈/저장, 스냅/줌/자동배치/잠금 기능이 도입됨.
- 강의실/네트워크 패널은 dashboard panel 기반 저장 경로를 사용.
- 텍스트/이미지 위젯은 입력 다이얼로그 기반 추가 흐름으로 개선됨.

## 이번 사이클 핵심 변경
- 목록 체크박스 제거 및 패널 직접 삭제 UX.
- 전체화면/집중보기/대시보드 전용 보기 추가.
- 드래그 충돌(선택 사각형/패널 이동) 완화.
- 전체맞춤 + 줌 안정화 시도 및 스냅 토글 적용.
- 편집 전용 도구를 수정모드에 종속.

## 알려진 리스크
- Qsync + Windows 환경에서 `:frontend:jar` 스냅샷 오류가 간헐 발생.
- 대형 Controller 단일 파일 편집 중 도구/자동치환으로 파일 손상 위험 존재.

## 운영 체크리스트
1. `:frontend:classes` 성공 확인.
2. `:frontend:run` 기동 후 수정모드 ON/OFF UX 확인.
3. 패널 추가/이동/리사이즈 후 새로고침 시 위치 유지 확인.
4. 텍스트/이미지 추가 후 목록/캔버스 즉시 반영 확인.
5. 전체맞춤 후 줌 버튼 동작 재확인.

## 다음 우선순위
1. DashboardController 분리(MVVM 또는 모듈 클래스 분리).
2. 편집모드 UI를 FXML 컴포넌트로 분리.
3. 패널 저장 성공/실패 로깅/토스트 표준화.
4. 패키징 안정화(Qsync 외부 빌드 경로 또는 복제 작업 디렉토리).

## 📊 핵심 발견

### 🚨 **CRITICAL 문제 3가지**

| 문제 | 영향 | 근원 |
|------|------|------|
| **1. DashboardController 비대화** | 테스트 불가, 유지보수 어려움 | 1,226줄, 30+ 메서드 모두 한곳 |
| **2. 성능 저하** | 100개 패널 시 5초마다 400+ 요청 | 독립적 HTTP 요청 4개 반복 |
| **3. 중복 코드 75줄** | 드래그/리사이즈 버전마다 다름 | 3곳에서 동일 로직 반복 |

### 🟠 **HIGH 우선순위 (이미 준비된 개선)**

1. **ViewModel/UIService 통합** → DashboardViewModel, UIService 생성됨 (미통합)
   - 상태 중복 6개 필드 제거
   - 메서드 중복 제거 (showToast, applyZoom 등)
   - 소요시간: **50분**

2. **PDF 생성 완성** → 현재 FileChooser만 구현 (10%)
3. **색상/테마 통일** → 7가지 혼재 색상 → CSS 변수 기반

### 🎯 **전략적 제안**

다음 중 **어느 경로를 먼저 진행할지** 선택 부탁드립니다: 

Analyzing your answers...

좋습니다. **전체 분석 후 종합 계획 수립** 및 **장기 개선 로드맵(1개월+)** 을 작성하겠습니다. 

메모리 파일 [](file:///c%3A/Users/chunwol/AppData/Roaming/Code/User/workspaceStorage/2c8d51534c249d84e7d05fecdfb3f69a/GitHub.copilot-chat/memory-tool/memories/MDNhMjMyZmUtMGJiMC00NDA2LWFkZWUtMTliZWU4YWU4NjRj/plan.md) 업데이트됨

이제 **종합 리팩토링 로드맵**을 상세히 작성하겠습니다.

## 📋 **Plan: JavaFX 대시보드 종합 리팩토링** (1개월+)

### TL;DR
DashboardController의 1,226줄을 4개 전문 서비스로 분해하고, 성능(400+ 요청 → 5개로 최적화), 중복 코드 제거, 미완성 기능 완성, 접근성/반응형 개선을 단계적으로 추진합니다.

---

## 🔴 **PHASE 1: 아키텍처 기반 구축 (1주일)**

### Step 1-1: DashboardStateManager 추출 (2시간)
- **위치**: `frontend/src/main/java/com/example/frontend/service/DashboardStateManager.java` (NEW)
- **목적**: 상태 관리 전문화 (zoomScale, snapToGrid, isEditMode 등)
- **내용**:
  - DashboardViewModel 통합 (이미 생성됨)
  - Preferences 저장/로드 로직 중앙화
  - 상태 변경 이벤트 발행 (옵저버 패턴)
  - 메서드: getZoomScale(), toggleEditMode(), resetDashboard() 등
- **검증**: `:frontend:classes` 통과
- **git**: `refactor(core): extract DashboardStateManager`

### Step 1-2: DashboardLayoutService 추출 (3시간)
- **위치**: `frontend/.../DashboardLayoutService.java` (NEW, 확장)
- **목적**: 레이아웃 연산 분리 (이미 UIService 생성됨, 확대)
- **내용**:
  - fitAllPanelsToViewport() 로직 이관
  - arrangeByGrid/Row/Column() 통합
  - snapValue(), snapPoint() 추가
  - resizePanel(), movePanel() 추가
  - 좌표 변환 유틸 (sceneToLocal, localToScene)
- **메서드 수**: 15개
- **검증**: `:frontend:classes` 통과
- **git**: `refactor(core): extract DashboardLayoutService`

### Step 1-3: DashboardInteractionService 추출 (2시간)
- **위치**: `frontend/.../DashboardInteractionService.java` (NEW)
- **목적**: 드래그/리사이즈 중복 제거 (75줄 3곳)
- **내용**:
  - `attachDragListener(Node panel, Callback onSaved)`
  - `attachResizeListener(Node panel, Callback onSaved)`
  - 드래그 시작/진행/끝 상태 관리
  - 스냅 격자 적용 (snapToGrid 참조)
  - Edit Mode 체크 & 반환
- **중복 제거**: 75줄 → 25줄 (3개 모듈이 공유)
- **기존 호출 업데이트**:
  - DashboardWidgetModule
  - DashboardClassroomModule
  - DashboardController (네트워크 패널)
- **검증**: `:frontend:classes` + `:frontend:build -x test`
- **git**: `refactor(interactions): BaseDragResizablePanel via DashboardInteractionService`

### Step 1-4: DashboardDataService 추출 (2시간)
- **위치**: `frontend/.../DashboardDataService.java` (NEW)
- **목적**: API 호출 배치화 + 캐싱
- **내용**:
  - `refreshAllData()`: 통합 API 엔드포인트 (또는 4개를 1초 간격으로 배치)
    ```java
    // 기존: 5초마다 다음 5개 독립 요청
    refreshDashboardData()         // 1초
    networkPanelController.refresh() // 2초
    networkItemControllers.refresh() // 3초
    widgetModule.loadTextItems()    // 4초
    widgetModule.loadImageItems()   // 5초
    
    // 개선: 배치 + 병렬화
    CompletableFuture.allOf(
      refreshDashboard(),  // 병렬
      refreshNetworkChart(),
      refreshWidgets()
    ).join();
    ```
  - 30초 자동 갱신 주기로 변경 (5초 → 30초)
  - 수동 갱신 버튼 "새로고침" 추가
  - 로딩 UI 표시 (ProgressBar)
  - 에러 재시도 로직
- **성능 개선**: 400+ 요청/5초 → 20 요청/30초 (95% 감소)
- **검증**: 네트워크 탭에서 요청 수 확인
- **의존**: Step 1-3 (상태 접근)
- **git**: `perf(network): batch HTTP requests and optimize refresh cycle`

### Step 1-5: DashboardController 통합 (3시간)
- **목적**: 새로운 서비스들을 DashboardController에 주입
- **변경**:
  ```java
  // 기존
  private double zoomScale = 1.0;
  private void applyZoom(double scale) { ... }
  
  // 개선
  private DashboardStateManager stateManager;
  private DashboardLayoutService layoutService;
  private DashboardInteractionService interactionService;
  private DashboardDataService dataService;
  
  // initialize()에서
  stateManager = new DashboardStateManager();
  layoutService = new DashboardLayoutService(...);
  interactionService = new DashboardInteractionService(stateManager);
  dataService = new DashboardDataService(...);
  
  // 메서드 호출 변경
  applyZoom(1.2) → layoutService.applyZoom(1.2);
  refr ashDashboardData() → dataService.refreshAllData();
  fitAllPanelsToViewport() → layoutService.fitAllPanels(...);
  ```
- **제거 대상**:
  - 드래그 이벤트 핸들 (모두 DashboardInteractionService로 이관)
  - 75줄 중복 코드
  - applyZoom(), fitAllPanels(), showToast() 메서드
  - 30개 상태 필드 중 6개 → StateManager로 위임
- **결과**: DashboardController 1,226줄 → ~850줄 (30% 감소)
- **검증**: 빌드 통과 + 기능 테스트 (모든 패널 조정 가능, 줌 동작)
- **의존**: Step 1-1~1-4 모두
- **git**: `refactor(core): integrate services into DashboardController`

### **PHASE 1 검증**
```bash
# 각 Step 후
./gradlew.bat :frontend:classes

# Step 1-5 후
./gradlew.bat :frontend:build -x test
```

**메트릭 개선**:
- 코드 라인: 1,226줄 → 850줄 (30% ↓)
- 중복 코드: 75줄 → 0줄 (100% 제거)
- HTTP 요청: 400+/5초 → 20/30초 (95% ↓)
- 순환 복잡도: 20+ → 8 (각 서비스 5 이하)

---

## 🟠 **PHASE 2: 기능 완성 (1주일)**

### Step 2-1: PDF 생성 구현 (3시간)
- **현황**: FileChooser 대화만 있음 (10%)
- **목표**: 완전한 PDF 생성 (100%)
- **필요 라이브러리**: [iText 7](https://itextpdf.com/) (backend 이미 사용 중) → frontend에 추가
- **구현**:
  ```java
  // DashboardPdfService.java (기존, 완성)
  public void generateAndSavePdf() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("PDF 저장");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
    File saveFile = chooser.showSaveDialog(stage);
    
    if (saveFile != null) {
      byte[] pdfBytes = backendClient.getPdfReport("DASHBOARD");
      Files.write(saveFile.toPath(), pdfBytes);
      showToast("PDF 저장 완료", false);
    }
  }
  ```
- **백엔드 API** (확인 필요):
  - GET `/api/v1/reports/pdf?type=DASHBOARD` → byte[]
  - DashboardPdfService (backend) 활용
- **검증**: "PDF 다운로드" 버튼 클릭 → PDF 파일 생성됨
- **git**: `feat(export): implement PDF generation frontend`

### Step 2-2: 레이아웃 전체 백업/복원 (2시간)
- **현황**: 텍스트 위젯만 가능 (JSON 내보내기/가져오기)
- **목표**: 모든 패널 (강의실, 텍스트, 이미지, 네트워크) 포함
- **구현**:
  ```java
  public void backupLayout() {
    Map<String, Object> layout = new LinkedHashMap<>();
    layout.put("timestamp", LocalDateTime.now());
    layout.put("classrooms", classroomModule.exportState());   // 위치, 크기, 순서
    layout.put("textItems", allTextWidgets);
    layout.put("imageItems", allImageWidgets);
    layout.put("networkPanels", networkItemControllers.keySet());
    
    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(layout);
    Files.write(backupFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
  }
  
  public void restoreLayout(File backupFile) {
    // 동일하게 복원 (순서 보존 필요)
  }
  ```
- **UI**: "레이아웃 백업" / "레이아웃 복원" 버튼 추가
- **검증**: 레이아웃 변경 → 백업 → 레이아웃 변경 → 복원 → 이전 상태 복구
- **git**: `feat(persistence): add full layout backup/restore`

### Step 2-3: WebSocket 안정화 (DashboardRealtimeModule 검토) (2시간)
- **목표**: 실시간 데이터 업데이트 기능 명확화 및 안정화
- **필요 분석**:
  - DashboardRealtimeModule의 실제 역할 확인
  - 현재 WebSocket 연결 상태 관리 미흡 여부
  - 재연결 로직 부재 여부
- **구현** (필요시):
  - WebSocket 연결 끊김 감지
  - 자동 재연결 (exponential backoff)
  - 연결 상태 UI 표시 (🟢 연결됨 / 🔴 연결 끊김)
  - 하트비트 ping/pong
- **검증**: 서버 재시작 → 클라이언트 자동 재연결
- **git**: `fix(websocket): stabilize realtime updates`

### Step 2-4: ViewModel/UIService 완전 통합 (1시간)
- **목표**: 미통합된 부분 마무리 (상태/메서드 중복 제거)
- **작업**:
  - DashboardStateManager와 DashboardViewModel 통일 (또는 StateManager 제거)
  - DashboardUIService의 모든 메서드 사용 확인
  - 프로퍼티 바인딩 설정 (리스너 → 실제 바인딩)
- **검증**: ViewModel/UIService 테스트 코드 작성 (단위 테스트 3-5개)
- **git**: `refactor(mvvm): complete ViewModel/UIService integration`

### **PHASE 2 검증**
- PDF 생성 완료율: 10% → 100%
- 레이아웃 저장: 30% → 100%
- WebSocket 자동 재연결: ✅
- 빌드: `:frontend:build -x test` 통과

---

## 🟡 **PHASE 3: UI/UX 개선 (1주일)**

### Step 3-1: CSS 테마 시스템 도입 (3시간)
- **현황**: hardcoded 색상 7가지, CSS 변수 없음
- **목표**: CSS 변수 기반 일관된 색상, 다크 모드 지원
- **구현**:
  ```css
  /* dashboard-theme.css */
  .root {
    --primary: #16a34a;
    --secondary: #2563eb;
    --success: #10b981;
    --warning: #f59e0b;
    --error: #ef4444;
    --neutral: #6b7280;
  }
  
  /* Dark mode */
  .root.dark-mode {
    --primary: #22c55e;
    --bg: #1f2937;
    --text: #f3f4f6;
  }
  
  .button-primary {
    -fx-background-color: var(--primary);
  }
  ```
- **통합**: 모든 색상 참조 → CSS 변수로 변경
  - hardcoded `#16a34a` 제거
  - `getStyle()` 대신 `getStyleClass()` 사용
- **다크 모드**: 토글 버튼 → `scene.getRoot().getStyleClass().add("dark-mode")`
- **검증**: alt+T 다크 모드 토글 → 모든 UI 색상 변경
- **git**: `style(theme): introduce CSS variables and dark mode`

### Step 3-2: 반응형 디자인 (2시간)
- **문제**: 좌측 패널 고정 300px (모바일 불가능)
- **해결**:
  ```java
  // 기존
  leftPane.setPrefWidth(300);
  
  // 개선
  SplitPane splitPane = (SplitPane) ..;
  splitPane.setDividerPositions(0.25); // 25% 동적
  
  // 화면 크기 리스너
  stage.widthProperty().addListener((obs, oldVal, newVal) -> {
    if (newVal.doubleValue() < 800) {
      leftPane.setVisible(false);  // 모바일: 좌측 패널 숨김
    } else {
      leftPane.setVisible(true);
    }
  });
  ```
- **반응형 레이아웃**:
  - **모바일** (<768px): 좌측 숨김, 탭 기반 네비게이션
  - **태블릿** (768-1024px): SplitPane 비율 조정
  - **데스크톱** (>1024px): 현재 상태 유지
- **검증**: 창 리사이즈 → UI 자동 조정
- **git**: `style(responsive): add mobile/tablet support`

### Step 3-3: 접근성 개선 (2시간)
- **스크린 리더 지원**:
  ```java
  button.setAccessibleText("패널 추가");
  button.setAccessibleRole(AccessibleRole.BUTTON);
  button.setAccessibleRoleDescription("새로운 패널 추가 버튼");
  ```
- **키보드 단축키**:
  ```java
  // Ctrl+E: Edit Mode toggle
  // Ctrl+F: Focus Mode
  // Ctrl+Z: Undo (선택사항)
  // Ctrl+S: Save/Backup
  ```
- **색상 대비**: WCAG AA standard (4.5:1 최소)
  - 현재 색상 컨트라스트 검사
  - 필요시 밝기 조정
- **검증**: 스크린 리더 (NVDA/JAWS) 테스트 / 키보드만으로 모든 기능 접근
- **git**: `a11y(accessibility): add screen reader support and keyboard shortcuts`

### **PHASE 3 검증**
- 색상 일관성: 7가지 → 6개 변수 + 다크 모드
- 반응형: 모바일 (320px~), 태블릿, 데스크톱 모두 테스트
- 접근성: 키보드 네비게이션 100% 가능

---

## 🟢 **PHASE 4: 외부 라이브러리 도입 (1주일+)**

### Step 4-1: ControlsFX 도입 (2시간)
- **진행 상태 (2026-03-19)**: 완료
- **라이브러리 추가**: `build.gradle`
  ```gradle
  implementation "org.controlsfx:controlsfx:11.1.2"
  ```
- **활용 처**:
  - TableView → 리치 DataGrid
  - CustomDialog 사용 (텍스트/이미지 입력)
  - PropertySheet (설정 UI)
  - RangeSlider (시간대 필터)
- **변경**: DashboardWidgetModule의 입력 대화 → ControlsFX Dialog로 개폭
- **검증**: 빌드 통과 + 대화 기능 동작
- **git**: `deps(ui): add ControlsFX for rich controls`

### Step 4-2: Ikonli (Material Icons) 도입 (1.5시간)
- **진행 상태 (2026-03-19)**: 완료
- **라이브러리 추가**:
  ```gradle
  implementation "org.kordamp.ikonli:ikonli-javafx:12.3.1"
  implementation "org.kordamp.ikonli:ikonli-materialdesign2-pack:12.3.1"
  ```
- **활용**:
  - 텍스트 버튼 → 아이콘 버튼 (일관된 Material Design)
  - 아이콘 패밀리: add, delete, edit, settings, save, refresh 등
  - 색상 + 크기 동적 조정
- **변경 예**:
  ```java
  // 기존
  Button addBtn = new Button("패널 추가");
  
  // 개선
  Button addBtn = new Button();
  addBtn.setGraphic(new FontIcon("fas-plus"));
  addBtn.setTooltip(new Tooltip("패널 추가 (Ctrl+N)"));
  ```
- **검증**: 모든 버튼 아이콘 표시 확인
- **git**: `style(icons): integrate Ikonli Material Icons`

### Step 4-3: AtlantaFX (Modern Theme) 도입 (2시간)
- **라이브러리 추가**:
  ```gradle
  implementation "com.dlsc.formsfx:formsfx-core:11.3.2"
  ```
  또는 CSS 기반 테마 적용
- **목표**: 모던한 외관 (버튼, 입력 필드, 스크롤바 등 통일)
- **적용**:
  ```java
  AtlantaFX.setTheme(new PrimerLight());
  ```
- **검증**: UI 전체 시각적 개선 확인
- **git**: `style(theme): apply AtlantaFX modern theme`

### **PHASE 4 검증**
- ControlsFX: DataGrid 렌더링 가능
- Ikonli: 모든 아이콘 표시 완료
- AtlantaFX: 테마 적용 + 다크 모드와 호환

---

## 🎯 **PHASE 5: 테스트 + 최적화 (1주일)**

### Step 5-1: 단위 테스트 작성 (3시간)
- **진행 상태 (2026-03-19)**: 진행 중 (1차 완료)
- **완료 항목**:
  - `DashboardViewModelTest` 추가
  - `DashboardInteractionServiceTest` 추가
  - `:frontend:test` 통과
- **대상 클래스**:
  - DashboardStateManager (상태 변경 테스트)
  - DashboardLayoutService (좌표 계산 테스트)
  - DashboardInteractionService (드래그 로직 테스트)
  - DashboardDataService (캐싱 로직 테스트)
- **목표**: 각 서비스별 3-5개 테스트, 70%+ 커버리지
- **도구**: JUnit 5 + AssertJ
- **예**:
  ```java
  @Test
  void testZoomWithSnap() {
    DashboardStateManager state = new DashboardStateManager();
    state.setSnapToGrid(true);
    state.setZoomScale(1.5);
    
    assertThat(state.snapValue(1.234)).isEqualTo(1.0); // snap 간격에 따라
  }
  ```
- **검증**: `./gradlew test` 통과
- **git**: `test(core): add unit tests for services`

### Step 5-2: 통합 테스트 (2시간)
- **진행 상태 (2026-03-19)**: 진행 중 (3차 완료)
- **완료 항목**:
  - `DashboardDataServiceTest` 추가 (비동기 fallback 포함)
  - `DashboardUIServiceTest` 추가 (줌/편집 UI/fit-all 분기 검증)
  - `DashboardViewModelTest`에 설정 영속(load/save) 케이스 추가
  - `DashboardInteractionServiceTest`에 드래그/리사이즈 값 변화 검증 추가
  - `:frontend:test` 통과
- **시나리오**:
  - Dashboard 로드 → 모든 패널 렌더링 ✅
  - Edit Mode 토글 → UI 상태 변경 ✅
  - 패널 드래그 → 위치 저장 + API 호출 ✅
  - 줌 → fit-all 동작 ✅
  - 레이아웃 백업/복원 ✅
- **도구**: Testfx (JavaFX GUI 테스트)
- **git**: `test(integration): add E2E tests for dashboard workflows`

### Step 5-3: 성능 프로파일링 (2시간)
- **측정 항목**:
  - 초기 로드 시간 (목표: <2초)
  - HTTP 요청 수 및 대역폭 (목표: 95% ↓)
  - 메모리 사용량 (목표: 안정적 <200MB)
  - UI 프레임 레이트 (목표: 60fps 유지)
- **도구**: Java Flight Recorder, YourKit, Chrome DevTools (REST API)
- **git**: `perf(profiling): add performance baselines`

### Step 5-4: 문서화 (2시간)
- **내용**:
  - 아키텍처 문서 (서비스 구조 다이어그램)
  - API 통합 가이드 (새 모듈 추가 방법)
  - 스타일 가이드 (색상, 폰트, 레이아웃)
  - 문제 해결 가이드 (일반적 버그 + 해결책)
- **위치**: `docs/FRONTEND_ARCHITECTURE.md`, `docs/STYLE_GUIDE.md`
- **git**: `docs(frontend): add architecture and style guides`

### **PHASE 5 검증**
- 테스트: 총 15+ 테스트 실행, 모두 통과
- 성능: 로드 시간 <2초, HTTP 요청 95% 감소
- 메모리: 안정적 <200MB
- 문서: 신규 개발자 온보딩 가능

---

## 📊 **영향도 분석 & 의존성**

### 병렬 처리 가능
- Phase 1-1과 1-2 병렬 (의존성 없음)
- Phase 2-1과 2-2 병렬
- Phase 3-1과 3-2 병렬

### 순차 필수
- Phase 1 완료 → Phase 2 시작 (기반 아키텍처 필요)
- Phase 2 완료 → Phase 3 시작 (안정성 확보)
- Phase 3 완료 → Phase 4 시작 (코드 정리 상태)

### 경로 최적화
```
Week 1: Phase 1-1~1-5 (병렬화 포함)
Week 2: Phase 2-1~2-4
Week 3: Phase 3-1~3-3  
Week 4: Phase 4-1~4-3 + Phase 5-1~5-4
```

---

## ✅ **검증 체크리스트**

### 각 Phase 완료 후
- [ ] 빌드 통과 (`:frontend:build -x test`)
- [ ] Git 커밋 (의미있는 메시지)
- [ ] 기능 테스트 (수동: 주요 사용 시나리오)
- [ ] 성능 확인 (로드 시간, 메모리)

### 최종 검증
- [ ] 전체 기능 회귀 테스트
- [ ] 라이브러리 호환성 (Java 17+)
- [ ] UI/UX 일관성 재검토
- [ ] 프로덕션 빌드 (`:frontend:jar`)

---

## 🚀 **결론**

| 메트릭 | 현재 | 목표 | 달성도 |
|--------|------|------|--------|
| **코드 라인** | 1,226 | 850 | 30% ↓ |
| **중복 코드** | 75줄 | 0 | 100% 제거 |
| **HTTP 요청** | 400+/5초 | 20/30초 | 95% ↓ |
| **순환 복잡도** | 20+ | <5 | 75% ↓ |
| **테스트 커버리지** | 0% | 70% | 新 |
| **기능 완성도** | 80% | 100% | +20% |
| **접근성** | 0% | 70% | 新 |

---

## 추가 고려사항

### 🔵 **Backend API 통합 필요**
1. 통합 새로고침 엔드포인트 (Step 1-4)
   ```
   GET /api/v1/dashboard/refresh?types=classroom,network,widgets
   ```
2. PDF 리포트 엔드포인트 확인 (Step 2-1)

### 🟣 **장기 로드맵 (1개월 이후)**
- FXML 마이그레이션 (프로그래밍 UI → 선언형)
- SceneBuilder 통합 (비개발자도 UI 편집)
- 실시간 협업 기능 (여러 사용자 동시 편집)
- 플러그인 시스템 (커스텀 패널 등록)

이 계획을 검토하시고 **시작할 Phase를 선택**하거나 **구체적 조정 사항**이 있으시면 알려주세요!