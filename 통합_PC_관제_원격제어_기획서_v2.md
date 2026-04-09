# 통합 PC 관제 및 원격제어 시스템 기획서

**프로젝트명**: 강의실별 통합 대시보드 및 원격제어 시스템  
**버전**: v2.0  
**작성일**: 2026년 3월 18일

---

## 1. 프로젝트 개요

### 1.1 배경 및 목적

학교/학원 환경에서 다수의 PC(강의실당 30~40대, 총 830대 규모)를 물리적으로 순회하며 관리하는 방식은 시간·인력 낭비가 크다. 본 프로젝트는 네트워크 기반의 경량 에이전트를 각 PC에 설치하고, 중앙 관리 서버에서 실시간 상태 모니터링과 원격 제어(전원 관리, 소프트웨어 배포, PS1 실행)를 가능하게 하는 시스템을 구축한다.

### 1.2 목표

| 목표 | 설명 |
|------|------|
| **무인 관리 (Zero-Touch)** | 에이전트 자동 설치 후 서버 등록·분류를 자동화 |
| **Grafana형 자유 배치 대시보드** | 패널 카탈로그에서 원하는 패널을 골라 캔버스에 자유롭게 배치 |
| **강의실별 통합 뷰** | 강의실 IP 대역 기반으로 교사PC/학생PC를 자동 분류하여 패널 표시 |
| **일괄 원격 제어** | 강의실 단위 또는 개별 PC 종료·재부팅·WOL·명령 실행 |
| **PS1 배포 자동화** | 설치 스크립트를 서버에 업로드 → 강의실 일괄 배포·실행 |
| **L3 대역 WOL 릴레이** | 라우터를 넘어 꺼진 PC를 깨우기 위한 릴레이 에이전트 방식 |
| **주간/월간 PDF 보고서** | 트래픽·가동률 통계를 iText 기반 PDF로 자동 생성·출력 |

### 1.3 적용 규모

- 관리 대상 PC: **최대 830대** (강의실당 30~40대)
- 강의실 수: **최대 25개** (서비스 확장 가능)
- 관리 서버: 관리자 PC 1대 (단일 서버, 내장 SQLite WAL 모드)
- 관리자 단말: JavaFX 대시보드가 서버와 동일 JVM에서 실행

---

## 2. 기술 스택

| 구성요소 | 언어/프레임워크 | 버전 | 비고 |
|---------|--------------|------|------|
| **백엔드 서버** | Java + Spring Boot | 25 / 3.x | REST API, STOMP WebSocket |
| **대시보드 UI** | JavaFX + FxWeaver | 25 / 25 | 단일 JVM 통합 실행 |
| **데이터베이스** | SQLite (WAL 모드) | 3.x | Flyway 마이그레이션 |
| **에이전트** | Java 25 + GraalVM Native Image | CE 25 | agent.exe 단독 실행, JRE 불필요 |
| **에이전트 HW 수집** | OSHI 라이브러리 | 6.x | CPU/RAM/네트워크 수집 |
| **인증** | JWT (HS256) + Agent-Token | - | 관리자/에이전트 채널 분리 |
| **PDF 생성** | iText 7 | 7.x | 주간/월간 보고서 |
| **API 문서** | SpringDoc OpenAPI | 3.x | Swagger UI |
| **빌드** | Gradle 멀티 모듈 | 8.x | backend / frontend / common / agent |

---

## 3. 시스템 아키텍처

### 3.1 전체 구성도

```
                    ┌────────────────────────────────────────┐
                    │          관리자 PC (서버 + UI 통합)        │
                    │                                        │
                    │  ┌─────────────────────────────────┐  │
                    │  │   Spring Boot (REST + WebSocket) │  │
                    │  │   HeartbeatController            │  │
                    │  │   CommandController              │  │
                    │  │   ScriptController               │  │
                    │  │   DashboardApiController         │  │
                    │  │   ReportController               │  │
                    │  └──────────────┬──────────────────┘  │
                    │                 │                      │
                    │  ┌──────────────▼──────────────────┐  │
                    │  │   SQLite (WAL)                   │  │
                    │  │   pc_master / command_queue      │  │
                    │  │   traffic_logs / scripts         │  │
                    │  │   dashboard_panels               │  │
                    │  └──────────────────────────────────┘  │
                    │                                        │
                    │  ┌──────────────────────────────────┐  │
                    │  │   JavaFX 대시보드 (FxWeaver)       │  │
                    │  │   Grafana형 캔버스 + 패널 카탈로그  │  │
                    │  └──────────────────────────────────┘  │
                    └─────────────────┬──────────────────────┘
                                      │ HTTP REST + WebSocket
          ┌───────────────────────────┼────────────────────────┐
          │                           │                        │
┌─────────▼──────────┐   ┌────────────▼────────┐   ┌──────────▼─────────┐
│  101호 (10.0.1.0/24)│   │ 102호 (10.0.2.0/24) │   │  103호 ...          │
│  교사PC (에이전트)   │   │  교사PC (에이전트)   │   │                    │
│  학생PC×30 (에이전트)│   │  학생PC×30 (에이전트)│   │                    │
└─────────────────────┘   └─────────────────────┘   └────────────────────┘
```

### 3.2 에이전트 동작 흐름

