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