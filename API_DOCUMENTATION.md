# API 문서

## 개요
이 문서는 강의실 통합 대시보드 백엔드 REST API의 현재 구현 기준 엔드포인트를 정리합니다.

## 서버/문서 접근 경로

**API Base (Local):** `http://localhost:8888/api/v1`

**Swagger UI (권장):** [http://localhost:8888/swagger-ui/index.html](http://localhost:8888/swagger-ui/index.html)

**Swagger UI (호환 리다이렉트):** [http://localhost:8888/swagger-ui.html](http://localhost:8888/swagger-ui.html)

**OpenAPI JSON:** [http://localhost:8888/v3/api-docs](http://localhost:8888/v3/api-docs)

**OpenAPI YAML:** [http://localhost:8888/v3/api-docs.yaml](http://localhost:8888/v3/api-docs.yaml)

> 프로덕션에서는 Nginx 리버스 프록시(443 -> 8888) 뒤에서 HTTPS로 접근합니다.

예시:
- `https://<your-domain>/swagger-ui/index.html`
- `https://<your-domain>/v3/api-docs`

문서 점검 예시:
```bash
curl -s http://localhost:8888/v3/api-docs | jq '.info.title,.info.version'
curl -I http://localhost:8888/swagger-ui/index.html
```

---

## 인증/인가 정책

### 1) JWT 로그인
```http
POST /api/v1/auth/login
Content-Type: application/json

{"username":"admin","password":"비밀번호"}
```

응답:
```json
{"token":"<jwt-token>"}
```

헤더 사용:
```http
Authorization: Bearer <jwt-token>
```

### 2) Agent 인증
- 등록: `X-Agent-Install-Key`
- 하트비트/에이전트 명령 처리: `X-Agent-Token`

### 3) 권한 매트릭스 (요약)

| 구분 | 규칙 |
|---|---|
| 공개 경로 | `/api/v1/auth/login`, `/api/v1/agent/register`, `/api/v1/heartbeat/**`, `/v3/api-docs/**`, `/swagger-ui/**` |
| Agent 명령 콜백 | `/api/v1/agent/commands/**`는 시큐리티 체인 permitAll, Agent 토큰 인터셉터에서 검증 |
| GET `/api/v1/**` | `ADMIN`, `USER`, `VIEWER` |
| 변경 `/api/v1/**` | `ADMIN`, `USER` |
| 컨트롤러 단위 ADMIN 강제 | `GroupController`, `AdminController`, `CommandController` 일부, `ScriptController` 일부, `FileDistributionController` 등 |

---

## 공통 응답 형식

### 페이지네이션 응답
일부 조회 API는 배열이 아니라 `Page` 객체를 반환합니다.

```json
{
  "content": [
    {"id": 1, "name": "강의실 101"}
  ],
  "pageable": {"pageNumber": 0, "pageSize": 20},
  "totalPages": 3,
  "totalElements": 45,
  "last": false,
  "size": 20,
  "number": 0,
  "first": true,
  "numberOfElements": 20,
  "empty": false
}
```

### 에러 응답
엔드포인트별 구현에 따라 에러 바디 키는 `error` 또는 `message`를 사용합니다.

```json
{"error":"Token MAC mismatch"}
```

```json
{"message":"groupId required"}
```

---

## 핵심 API (상세)

### 1. 인증 API

#### 1.1 로그인
```http
POST /api/v1/auth/login
```

요청:
```json
{"username":"admin","password":"비밀번호"}
```

응답:
```json
{"token":"<jwt-token>"}
```

상태 코드:
- `200 OK`
- `401 Unauthorized`

---

### 2. 강의실(그룹) API
Base: `/api/v1/groups` (ADMIN)

#### 2.1 강의실 목록 조회 (페이지)
```http
GET /api/v1/groups?page=0&size=20
```

응답 예시:
```json
{
  "content": [
    {
      "id": 1,
      "name": "강의실 101",
      "description": "1층 강의실",
      "createdAt": "2026-03-13T10:00:00"
    }
  ],
  "totalPages": 1,
  "totalElements": 1,
  "number": 0,
  "size": 20
}
```

#### 2.2 강의실 생성
```http
POST /api/v1/groups
```

요청:
```json
{"name":"강의실 103","description":"2층 강의실"}
```

응답: 생성된 `ClassroomGroup` 객체 (`200`)

#### 2.3 강의실 수정
```http
PUT /api/v1/groups/{id}
```

#### 2.4 강의실 삭제
```http
DELETE /api/v1/groups/{id}
```

응답 코드:
- `204 No Content`
- `404 Not Found`

#### 2.5 IP 기준 수동 할당
```http
POST /api/v1/groups/{id}/assign?ip=192.168.10.10
```

#### 2.6 MAC 기준 수동 할당
```http
POST /api/v1/groups/assign/manual?mac=00:1A:2B:3C:4D:5E&groupId=1
```

#### 2.7 좌표 저장/조회
```http
POST /api/v1/groups/{id}/positions
GET  /api/v1/groups/{id}/positions
```

---

### 3. 대시보드 API
Base: `/api/v1/dashboard`

#### 3.1 강의실 요약 목록 (페이지)
```http
GET /api/v1/dashboard/classrooms?page=0&size=20
```

응답 예시:
```json
{
  "content": [
    {
      "groupId": 1,
      "groupName": "강의실 101",
      "onlinePcs": 15,
      "totalPcs": 20,
      "visible": true,
      "x": 100.5,
      "y": 200.5,
      "width": 400.0,
      "height": 300.0,
      "ipRanges": ["192.168.10.0/24"],
      "teacherPcStatus": "ONLINE"
    }
  ],
  "totalPages": 1,
  "totalElements": 1,
  "number": 0,
  "size": 20
}
```

#### 3.2 강의실 PC 목록 (페이지)
```http
GET /api/v1/dashboard/classrooms/{classroomId}/pcs?page=0&size=20
```

#### 3.3 강의실별 PC 트래픽
```http
GET /api/v1/dashboard/classrooms/{classroomId}/traffic/pcs
```

#### 3.4 전체 트래픽
```http
GET /api/v1/dashboard/traffic
GET /api/v1/dashboard/traffic/summary
```

응답:
```json
{"inboundMbps":45.5,"outboundMbps":32.2}
```

#### 3.5 트래픽 임계치
```http
GET /api/v1/dashboard/config/traffic-thresholds
```

응답:
```json
{"globalMbps":2000.0,"classroomMbps":200.0,"pcMbps":50.0}
```

#### 3.6 대시보드 통합 스냅샷
```http
GET /api/v1/dashboard/refresh
```

#### 3.7 IP 대역(강의실별)
```http
GET    /api/v1/dashboard/classrooms/{groupId}/ip-ranges
POST   /api/v1/dashboard/classrooms/{groupId}/ip-ranges
DELETE /api/v1/dashboard/classrooms/ip-ranges/{id}
```

#### 3.8 교사 PC
```http
GET    /api/v1/dashboard/classrooms/{groupId}/teacher-devices
POST   /api/v1/dashboard/classrooms/{groupId}/teacher-devices
DELETE /api/v1/dashboard/classrooms/teacher-devices/{id}
```

#### 3.9 고장 PC
```http
POST   /api/v1/dashboard/pcs/broken
GET    /api/v1/dashboard/pcs/broken
DELETE /api/v1/dashboard/pcs/broken/{macAddress}
PUT    /api/v1/dashboard/pcs/broken/{macAddress}
```

---

### 4. 하트비트 API
Base: `/api/v1/heartbeat`

#### 4.1 하트비트 수신
```http
POST /api/v1/heartbeat
X-Agent-Token: <agent-token>
```

요청 예시:
```json
{
  "macAddress":"00:1A:2B:3C:4D:5E",
  "ipAddress":"192.168.10.10",
  "hostname":"PC-101-001",
  "inboundKbps":5200.0,
  "outboundKbps":3100.0,
  "positions":[]
}
```

응답 예시 (대기 명령 없음):
```json
{}
```

응답 예시 (대기 명령 있음):
```json
{
  "command": {
    "taskId": 123,
    "type": "SHUTDOWN",
    "payloadJson": "{}"
  }
}
```

#### 4.2 오프라인 선신고
```http
POST /api/v1/heartbeat/offline
X-Agent-Token: <agent-token>
```

요청 예시:
```json
{
  "macAddress":"00:1A:2B:3C:4D:5E",
  "ipAddress":"192.168.10.10",
  "reason":"SHUTDOWN"
}
```

응답:
```json
{"accepted":true}
```

---

### 5. 에이전트 API
Base: `/api/v1/agent`

#### 5.1 등록
```http
POST /api/v1/agent/register
X-Agent-Install-Key: <install-key>
```

응답 코드:
- `201 Created`

#### 5.2 에이전트 설정 파일 다운로드 (관리자)
```http
GET /api/v1/agent/config/download
```

#### 5.3 명령 ACK/결과 보고
```http
POST /api/v1/agent/commands/{taskId}/ack
POST /api/v1/agent/commands/{taskId}/result
```

ACK 요청 예시:
```json
{}
```

결과 보고 요청 예시:
```json
{"exitCode":0,"stdout":"ok","stderr":""}
```

---

### 6. 명령 API
Base: `/api/v1`

#### 6.1 명령 발행
```http
POST /api/v1/commands
```

요청 예시:
```json
{
  "scope":"GROUP",
  "groupId":1,
  "role":"ALL",
  "type":"SHUTDOWN",
  "payloadJson":"{}"
}
```

응답 코드:
- `202 Accepted` (큐 등록)
- `400 Bad Request`

응답 예시:
```json
{"queued":20,"taskIds":[101,102,103]}
```

#### 6.2 IP 기반 명령 발행
```http
POST /api/v1/commands/to-ip
```

요청 예시:
```json
{"ipAddress":"192.168.10.10","type":"REBOOT"}
```

#### 6.3 명령 목록 조회
```http
GET /api/v1/commands?page=0&size=20&status=QUEUED,DELIVERED,ACKED
```

#### 6.4 대기 명령 취소
```http
POST /api/v1/commands/{taskId}/cancel
```

아직 에이전트에 전달되지 않은 `QUEUED` 명령만 취소합니다. 명령 종류에는 제한이 없으며,
취소된 명령은 `CANCELLED`로 남아 대상 PC가 나중에 온라인이 되어도 실행되지 않습니다.

#### 6.5 ACK/RESULT 상태 충돌
- 명령 상태가 허용 상태가 아니면 `409 Conflict`를 반환할 수 있습니다.

---

## 운영/관리 API (인덱스)

### 7. 관리자 API (`/api/v1/admin`)
```http
POST   /api/v1/admin/enqueue
POST   /api/v1/admin/group/wol
PUT    /api/v1/admin/agents/{mac}/revoke
GET    /api/v1/admin/install-key
GET    /api/v1/admin/agents
DELETE /api/v1/admin/agents/{mac}
GET    /api/v1/admin/audit/logs
```

### 8. 스크립트/파일 API (`/api/v1`)
```http
POST /api/v1/scripts
GET  /api/v1/scripts/{id}/download

POST /api/v1/agent/uploads
GET  /api/v1/agent/uploads/{id}/download

POST /api/v1/file-chunks
POST /api/v1/file-chunks/assemble
POST /api/v1/agent/file-chunks
POST /api/v1/agent/file-chunks/assemble
```

### 9. 파일 배포 오케스트레이션 API (`/api/v1/file-distribution`)
```http
POST /api/v1/file-distribution/jobs
POST /api/v1/file-distribution/jobs/{jobId}/cancel
POST /api/v1/file-distribution/jobs/{jobId}/waves/{wave}/cancel
POST /api/v1/file-distribution/jobs/{jobId}/waves/{wave}/start
POST /api/v1/file-distribution/jobs/{jobId}/retry-failed
POST /api/v1/file-distribution/jobs/{jobId}/rollback
POST /api/v1/file-distribution/jobs/{jobId}/agents/{agentId}/result
GET  /api/v1/file-distribution/jobs/{jobId}
GET  /api/v1/file-distribution/jobs/{jobId}/events
GET  /api/v1/file-distribution/jobs
```

### 10. IP 대역 API (`/api/v1/ip-ranges`)
```http
GET    /api/v1/ip-ranges?page=0&size=50&groupId=
POST   /api/v1/ip-ranges
POST   /api/v1/ip-ranges/batch
DELETE /api/v1/ip-ranges/{id}
```

### 11. 리포트 API (`/api/v1/reports`)
```http
GET /api/v1/reports/pdf
GET /api/v1/reports/traffic
GET /api/v1/reports/uptime
GET /api/v1/reports/logs
```

### 12. 상태 API (`/api/v1/status`)
```http
GET /api/v1/status/all
GET /api/v1/status/{mac}
GET /api/v1/status/role/{role}
GET /api/v1/status/classroom/{groupId}
GET /api/v1/status/performance
```

### 13. 레이아웃 API (`/api/v1/dashboards`)
```http
GET    /api/v1/dashboards
POST   /api/v1/dashboards
PUT    /api/v1/dashboards/{id}
DELETE /api/v1/dashboards/{id}
GET    /api/v1/dashboards/{id}/panels
POST   /api/v1/dashboards/{id}/panels
PUT    /api/v1/dashboards/{id}/panels/{panelId}
DELETE /api/v1/dashboards/{id}/panels/{panelId}
POST   /api/v1/dashboards/{id}/layouts/backup
GET    /api/v1/dashboards/{id}/layouts/backups
GET    /api/v1/dashboards/{id}/layouts/backups/{backupId}
POST   /api/v1/dashboards/{id}/layouts/restore/{backupId}
DELETE /api/v1/dashboards/{id}/layouts/backups/{backupId}
```

### 14. 위젯/파일 서빙 API (`/api/v1`)
```http
GET    /api/v1/dashboard/widgets
POST   /api/v1/dashboard/widgets
PUT    /api/v1/dashboard/widgets/{id}
DELETE /api/v1/dashboard/widgets/{id}

GET    /api/v1/dashboard/texts
POST   /api/v1/dashboard/texts
PUT    /api/v1/dashboard/texts/{id}
PUT    /api/v1/dashboard/texts/{id}/rename
DELETE /api/v1/dashboard/texts/{id}
POST   /api/v1/dashboard/texts/restore

GET    /api/v1/dashboard/images
POST   /api/v1/dashboard/images
PUT    /api/v1/dashboard/images/{id}
DELETE /api/v1/dashboard/images/{id}
POST   /api/v1/dashboard/images/upload

GET    /api/v1/files/{filename}
```

### 15. 알림 API (`/api/v1/alerts`)
```http
GET  /api/v1/alerts?limit=30&unreadOnly=false
GET  /api/v1/alerts/unread-count
POST /api/v1/alerts/{alertId}/ack
POST /api/v1/alerts/ack-all
```

### 16. 설정 API (`/api/v1/settings`)
```http
GET /api/v1/settings/{key}
PUT /api/v1/settings/{key}
```

### 17. 호환/레거시 API

호환 alias:
```http
GET /api/v1/classrooms
```

관리자 사용자 API (별도 베이스):
```http
GET    /api/users
GET    /api/users/{id}
POST   /api/users
PUT    /api/users/{id}
DELETE /api/users/{id}
```

---

## Deprecated 경로
아래 경로는 호환 목적의 구버전이며 대시보드 경로로 마이그레이션해야 합니다.

```http
GET    /api/v1/{groupId}/teacher-devices
POST   /api/v1/{groupId}/teacher-devices
DELETE /api/v1/teacher-devices/{id}
POST   /api/v1/pcs/broken
```

권장 대체:
- `/api/v1/dashboard/classrooms/{groupId}/teacher-devices`
- `/api/v1/dashboard/classrooms/teacher-devices/{id}`
- `/api/v1/dashboard/pcs/broken`

---

## 사용 예시

### cURL

#### A) Swagger/OpenAPI 확인
```bash
curl -I http://localhost:8888/swagger-ui/index.html
curl -s http://localhost:8888/v3/api-docs | jq '.paths | keys | length'
```

#### B) 로그인
```bash
curl -X POST "http://localhost:8888/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"비밀번호"}'
```

#### C) 페이지 조회
```bash
curl -X GET "http://localhost:8888/api/v1/groups?page=0&size=20" \
  -H "Authorization: Bearer <token>"
```

#### D) 명령 발행
```bash
curl -X POST "http://localhost:8888/api/v1/commands" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "scope":"GROUP",
    "groupId":1,
    "role":"ALL",
    "type":"SHUTDOWN",
    "payloadJson":"{}"
  }'
```

### PowerShell

#### A) 로그인 및 헤더 생성
```powershell
$login = Invoke-RestMethod -Uri "http://localhost:8888/api/v1/auth/login" `
  -Method POST `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"비밀번호"}'

$token = $login.token
$headers = @{ Authorization = "Bearer $token" }
```

#### B) Swagger/OpenAPI 확인
```powershell
Invoke-WebRequest -Uri "http://localhost:8888/swagger-ui/index.html" -Method GET | Select-Object StatusCode
(Invoke-RestMethod -Uri "http://localhost:8888/v3/api-docs" -Method GET).paths.Keys.Count
```

#### C) 강의실 요약 조회 (페이지)
```powershell
Invoke-RestMethod -Uri "http://localhost:8888/api/v1/dashboard/classrooms?page=0&size=20" `
  -Headers $headers -Method GET | ConvertTo-Json -Depth 6
```

---

## 상태 코드 가이드

| 코드 | 의미 |
|---|---|
| `200 OK` | 요청 성공 |
| `201 Created` | 리소스 생성 (예: 에이전트 등록) |
| `202 Accepted` | 큐 등록 완료, 비동기 처리 예정 (예: 명령 발행) |
| `204 No Content` | 성공, 본문 없음 |
| `400 Bad Request` | 파라미터/바디 검증 실패 |
| `401 Unauthorized` | 인증 실패 |
| `403 Forbidden` | 권한 없음 |
| `404 Not Found` | 리소스 없음 |
| `409 Conflict` | 상태 전이/중복 등 충돌 |
| `500 Internal Server Error` | 서버 오류 |

---

> **최종 갱신일**: 2026-04-17  
> **대상 런타임 포트**: 8888  
> **문서 기준**: backend 컨트롤러 구현 + SecurityConfig 기준
