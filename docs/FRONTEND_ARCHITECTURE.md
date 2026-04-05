
# 프론트엔드 아키텍처 가이드

## 범위
- 모듈: frontend (JavaFX)
- 메인 진입점: com.example.frontend.DashboardApp
- 주요 화면 컨트롤러: com.example.frontend.controller.DashboardController

## 상위 구조
- 뷰(View):
  - FXML: 로그인, 대시보드 등 기본 틀
  - DashboardController에서 Java 코드로 동적 레이아웃 생성
- 뷰모델(ViewModel):
  - DashboardViewModel: UI 상태 및 사용자 환경설정 관리
- UI 서비스:
  - DashboardUIService: 시각적 동작(줌, 전체 맞춤, 토스트, 표시/숨김 등)
- 인터랙션 서비스:
  - DashboardInteractionService: 드래그/리사이즈 핸들러 담당
- 주요 기능 모듈:
  - DashboardClassroomModule
  - DashboardWidgetModule
  - DashboardRealtimeModule
  - DashboardManagementModule
  - DashboardCanvasModule
- 데이터/네트워크:
  - DashboardService (REST API 연동)
  - DashboardDataService (스냅샷/강의실 목록 갱신)
  - ApiClient (HTTP + JSON 처리)

## 런타임 흐름
1. DashboardApp이 로그인 화면을 로드하고 전역 스타일/테마를 적용합니다.
2. DashboardController가 ViewModel, 상단 바, 탭, 각 모듈을 초기화합니다.
3. 스케줄러가 주기적으로 스냅샷 갱신을 트리거합니다.
4. 실시간 모듈이 웹소켓 알림 및 갱신 힌트를 전달합니다.
5. 캔버스 및 인터랙션 서비스가 사용자의 드래그/리사이즈 동작을 관리합니다.
6. 서비스 계층이 패널/위젯 위치를 백엔드 API로 저장합니다.

## 상태 소유권
- DashboardViewModel이 관리하는 항목:
  - zoomScale, snapToGrid, layoutLocked
  - dashboardOnlyMode, focusMode, editMode
  - selectedDashboardId
  - 강의실/대시보드/패널 등 캐시 리스트
- DashboardController는 임의의 지역 변수 대신 ViewModel의 getter/setter 사용을 권장합니다.

## 현재 리팩토링 현황
- 완료:
  - 드래그/리사이즈 로직을 DashboardInteractionService로 일원화
  - 우측/좌측 패널 빌더 메서드 분리로 가독성 향상
  - 네트워크 패널 렌더링을 헬퍼 메서드로 분리
  - ViewModel, UI 서비스, 인터랙션 서비스, 데이터 서비스에 대한 단위/통합 테스트 추가
- 진행 중:
  - DashboardController의 셀 렌더링 및 패널 오케스트레이션 추가 분해

## 추천 후속 작업
1. 남은 대형 UI 블록을 별도 빌더 클래스로 분리
2. 컨트롤러는 조립 루트 및 이벤트 라우팅 역할만 유지
3. 컨트롤러 워크플로우 및 모듈 경계 테스트 확대
4. 성능 기준선 업데이트는 scripts/profile-dashboard.latest.json에 유지
