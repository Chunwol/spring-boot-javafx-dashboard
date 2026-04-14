---

# 🖥️ 통합 PC 관제 및 제어 시스템 구축 명세서 (Final)

> **문서 갱신일**: 2026-03-30  
> **현재 상태**: 1~4단계 핵심 기능 구현 완료. 운영 품질 개선 및 보안 강화 진행 중.

## 1. 프로젝트 개요

* **목표:** 830대의 학생/강의실 PC를 중앙에서 실시간 모니터링하고, 대역대(L3) 제약을 넘어 원격 제어 및 소프트웨어 배포를 자동화함.
* **핵심 가치:** 무인 관리(Zero-Touch), 네트워크 효율성, 확장성, 데이터 기반 보고.

---

## 2. 기술 스택 (Technology Stack)

### A. 메인 관리 서버 (관리자 PC)

* **Framework:** Spring Boot 3.x + JavaFX 25 (AtlantaFX PrimerLight 테마)
* **Language:** Java 25
* **Database:** SQLite 3 (WAL 모드 활성화, Flyway V1~V19 마이그레이션)
* **Communication:** REST API (JSON), WebSocket/STOMP (실시간), SMTP (이메일)
* **Security:** JWT 인증, BCrypt 패스워드, 에이전트 토큰 기반 인증
* **Deployment:** Docker Compose (Nginx 리버스 프록시 + SSL) 또는 단독 JAR 실행

### B. 에이전트 (학생/교사 PC)

* **Language:** Java 25 (순수 Java, SLF4J 로깅)
* **Library:** OSHI (하드웨어/네트워크 정보), OkHttp (HTTP 클라이언트), Jackson (JSON)
* **Execution:** Fat JAR 또는 GraalVM Native Image (설정 포함)
* **Security:** 설치키(Install Key) 기반 자동 등록, 토큰(Agent Token) 기반 하트비트 인증

---

## 3. 상세 기능 명세 (System Features)

### 3.1. 에이전트 핵심 기능 ✅ 구현 완료

* **하트비트 보고:** 2분 주기(±30초 지터)로 생존 신호, IP/MAC, 호스트명, 에이전트 버전, 네트워크 트래픽(인바운드/아웃바운드 Kbps) 전송
* **네트워크 관제:** OSHI 기반 실시간 인바운드/아웃바운드 트래픽(Kbps) 수집 및 보고
* **원격 제어 수행:**
  * **전원 관리:** 종료(`shutdown /s /t 10`), 재부팅(`shutdown /r /t 10`)
  * **명령 실행:** `cmd.exe /c` 기반 임의 명령 실행 (30초 타임아웃)
  * **PS1 스크립트:** 서버에서 다운로드 → SHA-256 무결성 검증 → PowerShell 실행 (5분 타임아웃)
  * **WOL 릴레이:** 동일 대역대 내 다른 PC를 깨우기 위한 매직 패킷 중계
* **자동 등록:** 설치키 기반 최초 등록, 토큰 발급, 401/403 시 자동 재등록
* **환경 변수 오버라이드:** Docker 친화적 설정 (`AGENT_SERVER_URL`, `AGENT_TOKEN` 등)

### 3.2. 서버 핵심 기능 ✅ 구현 완료

* **실시간 대시보드 (JavaFX):** 강의실별 패널 시각화 (ON: 초록, OFF: 회색, 경고: 노란색/빨간색)
* **강의실 자동 매핑:** IP 대역(CIDR/start-end) 기반 PC → 강의실 자동 귀속 + 교사/학생 역할 자동 판별
* **명령 큐(Command Queue) v2:** 상태 추적(QUEUED → ACKED → COMPLETED/FAILED), ACK/Result 콜백
* **PS1 스크립트 배포:** 업로드 → SHA-256 해시 저장 → 에이전트 다운로드 + 무결성 검증
* **WOL 릴레이:** 강의실 내 온라인 에이전트를 자동 선정하여 매직 패킷 릴레이
* **이상 징후 감지:** 트래픽 임계치 초과 시 이메일 알림 (스케줄러 기반)
* **통계 보고서:** PDF 리포트 생성 (강의실별 가동률, 트래픽 요약, 명령 실행 이력)
* **감사 로그:** 로그인, 명령 발행/ACK/결과, 설정 변경 등 전체 감사 추적

