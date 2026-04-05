
# 프론트엔드 스타일 가이드

## 목적
이 가이드는 JavaFX 프론트엔드 모듈의 코딩 및 UI 규칙을 정의합니다.

## 자바 코드 규칙
- 컨트롤러 메서드는 명확하고 짧게 유지합니다.
- 반복되는 UI/이벤트 로직은 헬퍼 메서드나 서비스로 분리합니다.
- 약어 대신 명확한 이름을 사용합니다.
- 상태 변경과 렌더링 로직을 한 블록에 섞지 않습니다.
- 비동기 API 콜백은 최소화하고, 모듈/서비스 메서드로 위임합니다.

## 상태 관리
- DashboardViewModel을 대시보드 상태의 단일 소스로 사용합니다.
- 환경설정 기반 상태는 ViewModel 메서드로 저장합니다.
- 불필요한 로컬 필드(병렬 상태)는 도입하지 않습니다.

## UI 구성
- 반복되는 버튼/토글/메뉴 생성은 재사용 가능한 헬퍼 빌더를 사용합니다.
- 버튼 스타일은 createActionButton / createIconActionButton 등으로 통일합니다.
- 인라인 스타일 문자열은 간결하게, 안정적인 스타일은 CSS로 분리합니다.

## 테마 및 시각 시스템
- 글로벌 테마 스택:
  - AtlantaFX PrimerLight (user-agent 스타일시트)
  - dashboard-theme.css (프로젝트 스타일시트)
- 사용 UI 라이브러리:
  - ControlsFX (고급 컨트롤/검증)
  - Ikonli MaterialDesign2 (아이콘)

## 인터랙션 패턴
- 드래그/리사이즈 동작은 반드시 DashboardInteractionService를 거칩니다.
- Snap/그리드 동작은 ViewModel 상태를 참조합니다.
- 위치/크기 업데이트는 릴리즈 이벤트에서만 저장합니다.

## 테스트 가이드라인
- 프론트엔드 변경 후 필수 체크:
  - gradlew :frontend:classes
  - gradlew :frontend:test
- UI 헬퍼/서비스 변경 시 단위 테스트 추가 또는 갱신
- 비동기 서비스 플로우는 예외 경로까지 명시적으로 검증

## 문서화 및 프로파일링
- docs/ENGINEERING_HANDOFF_2026-03-18.md에 진행 내역을 반영합니다.
- docs/PERFORMANCE_BASELINE.md에 최신 성능 지표를 유지합니다.
- scripts/profile-dashboard.ps1로 API 지연시간을 반복 측정합니다.