```
[에이전트 시작]
      │
      ├─ 설정 파일 로드: %APPDATA%\\PCAgent\\config.json
      │     {serverUrl, agentToken, heartbeatIntervalSec, jitterSec}
      │
      ├─ 서버 연결 확인 GET /actuator/health
      │
      ├─ OTA 자가 업데이트 체크 (GitHub Releases API)
      │     최신 버전 감지 → 다운로드 → SHA256 검증 → 교체 → 재시작
      │
      └─ 하트비트 스케줄러 시작 (120초 + 랜덤 지터 ±30초)
              │
              ▼
    POST /api/v1/heartbeat
    {mac, hostname, ip, agentVersion, inboundKbps, outboundKbps, osInfo}
              │
              ▼
    서버 응답 {command: {taskId, type, payload}}
              │
    ┌─────────┼────────────────────────────────────────┐
    │         │                                        │
    ▼         ▼               ▼             ▼          ▼
SHUTDOWN  REBOOT       RUN_CMD        RUN_PS1     WOL_RELAY
 OS 종료   OS 재부팅   ProcessBuilder  다운로드→      UDP 9포트
                       실행            해시검증→      매직패킷
                                       PS1 실행       브로드캐스트
              │
              ▼
    POST /api/v1/agent/commands/{taskId}/result
    {exitCode, stdout(4KB), stderr(4KB), completedAt}
```

### 3.3 WOL 릴레이 흐름

```
관리자 UI ──▶ POST /api/v1/commands
             {scope:GROUP, groupId:2, type:WOL, targetMacs:[...]}
                    │
                    ▼
             CommandService
             102호에서 ONLINE 에이전트(교사PC 우선) 선정
             → command_queue에 WOL_RELAY 삽입
               {targetMacs:[...], broadcastIp:"10.0.2.255"}
                    │ (다음 하트비트 응답으로 전달)
                    ▼
             릴레이 에이전트 (102호 교사PC)
                    │ UDP 9포트 브로드캐스트
                    └─▶ 102호 학생 PC들 순차 부팅
```

---

## 4. Grafana형 대시보드 시스템 상세 설계

### 4.1 개념: 패널 카탈로그 + 자유 배치 캔버스

Grafana처럼 **패널 카탈로그 목록**에서 원하는 패널 유형을 선택해 캔버스에 추가하고, 드래그&리사이즈로 자유롭게 배치한 뒤 레이아웃을 서버에 저장하는 방식이다.

```
┌──────────────────────────────────────────────────────────────────────┐
│  상단 툴바: [대시보드 선택 ▼]  [패널 추가 +]  [수정모드 🔓]  [PDF 출력]  │
├────────────┬─────────────────────────────────────────────────────────┤
│            │                                                         │
│  패널      │              캔버스 (자유 배치 영역)                        │
│  카탈로그  │  ┌──────────┐ ┌──────────┐ ┌──────────┐                │
│  ─────────│  │ 101호 현황│ │ 102호 현황│ │ 103호 현황│                │
│  🖥 강의실  │  │ ❤️ 하트비트│ │ ❤️ 하트비트│ │ ❤️ 하트비트│                │
│    하트비트 │  │ 학생 28/30│ │ 학생 15/30│ │ 학생 30/30│                │
│  📶 네트워크│  │ [제어 ▼] │ │ [제어 ▼] │ │ [제어 ▼] │                │
│    트래픽  │  └──────────┘ └──────────┘ └──────────┘                │
│  🅣 텍스트  │                                                         │
│  🖼 이미지  │  ┌──────────────────────┐  ┌──────────┐                │
│  📊 차트   │  │   101호 트래픽 차트    │  │ 공지사항  │                │
│            │  │  ↑──────────────────  │  │ Text 위젯│                │
│            │  │  └────────────────── │  └──────────┘                │
│            │  └──────────────────────┘                               │
└────────────┴─────────────────────────────────────────────────────────┘
```

### 4.2 패널 유형 및 최대 수량

| 패널 유형 | 최대 수량 | 데이터 소스 | 주요 표시 내용 |
|---------|---------|-----------|-------------|
| **강의실 하트비트 패널** | 18개 (강의실 수만큼) | GET /api/v1/dashboard/classrooms/{id} | 교사PC 상태, 학생 ON/OFF 수, 마지막 하트비트, 제어 버튼 |
| **네트워크 트래픽 패널** | 18개 (강의실당 1개) | GET /api/v1/dashboard/classrooms/{id}/traffic | 인바운드/아웃바운드 실시간 라인 차트 (최근 10분) |
| **전체 네트워크 요약 패널** | 1개 | GET /api/v1/dashboard/traffic/summary | 전체 트래픽 합산, 임계 초과 표시 |
| **텍스트 위젯 패널** | 무제한 | 로컬 DB | 자유 입력 공지/메모 |
| **이미지 위젯 패널** | 무제한 | 로컬 DB (URL or 업로드) | 이미지/로고 표시 |
| **명령 진행 현황 패널** | 1개 | GET /api/v1/commands?status=QUEUED,DELIVERED | 현재 진행 중인 명령 목록과 완료율 |

### 4.3 패널 추가 흐름 (Grafana 방식)

