# IA 정보구조

> 시스템명: 실습실 PC 통합 관제 및 원격 관리 시스템  
> 기준 코드: `frontend` 화면, `backend` API, `agent` 실행 흐름  
> 작성 기준일: 2026-06-08

---

## 1. IA 개요

본 문서는 사용자가 시스템에서 접근하는 화면 구조, 주요 메뉴, 화면별 기능, 백엔드 API 영역, 에이전트 정보 흐름을 정리한 정보구조 문서이다. 프론트엔드는 JavaFX 데스크톱 앱이며, 로그인 후 좌측 사이드바 메뉴를 통해 주요 업무 화면을 여는 구조이다.

---

## 2. 시스템 전체 구조

```text
실습실 PC 통합 관제 및 원격 관리 시스템
├─ 프론트엔드 JavaFX 관리자 앱
│  ├─ 로그인
│  ├─ 강의실 현황
│  ├─ PC 관리
│  ├─ 네트워크
│  ├─ 보고서
│  └─ 설정
├─ 백엔드 Spring Boot 서버
│  ├─ 인증/사용자
│  ├─ 대시보드/강의실/PC 상태
│  ├─ 에이전트 등록/하트비트
│  ├─ 명령 큐/명령 결과
│  ├─ 파일 업로드/배포
│  ├─ 알림/보고서/감사 로그
│  └─ 운영 메트릭
├─ PC 에이전트
│  ├─ 자동 시작
│  ├─ 자동 등록
│  ├─ 하트비트/트래픽 전송
│  ├─ 명령 실행
│  ├─ 파일 수집/배포
│  └─ 피어 캐시 서버
└─ 인프라
   ├─ PostgreSQL
   ├─ Redis
   ├─ Docker/Docker Compose
   └─ 파일 저장소
```

---

## 3. 프론트엔드 화면 IA

### 3.1 최상위 화면 구조

```text
로그인 화면
└─ 메인 화면
   ├─ 상단 헤더
   │  ├─ 시스템명
   │  ├─ 전역 검색
   │  ├─ 알림 버튼/배지
   │  ├─ 설정 버튼
   │  └─ 사용자 메뉴/로그아웃
   ├─ 좌측 사이드바
   │  ├─ 메뉴 검색
   │  ├─ 강의실 현황
   │  ├─ PC 관리
   │  ├─ 네트워크
   │  ├─ 보고서
   │  └─ 설정
   └─ 탭 영역
      ├─ 열린 업무 화면
      └─ 토스트 알림
```

### 3.2 메뉴 구조

| 1 Depth | 2 Depth | 화면 목적 | 대표 FXML/Controller |
| --- | --- | --- | --- |
| 로그인 | - | 서버 접속 및 사용자 인증 | `login.fxml`, `LoginController` |
| 강의실 현황 | - | 모든 강의실과 PC 상태를 한 화면에서 확인 | `classroom-status-tab.fxml`, `ClassroomStatusController` |
| PC 관리 | PC 관제 | PC 목록 조회, 선택, 상태 확인 | `pc-control-tab.fxml`, `PcControlTabController` |
| PC 관리 | 명령 발행 / 이력 | 종료, 재부팅, WOL, CMD, PowerShell 명령 발행 및 이력 확인 | `pc-control-tab.fxml`, `command-table.fxml` |
| PC 관리 | 토큰 관리 | 에이전트 설치 키, 등록 상태, 에이전트 목록 관리 | `token-mgmt-tab.fxml`, `TokenMgmtTabController` |
| PC 관리 | 고장 PC | 고장 PC 등록, 메모, 해제 | `mgmt-broken-pc-section.fxml` |
| 네트워크 | - | 트래픽 차트, PC별 트래픽, 임계치 상태 확인 | `network-tab.fxml`, `NetworkTabController` |
| 보고서 | - | 월간 보고서, PDF 보고서, 트래픽/가동률 확인 | `report-view.fxml` |
| 설정 | 강의실 IP 대역 | 강의실별 IP 대역 등록/삭제/일괄 등록 | `settings-tab.fxml`, `mgmt-ip-range-section.fxml` |
| 설정 | 교사 PC 관리 | 교사용 장비 IP/MAC 등록 및 삭제 | `mgmt-teacher-section.fxml` |
| 설정 | 시스템 | 서버/운영/사용자 관련 설정 | `system-settings-tab.fxml`, `SystemSettingsController` |