---

## 4. 네트워크 및 통신 아키텍처

### 4.1. L3 대역대 돌파 (WOL 릴레이) ✅ 구현 완료

1. 관리자 PC(서버)는 대역대가 다른 PC를 직접 켤 수 없음.
2. 서버는 해당 강의실에서 이미 켜져 있는 에이전트에게 WOL_RELAY 명령 전달.
3. 릴레이 에이전트가 동일 대역대 내부에서 매직 패킷 브로드캐스트.

### 4.2. 통신 부하 최적화 ✅ 적용됨

* **데이터 크기:** 하트비트당 약 0.5KB 미만
* **랜덤 지터(Jitter):** 접속 시간을 ±30초 랜덤 분산하여 서버 병목 방지
* **Caffeine 캐시:** IP 대역 해석 결과 캐시로 하트비트 처리 속도 최적화
* **WebSocket/STOMP:** 실시간 상태 변경 푸시 (폴링 최소화)

---

## 5. 데이터베이스 설계 (SQLite, Flyway V1~V19)

| 테이블명 | 주요 데이터 |
| --- | --- |
| `pc_master` | MAC 주소, IP, 그룹ID, 역할(TEACHER/STUDENT), 상태, 에이전트 버전, 마지막 하트비트 |
| `classroom_groups` | 강의실 이름, 설명, 생성일 |
| `classroom_ip_ranges` | 그룹ID, CIDR, 시작IP, 종료IP, 설명 |
| `classroom_teacher_devices` | 그룹ID, IP, MAC (교사PC 식별) |
| `traffic_logs` | MAC, 타임스탬프, 인바운드/아웃바운드 Kbps |
| `command_queue` | taskId, MAC, 타입, 상태(QUEUED/ACKED/COMPLETED/FAILED), 결과, 표준출력/에러 |
| `client_positions` | MAC, IP, 그룹ID, 대시보드 좌표(x,y) |
| `dashboard_modules` | 패널 타입/설정/위치/크기 |
| `script_artifacts` | 원본파일명, 저장파일명, SHA-256, 크기 |
| `audit_logs` | 행위자, 행위, 대상, 상세, 시간 |
| `users` | 사용자명, 암호화 비밀번호, 역할, 표시명 |

---

## 6. 구현 완료 현황

| 단계 | 내용 | 상태 |
|------|------|------|
| **1단계** | 에이전트 기본 구현 + Spring Boot 서버 | ✅ 완료 |
| **2단계** | JavaFX 대시보드 UI + 실시간 상태 표출 | ✅ 완료 |
| **3단계** | 명령 큐 v2 + PS1 배포 + WOL 릴레이 | ✅ 완료 |
| **4단계** | PDF 보고서 + 이메일 알림 + 감사 로그 | ✅ 완료 |

## 7. 향후 로드맵

- [ ] CI/CD: GitHub Actions 자동 빌드/테스트
- [ ] Observability: Micrometer + Prometheus/Grafana
- [ ] 에이전트 자가 업데이트(OTA): 서버 기반 버전 관리 + 자동 다운로드
- [ ] 관리자/교사 권한 분리: 역할 기반 접근 제어(RBAC)
- [ ] 테스트 커버리지 확대: 단위/통합 테스트 추가

---

## 8. JavaFX Dashboard FXML 리팩토링 TODO

목표: 동적 패널 생성 로직은 유지하되, 정적 화면 골격과 반복 UI 영역부터 FXML로 분리해 DashboardController의 책임을 단계적으로 축소한다.

