# 운영 메트릭/알람 런북

## 목적
- 외부 Grafana 없이 현재 웹 대시보드와 백엔드만으로 운영 상태를 진단하고 대응한다.
- 장애 징후를 빠르게 분류하고, 1차 대응 후 원인 분석까지 표준 절차로 처리한다.

## 1. 확인 위치
- 프론트: `설정 > 운영 메트릭 패널`
- 프론트: 우측 상단 알림 아이콘(미확인 배지, 목록, 전체 확인)
- 백엔드 API:
  - `GET /api/v1/status/performance`
  - `GET /api/v1/alerts?limit=50&unreadOnly=true`
  - `GET /actuator/prometheus` (ADMIN 권한)

## 2. 핵심 지표
- 명령 지연: `dispatch/ack/result` 평균 및 `P95`
- 명령 실패율: `operational.latency.failureRate`
- 전송 지표: 업/다운 처리량(Mbps), 전송 실패율, 실패 코드 분포
- 요청 지표: 역할별 요청량, `403` 비율, API 에러율
- 온라인 비율: 전체 대비 온라인 에이전트 비율

## 3. 알람 임계치(기본)
- 명령 실패율: `>= 25%` (CRITICAL)
- 전송 실패율: `>= 25%` (CRITICAL)
- ACK P95: `>= 20000ms` (WARNING)
- 결과 P95: `>= 45000ms` (WARNING)
- API 에러율: `>= 15%` (WARNING)
- 403 비율: `>= 40%` (INFO)
- 강의실 상태: 온라인 PC가 `0대 ↔ 1대 이상`으로 바뀔 때 켜짐/꺼짐 알림 (INFO)

환경변수로 조정:
- `METRICS_ALERT_COMMAND_FAILURE_RATE`
- `METRICS_ALERT_TRANSFER_FAILURE_RATE`
- `METRICS_ALERT_REQUEST_ERROR_RATE`
- `METRICS_ALERT_FORBIDDEN_RATE`
- `METRICS_ALERT_ACK_P95_MS`
- `METRICS_ALERT_RESULT_P95_MS`

## 4. 대응 절차
1. 알림 목록에서 신규 CRITICAL/WARNING과 강의실 전원 상태 INFO 확인
2. `status/performance`에서 동일 시점 지표 교차 확인
3. 명령 실패율 급증 시:
   - 특정 명령 타입/그룹 편중 여부 확인
   - stale command(전달/ACK 지연) 동반 여부 확인
4. 전송 실패율 급증 시:
   - 실패 코드(4xx/5xx/429) 확인
   - 청크 업로드 재개 여부, 저장소 용량/권한 점검
5. 403 급증 시:
   - VIEWER/USER 권한 변경 이력, 토큰 만료 여부 확인
6. 온라인 비율 급락 시:
   - 특정 강의실/대역대 집중 여부 확인
   - 네트워크/전원 이벤트(스위치, DHCP, 재부팅 창) 확인
7. 조치 후 알림 ACK 및 운영 메모 기록

## 5. 재발 방지 체크리스트
- 임계치가 너무 민감한지(오탐) 주 1회 점검
- 피크 시간대(수업 시작/종료) 기준 P95 추세 비교
- 대규모 배포 전후(웨이브 시작/완료) 실패율 기준선 갱신
- 월 1회 `status/performance` 스냅샷 샘플 보관