### 3.3 강의실 현황 화면

```text
강의실 현황
├─ 상단 제어
│  ├─ 새로고침
│  ├─ 전체화면/축소 보기
│  └─ 그리드 설정
├─ 강의실 카드 목록
│  ├─ 강의실명
│  ├─ 온라인/전체 PC 수
│  ├─ 온라인 비율 게이지
│  ├─ 교사 PC 영역
│  └─ 학생 PC 그리드
└─ 강의실 상세
   ├─ PC 상태
   ├─ IP/MAC/호스트명
   └─ 역할/고장 여부
```

### 3.4 PC 관리 화면

```text
PC 관리
├─ PC 목록 영역
│  ├─ 검색/필터
│  ├─ 강의실 필터
│  ├─ 역할 필터
│  └─ PC 테이블
├─ 명령 영역
│  ├─ 종료
│  ├─ 재부팅
│  ├─ Wake-on-LAN
│  ├─ CMD 실행
│  ├─ PowerShell 실행
│  ├─ 파일 목록 조회
│  ├─ 파일 수집
│  └─ 파일 배포
├─ 파일 배포 영역
│  ├─ 파일 선택
│  ├─ 업로드 진행률
│  ├─ 대상 선택
│  ├─ 웨이브 옵션
│  └─ 배포 상태
└─ 명령 이력
   ├─ 명령 상태
   ├─ 표준 출력
   ├─ 표준 오류
   └─ 종료 코드
```

### 3.5 네트워크 화면

```text
네트워크
├─ 전체 트래픽 요약
├─ 인바운드/아웃바운드 게이지
├─ 시간대별 트래픽 차트
├─ PC별 트래픽 테이블
└─ 임계치 초과 표시
```

### 3.6 보고서 화면

```text
보고서
├─ 월간 보고서 목록
├─ 보고서 상세 조회
├─ 월간 보고서 재생성
├─ PDF 보고서 생성
├─ 트래픽 보고서
└─ 가동률 보고서
```

### 3.7 설정 화면

```text
설정
├─ 강의실 IP 대역
│  ├─ 강의실 선택
│  ├─ IP 대역 추가
│  ├─ IP 대역 일괄 등록
│  └─ IP 대역 삭제
├─ 교사 PC 관리
│  ├─ 강의실 선택
│  ├─ 교사 PC IP/MAC 등록
│  └─ 교사 PC 삭제
├─ 시스템
│  ├─ 사용자/비밀번호 관리
│  ├─ 환경 설정 확인
│  └─ 운영 상태 확인
└─ 토큰/에이전트 관리
   ├─ 설치 키 조회
   ├─ 등록 허용 상태 변경
   ├─ 에이전트 설정 다운로드
   ├─ 등록 에이전트 목록
   └─ 에이전트 삭제/토큰 폐기
```

---

## 4. 백엔드 API IA

