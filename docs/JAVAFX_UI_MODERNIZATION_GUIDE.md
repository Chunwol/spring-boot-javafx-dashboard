# JavaFX Modernization Guide

## 더 쉬운 방식 (개발 생산성)
- 코드로 UI 전부 그리기 -> FXML + SceneBuilder 중심으로 전환 권장.
- 이유: 화면 구조/스타일/로직 분리, 협업성 향상, 파일 손상 리스크 감소.

## 더 세련된 방식 (디자인 품질)
- CSS 토큰화(색/간격/반경/타이포)를 먼저 정의.
- 공통 컴포넌트(툴바, 패널 헤더, 삭제 버튼, 토스트) 재사용.
- 상태별 시각 규칙(hover/selected/locked/error) 명확화.

## 추천 스택
1. ControlsFX: 실무형 컨트롤/유틸.
2. Ikonli: 아이콘 시스템 통일.
3. AtlantaFX 또는 BootstrapFX: 모던 테마 기반.

## 구조 권장
- View(FXML)
- ViewModel(상태/명령)
- Service(API, 저장)
- Controller는 바인딩/이벤트 중계만.

## 단계적 이행
1. DashboardController에서 정적 레이아웃을 FXML로 이동.
2. 편집툴바/좌측목록/캔버스를 컴포넌트화.
3. 패널 조작 로직을 ViewModel/Service로 분리.
4. 화면 회귀 체크리스트 + 스모크 테스트 자동화.
