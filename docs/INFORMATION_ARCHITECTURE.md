# 정보구조(IA) 문서 — Information Architecture

> **문서 버전**: 1.0  
> **최종 갱신일**: 2026-03-30

---

## 1. 시스템 전체 구조

```
통합 강의실 PC 관제·원격제어 시스템
├── 🖥️ 프론트엔드 (JavaFX 21)
│   ├── 로그인 화면
│   └── 대시보드 (5탭 구조)
│       ├── 탭1: 메인 대시보드
│       ├── 탭2: 네트워크 트래픽
│       ├── 탭3: 명령 상태
│       ├── 탭4: 관리
│       └── 탭5: PC 관리
├── ⚙️ 백엔드 서버 (Spring Boot 3.x)
│   ├── REST API (/api/v1/*)
│   ├── WebSocket (/ws)
│   └── SQLite DB
├── 🤖 PC 에이전트 (Java 25)
│   ├── 하트비트 전송
│   ├── 명령 실행
│   └── WOL 릴레이
└── 🌐 인프라
    ├── Docker + Docker Compose
    ├── Nginx 리버스 프록시
    └── ZeroSSL 인증서
```

---

## 2. 프론트엔드 화면 구조 (Screen Map)

```
[로그인 화면]
 │
 └──(JWT 인증 성공)──▶ [대시보드 메인]
                         │
                         ├── [상단바 — 공통]
                         │    ├── 시스템 타이틀
                         │    ├── 대시보드 선택기 (ComboBox)
                         │    ├── "+ 대시보드" 버튼
                         │    ├── 로그아웃 버튼
                         │    ├── 상태 요약 라벨
                         │    ├── WebSocket 연결 상태
                         │    └── 실시간 시계
                         │
                         ├── [탭 1: 메인 대시보드]
                         │    ├── 좌측: 리소스 패널
                         │    │    ├── 강의실 패널 목록
                         │    │    ├── 텍스트 위젯
                         │    │    ├── 이미지 위젯
                         │    │    ├── 네트워크 패널
                         │    │    └── 접속끊김 패널
                         │    │
                         │    ├── 우측: 자유 배치 캔버스
                         │    │    ├── 강의실 패널 (N개)
                         │    │    │    ├── 강의실명
                         │    │    │    ├── 온라인/전체 PC 수
                         │    │    │    ├── 교사PC 상태
                         │    │    │    ├── IP 대역
                         │    │    │    ├── [전체 종료] 버튼
                         │    │    │    └── [전체 켜기(WOL)] 버튼
                         │    │    ├── 텍스트 위젯 (N개)
                         │    │    ├── 이미지 위젯 (N개)
                         │    │    ├── 네트워크 패널
                         │    │    └── 접속끊김 패널
                         │    │
                         │    └── 하단: 모드·줌 컨트롤
                         │         ├── 편집 모드 토글
                         │         ├── 포커스 모드 토글
                         │         ├── 대시보드만 모드 토글
                         │         ├── 전체 화면 토글
                         │         ├── 줌 (+/−/100%/맞춤)
                         │         ├── 배경색 변경
                         │         ├── 정렬 (좌/우/상/하)
                         │         └── 배열 (격자/행/열)
                         │
                         ├── [탭 2: 네트워크 트래픽]
                         │    ├── 인바운드/아웃바운드 게이지
                         │    ├── 실시간 LineChart
                         │    ├── PC별 트래픽 테이블
                         │    └── 임계치 경고 표시
                         │
                         ├── [탭 3: 명령 상태]
                         │    ├── 요약 라벨 (Q/D/A/완료/실패)
                         │    └── 명령 이력 테이블
                         │
                         ├── [탭 4: 관리]
                         │    ├── 에이전트 설치 관리
                         │    │    ├── 설치키 조회/복사
                         │    │    ├── config.json 다운로드
                         │    │    └── 등록 에이전트 목록
                         │    ├── IP 대역 관리
                         │    │    ├── 대역 추가 (CIDR/범위)
                         │    │    ├── 일괄 등록
                         │    │    └── 대역 목록/삭제
                         │    ├── 교사 장치 관리
                         │    │    ├── 교사 PC 등록 (IP/MAC)
                         │    │    └── 교사 PC 목록/삭제
                         │    ├── 고장 PC 관리
                         │    │    ├── 고장 등록/해제
                         │    │    └── 고장 메모
                         │    └── 보고서 생성
                         │         ├── 기간 선택
                         │         └── PDF 다운로드
                         │
                         └── [탭 5: PC 관리]
                              ├── 필터 (전체/강의실/교사)
                              ├── PC 테이블 (멀티셀렉션)
                              ├── 명령 대상 선택
                              ├── 명령 종류 선택
                              └── 전송 확인 다이얼로그
```