```text
/api/v1
├─ /auth
│  └─ 로그인
├─ /users
│  └─ 사용자 CRUD
├─ /settings
│  └─ 사용자 설정 조회/저장
├─ /admin
│  ├─ 관리자 비밀번호 변경
│  ├─ 명령 큐 등록
│  ├─ 그룹 WOL
│  ├─ 설치 키 조회
│  ├─ 에이전트 목록/삭제/토큰 폐기
│  └─ 감사 로그
├─ /agent
│  ├─ 에이전트 등록
│  ├─ 등록 상태 조회/변경
│  └─ 설정 다운로드
├─ /heartbeat
│  ├─ 하트비트 수신
│  └─ 오프라인 알림
├─ /commands
│  ├─ 명령 생성
│  ├─ IP 대상 명령
│  └─ 명령 목록
├─ /agent/commands
│  ├─ 다음 명령 조회
│  ├─ 명령 ACK
│  └─ 명령 결과 보고
├─ /dashboard
│  ├─ 강의실 요약
│  ├─ PC 목록/상태
│  ├─ 트래픽
│  ├─ IP 대역
│  ├─ 교사 PC
│  ├─ 고장 PC
│  └─ 통합 새로고침
├─ /dashboards
│  ├─ 대시보드 CRUD
│  ├─ 패널 CRUD
│  ├─ 패널 위치 저장
│  └─ 레이아웃 백업/복원
├─ /dashboard/widgets
│  └─ 위젯 CRUD
├─ /dashboard/texts
│  └─ 텍스트 위젯 CRUD/복원/이름 변경
├─ /dashboard/images
│  └─ 이미지 위젯 CRUD/업로드
├─ /groups
│  ├─ 강의실 그룹 CRUD
│  ├─ PC 수동 할당
│  └─ PC 위치 저장/조회
├─ /ip-ranges
│  ├─ IP 대역 조회
│  ├─ IP 대역 등록
│  ├─ IP 대역 일괄 등록
│  └─ IP 대역 삭제
├─ /file-distribution
│  ├─ 배포 Job 생성
│  ├─ Job/Wave 취소
│  ├─ Wave 시작
│  ├─ 실패 재시도
│  ├─ 롤백
│  ├─ 대상별 결과 보고
│  └─ Job/Event 조회
├─ /scripts
│  └─ 스크립트 업로드/다운로드
├─ /file-chunks
│  └─ 프론트 대용량 파일 청크 업로드/조립
├─ /agent/file-chunks
│  └─ 에이전트 파일 수집 청크 업로드/조립
├─ /agent/uploads
│  └─ 에이전트 수집 파일 다운로드
├─ /alerts
│  ├─ 알림 목록
│  ├─ 미확인 개수
│  ├─ 개별 확인
│  └─ 전체 확인
├─ /reports
│  ├─ 월간 보고서
│  ├─ PDF 보고서
│  ├─ 트래픽 보고서
│  ├─ 가동률 보고서
│  └─ 로그 조회
└─ /status
   ├─ 전체 PC 상태
   ├─ MAC별 상태
   ├─ 역할별 상태
   ├─ 강의실별 상태
   └─ 운영 성능 상태
```

---

## 5. 에이전트 IA

```text
PCAgent
├─ 설치/시작
│  ├─ MSI 설치
│  ├─ ProgramData 초기화
│  ├─ 설정 파일 로드
│  ├─ 자동 시작 작업 등록
│  └─ 단일 인스턴스 잠금
├─ 등록
│  ├─ 에이전트 토큰 확인
│  ├─ 설치 키 기반 등록
│  └─ 토큰 저장
├─ 상태 수집
│  ├─ 하드웨어 정보 수집
│  ├─ 네트워크 인터페이스 수집
│  ├─ 트래픽 샘플링
│  └─ 하트비트 전송
├─ 명령 처리
│  ├─ 명령 Long Polling
│  ├─ ACK 전송
│  ├─ 종료/재부팅 실행
│  ├─ CMD 실행
│  ├─ PowerShell 실행
│  ├─ WOL Relay
│  └─ 결과 보고
├─ 파일 처리
│  ├─ 파일 목록 조회
│  ├─ 파일 수집
│  ├─ 청크 업로드
│  ├─ Range 다운로드
│  ├─ ZIP 압축/해제
│  └─ SHA-256 검증
├─ 피어 배포
│  ├─ 로컬 캐시 서버 시작
│  ├─ 받은 파일 제공
│  └─ 같은 대역 에이전트 다운로드 지원
└─ 운영 보조
   ├─ powercfg 절전 보정
   ├─ netsh 방화벽 보정
   ├─ 민감정보 로그 마스킹
   └─ agent.log 기록
```

---

## 6. 주요 데이터 흐름

### 6.1 로그인 및 화면 진입

```text
사용자
→ 로그인 화면
→ /api/v1/auth/login
→ JWT 발급
→ 메인 화면 진입
→ 강의실 현황 자동 오픈
→ /api/v1/dashboard/refresh 또는 강의실 상태 API 조회
```