```
[+ 패널 추가] 버튼 클릭
        │
        ▼
┌──────────────────────────────────┐
│   패널 유형 선택 다이얼로그         │
│                                  │
│  ○ 강의실 하트비트 → 강의실 선택   │
│    [101호] [102호] [103호] ...    │
│                                  │
│  ○ 네트워크 트래픽 → 강의실 선택  │
│    [101호] [102호] [전체 요약]    │
│                                  │
│  ○ 텍스트 위젯                    │
│  ○ 이미지 위젯                    │
│  ○ 명령 진행 현황                  │
│                                  │
│              [추가]  [취소]       │
└──────────────────────────────────┘
        │ 확인
        ▼
  POST /api/v1/dashboards/{id}/panels
  {type: "CLASSROOM_HEARTBEAT", groupId: 1,
   x: 50, y: 50, width: 280, height: 220}
        │
        ▼
  캔버스에 패널 즉시 렌더링
  (드래그로 위치 조정, 리사이즈 핸들로 크기 조정)
        │
        ▼
  마우스 놓는 순간 위치 자동 저장
  PUT /api/v1/dashboards/{id}/panels/{panelId}
  {x, y, width, height}
```

### 4.4 다중 대시보드 지원

```
[대시보드 선택 ▼]
  ├─ 메인 대시보드 (기본)
  ├─ 1층 강의실 현황
  ├─ 2층 강의실 현황
  ├─ 트래픽 모니터링 전용
  └─ [+ 새 대시보드 추가]

각 대시보드는 독립적인 패널 집합을 가짐
DB: dashboards (id, name, created_at) + dashboard_panels (dashboard_id, ...)
```

### 4.5 레이아웃 저장 메커니즘

기존 classroom_groups.dashboard_x/y, dashboard_widget_items.x/y 방식을 **통합 dashboard_panels 테이블**로 일원화한다.

```
dashboard_panels 테이블
  id (PK, UUID)
  dashboard_id   → dashboards.id
  panel_type     : CLASSROOM_HEARTBEAT | NETWORK_TRAFFIC | TEXT | IMAGE | COMMAND_STATUS
  ref_group_id   : 강의실 패널의 경우 해당 그룹 ID (null 허용)
  config_json    : 패널별 추가 설정 (색상, 차트 기간 등)
  x, y           : 캔버스 상 좌표
  width, height  : 패널 크기
  z_index        : 레이어 순서
  visible        : 표시 여부
  created_at, updated_at
```

### 4.6 Edit Mode (캔버스 편집)

| 기능 | 조작 |
|------|------|
| Edit Mode 진입 | 상단 🔓 버튼 클릭 |
| 패널 드래그 이동 | 패널 헤더 드래그 |
| 패널 리사이즈 | 패널 우측하단 핸들 드래그 |
| 패널 삭제 | Edit Mode에서 패널 우상단 ✕ 아이콘 |
| 격자 스냅 | 8px 그리드 스냅 (토글 가능) |
| 정렬 | 좌/우/상/하/가운데 정렬 버튼 |
| Edit Mode 종료 | 🔒 버튼 클릭 → 레이아웃 일괄 저장 |

---

## 5. 데이터베이스 설계

### 5.1 전체 ERD (텍스트)

```
users ──────────────────────────────────────────────────────────────
  PK: id | username(UNIQUE), password_hash, email, role, created_at

dashboards ──────────────────────────────────────────────────────────
  PK: id
  name (예: "메인 대시보드"), description, display_order
  created_by → users.id, created_at

dashboard_panels ────────────────────────────────────────────────────
  PK: id (UUID)
  FK: dashboard_id → dashboards.id
  panel_type: CLASSROOM_HEARTBEAT|NETWORK_TRAFFIC|TEXT|IMAGE|COMMAND_STATUS
  FK: ref_group_id → classroom_groups.id (nullable)
  config_json : 패널별 설정
  x, y, width, height, z_index, visible
  created_at, updated_at

classroom_groups ────────────────────────────────────────────────────
  PK: id | name(UNIQUE), description, created_at
  1:N → classroom_ip_ranges
  1:N → classroom_teacher_devices
  1:N → pc_master

classroom_ip_ranges ─────────────────────────────────────────────────
  PK: id | FK: group_id | cidr, start_ip, end_ip, description

classroom_teacher_devices ───────────────────────────────────────────
  PK: id | FK: group_id | ip_address, mac_address, description

pc_master ───────────────────────────────────────────────────────────
  PK: mac_address
  pc_name, ip_address, os_info, agent_version
  FK: group_id → classroom_groups.id
  role: TEACHER|STUDENT|UNKNOWN
  status: ONLINE|OFFLINE
  last_seen, created_at

agent_auth_tokens ───────────────────────────────────────────────────
  PK: id
  FK: mac_address → pc_master.mac_address (UNIQUE)
  token_hash (SHA-256 hex, UNIQUE), issued_at, last_used_at, revoked

traffic_logs ────────────────────────────────────────────────────────
  PK: log_id | FK: mac_address | inbound_kbps, outbound_kbps, logged_at

command_queue ───────────────────────────────────────────────────────
  PK: task_id (UUID) | FK: mac_address
  command_type: SHUTDOWN|REBOOT|RENAME_PC|RUN_CMD|RUN_PS1|WOL_RELAY
  status: QUEUED|DELIVERED|ACKED|COMPLETED|FAILED
  target_scope: SINGLE|GROUP|ROLE
  group_id, role_filter, payload_json
  exit_code, stdout(4KB), stderr(4KB)
  retry_count, max_retries
  created_at, delivered_at, acked_at, completed_at

scripts ─────────────────────────────────────────────────────────────
  PK: id (UUID)
  original_filename, stored_filename (UUID 기반)
  sha256_hash, size_bytes, content_type
  description, uploaded_by, uploaded_at

audit_logs ──────────────────────────────────────────────────────────
  PK: id | actor_username, action, target_type, target_id
  detail_json, ip_address, created_at
```