- [x] Phase 1. dashboard.fxml 루트 쉘 추출
- [x] Phase 1-1. 상단바 슬롯, 메인 콘텐츠 슬롯을 FXML에 정의
- [x] Phase 1-2. DashboardController가 슬롯에 기존 buildTopBar/buildTabs 결과를 주입하도록 전환
- [x] Phase 1-3. 컴파일 및 로그인 진입 경로 검증
- [x] Phase 2. 상단바 정적 구조 FXML 분리
- [x] Phase 2-1. 제목/서브타이틀/대시보드 선택 영역을 FXML 노드로 이동
- [x] Phase 2-2. 설정 메뉴와 액션 버튼의 이벤트 바인딩을 컨트롤러 메서드로 연결
- [x] Phase 2-3. 상태 라벨/시계 라벨 fx:id 정리
- [x] Phase 3. 탭 셸 FXML 분리
- [x] Phase 3-1. TabPane와 고정 탭 골격을 FXML로 이동
- [x] Phase 3-2. 각 탭 content는 코드 모듈 주입 방식 유지
- [x] Phase 3-3. 탭 선택 시 reload/refresh 리스너 재연결
- [x] Phase 4. 메인 대시보드 SplitPane 쉘 분리
- [x] Phase 4-1. 좌우 패널 슬롯과 툴바 래퍼를 FXML로 이동
- [x] Phase 4-2. Resource/Canvas/Toolbar 조립을 슬롯 주입 구조로 정리
- [x] Phase 5. 반복 위젯/다이얼로그 분리 후보 정리
- [x] Phase 5-1. 로그인 외 공통 다이얼로그의 FXML 전환 후보 선정
- [x] Phase 5-2. PC 관리/명령 상세/설정 다이얼로그의 분리 우선순위 수립
- [x] Phase 5-3. CommandStatusPanel showCommandDetail → command-detail-dialog-content.fxml 추출
- [x] Phase 5-4. DashboardWidgetModule handleAddTextItem → widget-text-add-dialog-content.fxml 추출
- [x] Phase 5-5. DashboardPcManagement showConfirmationDialog FXML 확인 (pc-command-confirm-dialog.fxml 기존 분리 적절)
- [x] Phase 6. TableView 컬럼 및 레이아웃 FXML 추출 (포괄적)
- [x] Phase 6-1. PcManagementModule.buildPcTable() → pcmgmt-pc-table.fxml (6컬럼)
- [x] Phase 6-2. CommandStatusPanelController.createCommandTable() → command-table.fxml (12컬럼)
- [x] Phase 6-3. NetworkPanelController.createPcTrafficTable() → network-traffic-table.fxml (5컬럼)
- [x] Phase 6-4. NetworkPanelController.createNetworkChart() → network-chart.fxml (LineChart)
- [x] Phase 6-5. ManagementModule.buildAgentInstallBox() → mgmt-agent-section.fxml 컬럼 확장 (8컬럼)
- [x] Phase 6-6. showConfirmationDialog 확인 테이블 → pc-command-confirm-dialog.fxml 확장 (4컬럼)
- [x] Phase 6-7. ManagementModule IP대역/교사/고장PC 액션 컬럼 → 각 FXML 컬럼 추가
- [x] Phase 6-8. DashboardController 상태바 → dashboard-statusbar.fxml (중복 제거)

---

## 9. 2026-04 확장 TODO (우선순위 1~5)

### 9.1. 대용량 전송 고도화 (`/file-chunks`, `/file-chunks/assemble`)

목표: 30GB+ 파일/폴더를 네트워크 불안정 환경에서도 재시도/재개 가능하게 안정 전송.