### 6.2 에이전트 등록 및 상태 반영

```text
에이전트 설치
→ 자동 시작 작업 등록
→ 설정 파일 로드
→ 토큰 없음
→ /api/v1/agent/register
→ 토큰 저장
→ /api/v1/heartbeat
→ PC Master 갱신
→ IP 대역/교사 PC 기준 강의실과 역할 판정
→ 프론트 강의실 현황 갱신
```

### 6.3 명령 실행

```text
관리자 PC 선택
→ 명령 발행
→ /api/v1/commands
→ 명령 큐 저장
→ 에이전트 /api/v1/agent/commands/next
→ ACK 보고
→ 로컬 OS 명령 실행
→ 결과 보고
→ 명령 이력 화면 갱신
```

### 6.4 파일 배포

```text
관리자 파일 선택
→ 프론트 청크 업로드
→ 서버 파일 조립
→ 배포 Job 생성
→ Wave 단위 대상 선정
→ 에이전트 다운로드
→ 피어 캐시 후보 등록
→ 대상별 결과 보고
→ 배포 상태 화면 갱신
```

### 6.5 보고서 생성

```text
관리자 기간 선택
→ 보고서 API 호출
→ PC 상태/트래픽/가동률 집계
→ PDF 또는 JSON 결과 생성
→ 프론트에서 확인/다운로드
```

---

## 7. 화면별 API 매핑

| 화면 | 주요 API 영역 |
| --- | --- |
| 로그인 | `/api/v1/auth/login` |
| 강의실 현황 | `/api/v1/dashboard/classrooms`, `/api/v1/dashboard/classrooms/{id}/pcs`, `/api/v1/dashboard/refresh` |
| PC 관리 | `/api/v1/status`, `/api/v1/commands`, `/api/v1/commands/to-ip`, `/api/v1/file-distribution` |
| 토큰 관리 | `/api/v1/agent/registration-status`, `/api/v1/agent/config/download`, `/api/v1/admin/install-key`, `/api/v1/admin/agents` |
| 네트워크 | `/api/v1/dashboard/traffic`, `/api/v1/dashboard/traffic/summary`, `/api/v1/reports/traffic` |
| 보고서 | `/api/v1/reports/monthly`, `/api/v1/reports/pdf`, `/api/v1/reports/uptime` |
| 강의실 IP 대역 설정 | `/api/v1/ip-ranges`, `/api/v1/dashboard/classrooms/{groupId}/ip-ranges` |
| 교사 PC 설정 | `/api/v1/dashboard/classrooms/{groupId}/teacher-devices`, `/api/v1/{groupId}/teacher-devices` |
| 고장 PC 관리 | `/api/v1/dashboard/pcs/broken` |
| 알림 | `/api/v1/alerts`, `/api/v1/alerts/unread-count`, `/api/v1/alerts/ack-all` |
| 사용자/시스템 설정 | `/api/v1/users`, `/api/v1/settings`, `/api/v1/admin/change-password`, `/api/v1/status/performance` |

---

## 8. 정보구조 설계 의도

| 설계 항목 | 의도 |
| --- | --- |
| 강의실 현황 첫 화면 | 수업 시작 전후 가장 자주 확인하는 PC ON/OFF 상태를 즉시 볼 수 있게 한다. |
| PC 관리 분리 | 목록 조회, 명령 발행, 파일 배포처럼 작업 위험도가 높은 기능을 별도 업무 화면에 둔다. |
| 설정 분리 | IP 대역, 교사 PC, 시스템 설정처럼 자주 바뀌지 않는 관리 기능을 설정 영역에 둔다. |
| 알림 상단 고정 | 오프라인, 배포 실패, 트래픽 이상 등 즉시 확인해야 하는 상태를 어느 화면에서나 볼 수 있게 한다. |
| 에이전트 백그라운드 구조 | 관리 대상 PC 사용자가 직접 조작하지 않아도 서버와 계속 연결되도록 한다. |
| 웨이브/피어 배포 구조 | 다수 PC에 큰 파일을 배포할 때 서버와 네트워크 부하가 한 번에 몰리지 않게 한다. |