### 5.2 Flyway 마이그레이션 계획

| 버전 | 파일명 | 내용 |
|------|--------|------|
| V1 | V1__init.sql | 기본 테이블 (기존) |
| V2 | V2__classroom_ranges_and_roles.sql | IP 대역/교사PC 테이블, pc_master.role (기존) |
| V3 | V3__command_queue_v2.sql | 명령 큐 상태 확장 (기존) |
| V4 | V4__scripts.sql | 스크립트 저장소 (기존) |
| V5 | V5__dashboard_widget_items.sql | 레거시 위젯 아이템 (기존) |
| V6 | V6__init_data.sql | 초기 데이터 (기존) |
| **V7** | V7__dashboards_and_panels.sql | dashboards + dashboard_panels 통합 테이블 신규 |
| **V8** | V8__agent_auth_tokens.sql | 에이전트 전용 인증 토큰 테이블 신규 |
| **V9** | V9__audit_logs.sql | 감사 로그 테이블 신규 |

---

## 6. API 명세 (전체)

### 6.1 인증 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | /api/v1/auth/login | 관리자 로그인, JWT 발급 | 없음 |
| POST | /api/v1/auth/refresh | 토큰 갱신 | Bearer JWT |

### 6.2 에이전트 인증 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | /api/v1/agent/register | 최초 등록, 에이전트 토큰 발급 | X-Agent-Install-Key 헤더 |
| POST | /api/v1/heartbeat | 하트비트 전송, 명령 수령 | X-Agent-Token 헤더 |
| POST | /api/v1/agent/commands/{taskId}/ack | 명령 수신 확인 | X-Agent-Token |
| POST | /api/v1/agent/commands/{taskId}/result | 명령 실행 결과 업로드 | X-Agent-Token |
| GET | /api/v1/scripts/{id}/download | PS1 다운로드 | X-Agent-Token |

### 6.3 대시보드 레이아웃 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | /api/v1/dashboards | 대시보드 목록 | JWT |
| POST | /api/v1/dashboards | 새 대시보드 생성 | JWT |
| PUT | /api/v1/dashboards/{id} | 대시보드 이름/순서 수정 | JWT |
| DELETE | /api/v1/dashboards/{id} | 대시보드 삭제 | JWT |
| GET | /api/v1/dashboards/{id}/panels | 패널 목록 + 위치 | JWT |
| POST | /api/v1/dashboards/{id}/panels | 패널 추가 | JWT |
| PUT | /api/v1/dashboards/{id}/panels/{panelId} | 패널 위치/크기/설정 수정 | JWT |
| DELETE | /api/v1/dashboards/{id}/panels/{panelId} | 패널 삭제 | JWT |

### 6.4 강의실 데이터 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | /api/v1/groups | 강의실 목록 | JWT |
| POST | /api/v1/groups | 강의실 생성 | JWT |
| PUT | /api/v1/groups/{id} | 강의실 수정 | JWT |
| DELETE | /api/v1/groups/{id} | 강의실 삭제 | JWT |
| GET | /api/v1/groups/{id}/ip-ranges | IP 대역 목록 | JWT |
| POST | /api/v1/groups/{id}/ip-ranges | IP 대역 추가 (CIDR 또는 start-end) | JWT |
| DELETE | /api/v1/groups/{id}/ip-ranges/{rangeId} | IP 대역 삭제 | JWT |
| GET | /api/v1/groups/{id}/teachers | 교사 PC 목록 | JWT |
| POST | /api/v1/groups/{id}/teachers | 교사 PC 등록 (IP/MAC) | JWT |
| DELETE | /api/v1/groups/{id}/teachers/{deviceId} | 교사 PC 삭제 | JWT |
| GET | /api/v1/dashboard/classrooms | 강의실 요약 목록 | JWT |
| GET | /api/v1/dashboard/classrooms/{id}/pcs | PC 목록 (역할/상태) | JWT |
| GET | /api/v1/dashboard/classrooms/{id}/traffic | 강의실 트래픽 시계열 | JWT |
| GET | /api/v1/dashboard/traffic/summary | 전체 트래픽 요약 | JWT |

### 6.5 명령 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | /api/v1/commands | 명령 발행 (단일/그룹/역할) | JWT |
| GET | /api/v1/commands/{taskId} | 명령 상태 조회 | JWT |
| GET | /api/v1/commands?groupId=&status= | 명령 목록 | JWT |

### 6.6 스크립트 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| POST | /api/v1/scripts | PS1 업로드 (multipart) | JWT |
| GET | /api/v1/scripts | 업로드 목록 | JWT |
| DELETE | /api/v1/scripts/{id} | 삭제 | JWT |
| GET | /api/v1/scripts/{id}/download | 다운로드 (에이전트용) | Agent-Token |