- [x] `POST /api/v1/file-chunks` 구현
- [x] 업로드 식별자(`uploadId`) 유효성 검증 및 경로 탈출(path traversal) 방지
- [x] 청크 단위 SHA-256 검증(`chunkHash`) 실패 시 즉시 거절
- [x] 동일 청크 재업로드 시 resume 처리(이미 같은 해시면 skip)
- [x] 업로드 메타데이터(`totalChunks`, 업로드된 인덱스, 해시/크기) 저장
- [x] `POST /api/v1/file-chunks/assemble` 구현
- [x] 누락 청크 감지 및 누락 목록 반환(재개 업로드 지원)
- [x] 조립 시 스트리밍 방식으로 최종 파일 생성(대용량 메모리 사용 방지)
- [x] 최종 파일 SHA-256 계산/검증 및 artifact 등록
- [x] 기존 파일 해시 중복 시 dedup 처리
- [x] 조립 완료 후 임시 청크 정리 및 매니페스트 생성
- [x] 대용량 업로드/조립 실패 시 복구 가능한 에러 코드/메시지 표준화
- [ ] API 스모크 테스트에 청크 경로 시나리오 추가

### 9.2. 권한 UX 정교화 (VIEWER 읽기 전용)

목표: VIEWER는 ‘조회만’ 가능한 명확한 UI/동선 제공.

- [x] VIEWER 전용 네비게이션 구성(불필요 탭 완전 숨김)
- [x] 명령/제어 관련 탭, 버튼, 단축키 완전 제거 또는 비노출
- [x] 읽기 전용 배지/상태 문구 일관 표시(상단/패널)
- [x] VIEWER에게 403 발생 가능한 액션 UI 사전 차단
- [x] API 에러 발생 시 권한 안내 문구 표준화
- [x] ADMIN/USER/VIEWER 역할별 화면 회귀 테스트 추가

### 9.3. USB 전용 편의 개선

목표: 원격 파일 탐색기에서 USB 장치를 빠르게 식별/선택/다운로드.

- [x] LIST_FILES 응답에 드라이브 라벨, 타입(USB/Fixed/Network), 총용량/여유용량 추가
- [x] 프론트 탐색기에서 ‘USB만 보기’ 필터 제공
- [x] 드라이브 목록 자동 새로고침(핫플러그 감지 주기 폴링)
- [x] USB 삽입/제거 이벤트에 대한 UI 토스트/배지 제공
- [x] 대용량 USB 경로 선택 시 경고/예상 시간 안내 표시
- [x] USB 경로 수집 시 오류(권한/잠금/분리) 상세 원인 표시

### 9.4. E2E 자동화 (CI + UI 회귀)

목표: 기능 추가 시 회귀를 자동 검출하고 배포 안정성 확보.

- [x] GitHub Actions 워크플로우 분리(`build`, `unit`, `integration`, `ui-smoke`)
- [x] 백엔드 API 스모크(로그인/에이전트등록/heartbeat/명령/다운로드) CI 편입
- [x] 프론트 JavaFX TestFX 기반 로그인/탭 전환/권한 UX 시나리오 자동화
- [x] 대용량 청크 업로드 시뮬레이션 테스트(부분 실패/재시도/재개)
- [x] 테스트 산출물(로그/스크린샷/리포트) 아티팩트 업로드
- [x] 실패 유형별 재현 가이드 자동 링크(README/문서 연결)

### 9.5. 운영 관측성 (메트릭/대시보드/알람)

목표: 명령/전송 실패와 지연을 실시간 감시하고 원인 추적 단축.

- [x] 앱 내 운영 알람 센터(우측 상단 아이콘/배지/실시간 토스트/알람 목록/ACK) 구현
- [ ] Micrometer 메트릭 추가(명령 전달 지연, ACK 지연, 결과 지연, 실패율)
- [ ] 파일 업로드/다운로드 처리량, 전송 시간, 실패 코드 메트릭 추가
- [ ] 역할별 요청량/403 비율/에러율 메트릭 추가
- [ ] Prometheus scrape endpoint/설정 정리
- [ ] Grafana 대시보드 템플릿(운영/장애대응) 추가
- [ ] 임계치 기반 알람 룰(지연 급증, 실패율 급증, 에이전트 급감) 정의
- [ ] 장애 대응 런북(runbook) 문서화