---

## 3. 백엔드 API 구조 (Sitemap)

```
/api/v1
├── /auth
│   └── POST /login                      # 로그인 (JWT 발급)
│
├── /groups                               # 강의실 관리
│   ├── GET    /                          # 목록 조회
│   ├── POST   /                          # 생성
│   ├── PUT    /{id}                      # 수정
│   ├── DELETE /{id}                      # 삭제
│   ├── POST   /{id}/assign              # IP 기반 PC 할당
│   ├── POST   /assign/manual            # MAC 기반 PC 할당
│   ├── GET    /{id}/positions            # PC 위치 조회
│   └── POST   /{id}/positions            # PC 위치 저장
│
├── /ip-ranges                            # IP 대역 관리
│   ├── GET    /                          # 조회
│   ├── POST   /                          # 등록
│   ├── POST   /batch                     # 일괄 등록
│   └── DELETE /{id}                      # 삭제
│
├── /heartbeat                            # 에이전트 하트비트
│   └── POST   /                          # 하트비트 수신
│
├── /agent                                # 에이전트 관리
│   ├── POST   /register                  # 에이전트 등록
│   └── /commands
│       ├── POST /{taskId}/ack            # 명령 ACK
│       └── POST /{taskId}/result         # 명령 결과
│
├── /commands                             # 명령 관리
│   ├── POST   /                          # 명령 발행
│   ├── POST   /to-ip                     # IP 지정 명령
│   └── GET    /                          # 명령 목록
│
├── /admin                                # 관리자 API
│   ├── POST   /enqueue                   # 단일 명령 큐잉
│   ├── POST   /group/wol                 # 그룹 WOL
│   ├── GET    /install-key               # 설치키 조회
│   ├── GET    /agents                    # 에이전트 목록
│   ├── PUT    /agents/{mac}/revoke       # 토큰 폐기
│   ├── DELETE /agents/{mac}              # 에이전트 삭제
│   └── GET    /audit/logs                # 감사 로그
│
├── /dashboard                            # 대시보드 데이터
│   ├── GET    /classrooms                # 강의실 요약
│   ├── GET    /classrooms/{id}/pcs       # PC 상태
│   ├── GET    /classrooms/{id}/traffic/pcs  # PC별 트래픽
│   ├── GET    /traffic                   # 전체 트래픽
│   ├── GET    /traffic/summary           # 트래픽 요약
│   ├── GET    /config/traffic-thresholds # 임계값 설정
│   ├── GET    /refresh                   # 통합 새로고침
│   │
│   ├── /classrooms/{gid}/ip-ranges       # 강의실별 IP 대역
│   │   ├── GET  /
│   │   └── POST /
│   ├── DELETE /classrooms/ip-ranges/{id}
│   │
│   ├── /classrooms/{gid}/teacher-devices # 강의실별 교사 장치
│   │   ├── GET  /
│   │   └── POST /
│   ├── DELETE /classrooms/teacher-devices/{id}
│   │
│   ├── /pcs/broken                       # 고장 PC
│   │   ├── POST   /                      # 고장 등록
│   │   ├── GET    /                      # 고장 목록
│   │   ├── DELETE /{mac}                 # 고장 해제
│   │   └── PUT    /{mac}                 # 메모 수정
│   │
│   ├── /texts                            # 텍스트 위젯
│   │   ├── GET/POST/PUT/DELETE
│   │   └── POST /restore                # 복원
│   ├── /images                           # 이미지 위젯
│   │   ├── GET/POST/PUT/DELETE
│   │   └── POST /upload                 # 파일 업로드
│   ├── /widgets                          # 범용 위젯
│   │   └── GET/POST/PUT/DELETE
│   └── /modules                          # 레거시 모듈
│       └── GET/POST/PUT/DELETE
│
├── /dashboards                           # 다중 대시보드
│   ├── GET/POST/PUT/DELETE
│   └── /{id}/panels                      # 패널 관리
│       └── GET/POST/PUT/DELETE
│
├── /scripts                              # 스크립트 관리
│   ├── POST   /                          # 업로드
│   └── GET    /{id}/download             # 다운로드
│
├── /reports                              # 보고서
│   ├── GET    /pdf                       # PDF 보고서
│   ├── GET    /traffic                   # 트래픽 리포트
│   ├── GET    /uptime                    # 가동률 리포트
│   └── GET    /logs                      # 트래픽 로그
│
├── /status                               # PC 상태 조회
│   ├── GET    /all                       # 전체
│   ├── GET    /{mac}                     # 개별
│   ├── GET    /role/{role}               # 역할별
│   ├── GET    /classroom/{groupId}       # 강의실별
│   └── GET    /performance               # 성능 데이터
│
├── /settings                             # 설정
│   ├── GET    /{key}                     # 조회
│   └── PUT    /{key}                     # 변경
│
├── /files/{filename}                     # 파일 서빙
│
└── /ws                                   # WebSocket (STOMP)
    ├── /topic/pc-status                  # PC 상태 Push
    └── /topic/classrooms                 # 강의실 Push
```