### 6.7 보고서 API

| 메서드 | 엔드포인트 | 설명 | 인증 |
|--------|-----------|------|------|
| GET | /api/v1/reports/pdf?type=WEEKLY | 주간 PDF 보고서 생성·반환 | JWT |
| GET | /api/v1/reports/pdf?type=MONTHLY&year=2026&month=3 | 월간 PDF | JWT |
| GET | /api/v1/reports/traffic?from=&to=&groupId= | 트래픽 JSON 집계 | JWT |
| GET | /api/v1/reports/uptime?from=&to=&groupId= | 가동률 JSON | JWT |

---

## 7. 에이전트 상세 기능 명세

### 7.1 구성 및 빌드

- **언어**: Java 25
- **빌드**: Gradle + GraalVM Native Image Plugin
- **라이브러리**: OSHI 6.x, OkHttp 4.x, Jackson 2.x
- **배포 형태**: agent.exe (단일 실행 파일, 약 30~60 MB)
- **Gradle 모듈**: agent/build.gradle — 별도 서브프로젝트

### 7.2 에이전트 등록 프로세스 (5단계)

#### Phase 1: 설치 전 준비 (관리자)

```
1. 관리자가 JavaFX 대시보드 → [설정] → [에이전트 설치키 확인]
   서버가 발급한 환경 공유 설치키 (Install-Key) 표시
   예: INST-A1B2C3D4E5F6

2. agent.exe + install.bat + config.json 을 배포 패키지로 묶음
```

config.json (초기 배포본):

```json
{
  "serverUrl": "http://192.168.0.1:8080",
  "installKey": "INST-A1B2C3D4E5F6",
  "agentToken": "",
  "heartbeatIntervalSec": 120,
  "jitterSec": 30
}
```

#### Phase 2: 에이전트 설치 (각 PC)

```batch
:: install.bat (관리자 권한 실행)
xcopy /Y agent.exe "C:\Program Files\PCAgent\"
xcopy /Y config.json "C:\Program Files\PCAgent\"
sc create "PCAgent" binPath= "C:\Program Files\PCAgent\agent.exe" ^
    DisplayName= "PC Management Agent" start= auto
sc start "PCAgent"
netsh advfirewall firewall add rule name="PCAgent Outbound" ^
    dir=out action=allow protocol=TCP remoteport=8080
```

#### Phase 3: 자동 등록 (에이전트 최초 기동)

```
에이전트 최초 시작
    │
    ├─ config.json 로드 → agentToken 비어 있음 확인
    ├─ OSHI로 MAC 주소 수집
    │
    ▼
POST /api/v1/agent/register
Headers: X-Agent-Install-Key: INST-A1B2C3D4E5F6
Body: {
  "mac": "AA:BB:CC:DD:EE:FF",
  "hostname": "PC-101-01",
  "ip": "10.0.1.10",
  "osInfo": "Windows 11 23H2",
  "agentVersion": "1.0.0"
}
    │
    ▼ (서버 처리)
1. Install-Key 유효성 검증
2. MAC 주소 중복 확인
3. pc_master 신규 등록 (status: OFFLINE)
4. UUID 기반 에이전트 토큰 생성 → agent_auth_tokens 저장
    │
    ▼ (서버 응답)
HTTP 201
{
  "agentToken": "tok_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "message": "등록 완료. 하트비트를 시작합니다."
}
    │
    ▼ (에이전트)
config.json의 agentToken 필드에 저장
이후 모든 API 호출: X-Agent-Token: tok_xxxx
```

#### Phase 4: 서버 측 등록 확인 (관리자)

```
대시보드 → [설정] → [등록된 에이전트 목록]
  ─────────────────────────────────────────
  PC명       │  IP          │  상태   │ 등록일
  PC-101-01  │ 10.0.1.10   │ OFFLINE │ 03-18 09:01
  PC-101-02  │ 10.0.1.11   │ ONLINE  │ 03-18 09:02
  ─────────────────────────────────────────
  [토큰 폐기]  [강의실 재배정]  [강제 삭제]

IP 대역이 설정된 강의실이 있으면,
다음 하트비트 수신 시 ClassroomResolutionService가
자동으로 group_id와 role(TEACHER/STUDENT) 결정
```

#### Phase 5: 토큰 갱신 및 폐기

```
토큰 폐기:
  대시보드 → [토큰 폐기]
  PUT /api/v1/admin/agents/{mac}/revoke
  → agent_auth_tokens.revoked = true
  → 이후 하트비트 401 응답

에이전트 재등록:
  401 수신 → config.json의 agentToken = ""
  → Phase 3 재등록 플로우 재시작
  (단, 관리자가 설치키를 재발급해야 가능)
```

### 7.3 하트비트 수집 항목

| 항목 | OSHI API | 비고 |
|------|----------|------|
| MAC 주소 | NetworkInterface.getMacAddr() | Primary NIC |
| IP 주소 | NetworkInterface.queryNetworkInterfaces() | IPv4 우선 |
| 호스트명 | SystemInfo.operatingSystem.networkParams | |
| OS 정보 | OperatingSystem.toString() | |
| 인바운드 Kbps | NetworkIF.getBytesRecv() 델타/초 × 8 / 1000 | |
| 아웃바운드 Kbps | NetworkIF.getBytesSent() 델타/초 × 8 / 1000 | |
| 에이전트 버전 | 빌드 시 상수 (build.properties) | |

### 7.4 명령 처리 상세

#### SHUTDOWN / REBOOT

```
shutdown /s /t 10 /c "원격 종료 명령"   (SHUTDOWN)
shutdown /r /t 10                       (REBOOT)
```

#### RUN_PS1 무결성 검증 포함

```
1. GET /api/v1/scripts/{id}/download → 임시 파일 저장
2. SHA-256 계산 → payload.sha256와 비교 → 불일치 시 FAILED 반환
3. powershell.exe -ExecutionPolicy Bypass -Scope Process -File <path> <args>
4. 표준출력/오류 4KB 제한으로 result API 업로드
5. 임시 파일 삭제
```

#### WOL_RELAY

```java
byte[] magic = new byte[102];
for (int i = 0; i < 6; i++) magic[i] = (byte)0xFF;
for (int i = 1; i <= 16; i++)
    System.arraycopy(macBytes, 0, magic, i*6, 6);
DatagramSocket socket = new DatagramSocket();
socket.setBroadcast(true);
socket.send(new DatagramPacket(magic, 102,
    InetAddress.getByName(broadcastIp), 9));
```

### 7.5 자가 업데이트 (OTA)

```
주기: 12시간마다 또는 서버 응답 헤더 X-Agent-Update: true 수신 시

1. GET https://api.github.com/repos/{owner}/{repo}/releases/latest
2. 현재 버전 < latestVersion → 업데이트 진행
3. agent.exe 다운로드 → SHA256 검증
4. agent.exe → agent.exe.bak 백업
5. cmd /c "timeout 3 && move agent_new.exe agent.exe && net start PCAgent"
```

---

## 8. 주간/월간 PDF 보고서 상세 설계

### 8.1 보고서 구성 (iText 7 기반)

```
┌──────────────────────────────────────────────────────┐
│              PC 관제 시스템 주간 보고서                 │
│          기간: 2026-03-11 ~ 2026-03-17               │
│          생성: 2026-03-18 09:00 | 담당자: admin       │
├──────────────────────────────────────────────────────┤
│  1. 요약                                               │
│     총 PC 수: 830 | 평균 가동률: 94.2%                  │
│     총 트래픽: 인바운드 1.2TB / 아웃바운드 0.8TB           │
│     이상 감지: 3건 (임계치 초과)                          │
├──────────────────────────────────────────────────────┤
│  2. 강의실별 가동률                                      │
│     ┌───────┬──────┬──────┬──────┬──────┐            │
│     │ 강의실 │ 총PC │ON평균│가동률│이상  │            │
│     ├───────┼──────┼──────┼──────┼──────┤            │
│     │ 101호 │  30  │  28  │ 93%  │  1건 │            │
│     │ 102호 │  35  │  33  │ 94%  │  0건 │            │
│     └───────┴──────┴──────┴──────┴──────┘            │
├──────────────────────────────────────────────────────┤
│  3. 일별 트래픽 차트 (라인 그래프)                        │
│     SVG → iText 변환, 인바운드/아웃바운드 구분            │
├──────────────────────────────────────────────────────┤
│  4. 명령 실행 이력 요약                                   │
│     총 명령: 47건 | 성공: 44 | 실패: 3                    │
│     유형별: SHUTDOWN×20, RUN_PS1×15, REBOOT×12       │
├──────────────────────────────────────────────────────┤
│  5. 이상 감지 상세                                       │
│     날짜·시각 | 강의실 | PC명 | 이상 내용                  │
└──────────────────────────────────────────────────────┘
```

### 8.2 PDF 생성 흐름

```
대시보드 [PDF 출력] 버튼 클릭
    │
    ▼
보고서 종류 선택 다이얼로그
  ○ 주간 보고서 (이번 주 / 지난 주)
  ○ 월간 보고서 (연도/월 선택)
  [생성]
    │
    ▼ GET /api/v1/reports/pdf?type=WEEKLY
    │
    ▼ (백엔드 ReportService)
1. 기간 내 pc_master 스냅샷 집계
2. traffic_logs 일별 집계 (dailyInboundAggregation)
3. command_queue 실행 이력 집계
4. iText PdfDocument 생성
   → 표지, 요약 테이블, SVG 라인 차트, 상세 테이블
5. byte[] 반환 (Content-Type: application/pdf)
    │
    ▼ (프론트엔드 DashboardPdfService)
FileChooser → 저장 경로 선택
→ byte[] → FileOutputStream 저장
→ 저장 완료 알림
```

### 8.3 ReportService 주요 메서드

| 메서드 | 설명 |
|--------|------|
| generatePdfReport(type, from, to) | 기간 지정 PDF 생성 |
| dailyInboundAggregation(logs) | 일별 인바운드 합산 |
| computeUsageByTimeSlot(logs) | 아침/오후/야간별 트래픽 |
| buildSvgLineChart(Map<LocalDate, Long>) | SVG 라인 차트 → iText 삽입 |
| computeUptimeByGroup(from, to) | 강의실별 가동률 계산 |
| summarizeCommandHistory(from, to) | 명령 실행 이력 집계 |