---

## 4. 데이터 계층 구조

```
시스템
├── 사용자 (users)
│   └── 설정 (user_settings)
│
├── 강의실 (classroom_groups)
│   ├── IP 대역 (classroom_ip_ranges)        [1:N]
│   ├── 교사 장치 (classroom_teacher_devices)  [1:N]
│   └── PC (pc_master)                        [1:N]
│       ├── 트래픽 로그 (traffic_logs)         [1:N]
│       ├── 명령 큐 (command_queue)            [1:N]
│       ├── 에이전트 토큰 (agent_auth_tokens)  [1:1]
│       └── 위치 (client_positions)            [1:N]
│
├── 대시보드 (dashboards)
│   ├── 패널 (dashboard_panels)               [1:N]
│   └── 위젯 (dashboard_widget_items)         [1:N]
│
├── 스크립트 (scripts)
│
└── 감사 로그 (audit_logs)
```

---

## 5. 에이전트 상태 흐름도

```
[미등록] ──(설치키 인증)──▶ [등록/토큰 발급] ──▶ [하트비트 루프]
                                                     │
                                     ┌────────────────┼────────────────┐
                                     ▼                ▼                ▼
                              [상태 보고]      [명령 수신]      [트래픽 수집]
                              (ONLINE 유지)   (하트비트 응답)   (OSHI NIC 델타)
                                                     │
                                              ┌──────┴──────┐
                                              ▼             ▼
                                         [ACK 전송]    [실행 → 결과 보고]
                                                     (SHUTDOWN/REBOOT/
                                                      RUN_CMD/RUN_PS1/
                                                      WOL_RELAY)
```

---

## 6. 명령 라이프사이클

```
[관리자 발행]
     │
     ▼
  QUEUED ──(하트비트 응답)──▶ DELIVERED ──(에이전트 ACK)──▶ ACKED
     │                           │                           │
     │                           │                           ▼
     │                    (180초 타임아웃)              [에이전트 실행]
     │                           │                           │
     │                           ▼                     ┌─────┴─────┐
     │                     [재큐잉 ≤3회]               ▼           ▼
     │                                           COMPLETED     FAILED
     └──(오프라인 PC)──▶ [대기 유지] ──(PC 복귀)──▶ DELIVERED
```

---

## 7. 네비게이션 흐름도

```
로그인 ──▶ 대시보드
            │
            ├── 탭1 메인 ──▶ 강의실 패널 클릭 ──▶ 전체 종료 / 전체 WOL
            │                                 ──▶ 편집 모드 진입 ──▶ 드래그/리사이즈
            │
            ├── 탭2 네트워크 ──▶ PC별 트래픽 확인
            │
            ├── 탭3 명령 상태 ──▶ 진행중/완료/실패 확인
            │
            ├── 탭4 관리 ──┬── 에이전트 설치 관리
            │              ├── IP 대역 관리
            │              ├── 교사 장치 관리
            │              ├── 고장 PC 관리
            │              └── 보고서 생성 ──▶ PDF 다운로드
            │
            └── 탭5 PC관리 ──▶ PC 선택 ──▶ 명령 전송 ──▶ 확인 다이얼로그
```