---

## 9. JavaFX 대시보드 UI 명세

### 9.1 화면 전체 구성

```
로그인 화면 → 메인 대시보드
  ├─ 상단 툴바
  │   ├─ [대시보드 선택 ▼]  (다중 대시보드 전환)
  │   ├─ [+ 패널 추가]      (패널 카탈로그 다이얼로그)
  │   ├─ [수정 모드 🔓/🔒]
  │   ├─ [PDF 출력]
  │   └─ admin | [로그아웃]
  │
  ├─ 좌측 사이드바 (토글 가능)
  │   ├─ 패널 목록 (현재 대시보드)
  │   └─ 강의실 설정 (IP 대역, 교사PC 등록)
  │
  └─ 캔버스 영역 (스크롤 가능)
      패널들이 자유 배치
```

### 9.2 강의실 하트비트 패널

```
┌──────────────────────────────────┐
│  101호  [ON] ────────────────── ✕│  ← Edit Mode에서 ✕
├──────────────────────────────────┤
│  교사 PC  ● ONLINE (10.0.1.1)    │
│  학생 PC  28 / 30 ONLINE          │
│  ━━━━━━━━━━━━━━━━━════════       │  ← 온라인 비율 프로그레스 바
│  마지막 하트비트: 09:01:45         │
│  ↑ 2.4 Mbps  ↓ 1.1 Mbps          │
├──────────────────────────────────┤
│  [PC 목록]  [제어▼]               │
└──────────────────────────────────┘

[제어▼] 드롭다운:
  ├─ 학생 PC 종료
  ├─ 전체 PC 종료
  ├─ 전체 재부팅
  ├─ WOL (꺼진 PC 켜기)
  ├─ 명령 실행 (CMD 입력)
  └─ PS1 배포
```

### 9.3 네트워크 트래픽 패널

```
┌──────────────────────────────────┐
│  101호 트래픽  [10분] [1시간] [1일]  │
├──────────────────────────────────┤
│  ↑ Mbps                          │
│  5│   ╱╲                         │
│  4│  ╱  ╲    ╱╲                  │
│  3│ ╱    ╲──╱  ╲                 │
│  2│╱                             │
│   └──────────────────────▶ 시간   │
│  ── 인바운드  ── 아웃바운드          │
└──────────────────────────────────┘
```

### 9.4 명령 진행 현황 패널

```
┌──────────────────────────────────────┐
│  명령 진행 현황                          │
├──────────────────────────────────────┤
│  RUN_PS1 → 101호 학생                 │
│  진행률: ████████░░ 44/50            │
│  완료: 44  실패: 2  대기: 4           │
│                                      │
│  SHUTDOWN → 102호 전체             │
│  진행률: ██████████ 35/35 ✓          │
└──────────────────────────────────────┘
```

---

## 10. 보안 설계

### 10.1 인증 채널 분리

```
관리자 ──────── JWT Bearer ──────── 관리자 API
에이전트 ─── X-Agent-Token ──── 에이전트 API
```

### 10.2 에이전트 토큰 보안

- 최초 등록 시 X-Agent-Install-Key로 서버가 발급 (관리자가 재발급 가능)
- 발급 토큰은 SHA-256 해시 후 DB 저장 (원문 1회 응답, 이후 폐기)
- 단방향 해시 비교 검증 → DB 유출 시에도 원문 복원 불가

### 10.3 OWASP Top 10 대응

| 위협 | 대응 방안 |
|------|---------|
| SQL Injection | JPA 파라미터 바인딩 전용, 네이티브 쿼리 원칙적 금지 |
| Command Injection | RUN_CMD 명령어를 화이트리스트 패턴 검증 또는 배포 스크립트로만 제한 |
| Path Traversal | 스크립트 저장을 UUID 파일명으로, 다운로드 URL에 원본 파일명 미포함 |
| 인증 실패 | JWT 만료(8h) + 에이전트 토큰 폐기 API |
| 스크립트 위변조 | SHA-256 서버 저장 → 에이전트 다운로드 후 재검증 후 실행 |
| 감사 추적 | 모든 명령 발행·결과를 audit_logs 기록 |
| SSRF | 에이전트 다운로드 URL을 서버 내부 경로만 허용 |

---

## 11. 운영 및 배포 시나리오

### 11.1 초기 설치

```
[관리 서버]
1. server.jar 실행 (포트 8080)
2. Flyway 자동 마이그레이션 (V1~V9)
3. admin 계정 자동 생성
4. JavaFX 대시보드 로드 → 로그인

[강의실 설정]
1. 대시보드 → [설정] → [강의실 추가]
2. 이름: 101호, CIDR: 10.0.1.0/24
3. 교사PC 등록: IP 10.0.1.1 또는 MAC
4. 설치키 확인: INST-A1B2C3D4E5F6

[에이전트 배포]
1. 배포 패키지(agent.exe + config.json + install.bat) 배포
2. 각 PC에서 install.bat 실행 (또는 GPO 자동 배포)
3. 서비스 시작 → 2분 내 대시보드에 자동 등록
```

### 11.2 소프트웨어 일괄 배포

```
1. 대시보드 → [스크립트 관리] → install_chrome.ps1 업로드
2. 강의실 배포 → 101호 → 학생만 → 실행
3. 명령 진행 현황 패널에서 완료율 실시간 확인
4. 완료 후 명령 이력에서 PC별 성공/실패 로그 확인
```

### 11.3 수업 종료 시 일괄 종료

```
1. 대시보드 → 101호 패널 → [제어▼] → 학생 PC 종료
2. 명령 진행 현황 패널에서 진행 상태 확인
```

---

## 12. 개발 일정 (마일스톤)

### 12.1 Phase별 개발 계획 (총 8주, 2인 기준)

| Phase | 기간 | 주요 작업 |
|------|------|---------|
| **Phase 1** | Week 1~2 | agent 서브모듈, GraalVM 빌드, Heartbeat 전송, 자동 등록 API, V7~V9 마이그레이션, install.bat 패키징 |
| **Phase 2** | Week 3~4 | RUN_PS1 + SHA256, WOL_RELAY, OTA 자가 업데이트, 에이전트 토큰 폐기/재등록, 감사 로그 연동 |
| **Phase 3** | Week 5~6 | Grafana형 캔버스 리팩터링, dashboard_panels 통합 API+UI, 패널 카탈로그, 다중 대시보드, 강의실 패널 완성, 진행 현황 패널 |
| **Phase 4** | Week 7~8 | PDF 보고서 완성, 이메일 알림, 830대 부하 테스트, SQLite 튜닝, 배포 패키징, 문서화 |

### 12.2 마일스톤

| 마일스톤 | 달성 기준 | 목표 시점 |
|---------|---------|---------|
| **M1: 에이전트 Alpha** | agent.exe 실행 → 서버 자동 등록 → 하트비트 수신 확인 | Week 2 |
| **M2: 명령 체계 완성** | 대시보드에서 종료·PS1 배포·WOL 동작 확인 | Week 4 |
| **M3: 대시보드 완성** | 18개 패널 자유 배치, 진행 상태 실시간 표시 | Week 6 |
| **M4: 보고서 + 릴리즈** | 주간/월간 PDF 정상 생성, 830대 부하 테스트 통과 | Week 8 |

---

## 13. 현재 구현 완료 현황

| 기능 | 상태 | 담당 파일 |
|------|------|---------|
| 백엔드 REST API 기본 구조 | ✅ 완료 | Spring Boot 전체 |
| JWT 인증 | ✅ 완료 | AuthController, JwtUtil |
| Heartbeat 수신 + PC 자동 등록 | ✅ 완료 | HeartbeatController |
| IP 대역 → 강의실 자동 매핑 | ✅ 완료 | ClassroomResolutionService |
| 교사PC/학생PC 역할 분류 | ✅ 완료 | V2 마이그레이션 |
| 명령 큐 v2 (상태 추적) | ✅ 완료 | V3 마이그레이션 |
| PS1 업로드/다운로드/해시 검증 | ✅ 완료 | ScriptController |
| WOL 릴레이 명령 발행 | ✅ 완료 | CommandService |
| 강의실 요약/PC 목록 API | ✅ 완료 | DashboardApiController |
| JavaFX 로그인 화면 | ✅ 완료 | LoginController |
| 드래그&리사이즈 기반 캔버스 | ✅ 완료 | DashboardCanvasModule |
| 텍스트/이미지 위젯 CRUD | ✅ 완료 | DashboardWidgetModule |
| 강의실 패널 기본 렌더링 | 🔄 진행 중 | DashboardClassroomModule |
| Grafana형 패널 카탈로그 | ⏳ 미착수 | Phase 3 |
| 다중 대시보드 지원 | ⏳ 미착수 | Phase 3, V7 마이그레이션 |
| 에이전트 모듈 (agent) | ⏳ 미착수 | Phase 1 |
| 에이전트 자동 등록 API | ⏳ 미착수 | Phase 1, V8 마이그레이션 |
| 감사 로그 | ⏳ 미착수 | Phase 2, V9 마이그레이션 |
| PDF 보고서 완성 | 🔄 기반 있음 | Phase 4, ReportService 확장 |
| 이메일 알림 | ⏳ 미착수 | Phase 4 |
| OTA 자가 업데이트 | ⏳ 미착수 | Phase 2 |

---

## 14. 제약 사항 및 전제 조건

| 항목 | 내용 |
|------|------|
| WOL | NIC Wake on LAN BIOS 옵션 활성화 필수. 릴레이 에이전트로 L3 대역 극복 |
| PS1 실행 권한 | 에이전트가 LocalSystem 또는 관리자 계정 서비스로 실행 필요. Defender 예외 등록 권장 |
| GraalVM Native Image | OSHI 리플렉션 대상 reflect-config.json 사전 구성 필요 |
| SQLite 동시성 | WAL 모드 + 하트비트 ±30초 지터로 병목 방지. 초당 최대 7대/초 동시 쓰기 목표 |
| 에이전트 OTA | GitHub API 접근 가능 환경 전제. 폐쇄망 시 내부 업데이트 엔드포인트 대안 필요 |
| iText 7 라이센스 | AGPL 라이센스. 상업적 배포 시 Apache PDFBox 대체 검토 |
