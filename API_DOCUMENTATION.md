# API 문서

## 개요
이 문서는 강의실 통합 대시보드 REST API의 모든 엔드포인트를 설명합니다.

**API 베이스 URL:** `http://localhost:8888/api/v1`

**Swagger UI:** [http://localhost:8888/swagger-ui.html](http://localhost:8888/swagger-ui.html)

**OpenAPI Spec:** [http://localhost:8888/v3/api-docs](http://localhost:8888/v3/api-docs)

> 프로덕션 배포 시 Nginx 리버스 프록시(443 → 8888)를 통해 HTTPS로 접근합니다.

---

## 인증

### JWT 토큰 인증
대부분의 API 엔드포인트는 JWT 토큰 인증이 필요합니다.

**1. 토큰 발급:**
```
POST /api/v1/auth/login
Content-Type: application/json

{"username": "admin", "password": "비밀번호"}
```

**응답:**
```json
{"token": "eyJhbGciOiJIUzI1NiJ9..."}
```

**2. 인증 헤더 사용:**
```
Authorization: Bearer <token>
```

### 에이전트 인증
에이전트는 별도의 `X-Agent-Token` 헤더를 사용합니다.
에이전트 등록 시 발급받은 토큰을 사용하며, `/api/v1/heartbeat` 및 `/api/v1/agent/*` 경로에 적용됩니다.

---

## API 엔드포인트

### 1. 강의실(그룹) 관리 API

#### 1.1 모든 강의실 조회
```
GET /api/v1/groups
```

**설명:** 전체 강의실(그룹) 목록을 조회합니다.

**응답 예시:**
```json
[
  {
    "id": 1,
    "name": "강의실 101",
    "description": "1층 강의실",
    "createdAt": "2026-03-13T10:00:00"
  },
  {
    "id": 2,
    "name": "강의실 102",
    "description": "1층 강의실",
    "createdAt": "2026-03-13T10:00:00"
  }
]
```

**상태 코드:**
- `200 OK` - 강의실 목록 조회 성공

---

#### 1.2 신규 강의실 생성
```
POST /api/v1/groups
```

**설명:** 새로운 강의실(그룹)을 생성합니다.

**요청 예시:**
```json
{
  "name": "강의실 103",
  "description": "2층 강의실"
}
```

**응답 예시:**
```json
{
  "id": 3,
  "name": "강의실 103",
  "description": "2층 강의실",
  "createdAt": "2026-03-16T10:52:00"
}
```

**상태 코드:**
- `200 OK` - 강의실 생성 성공

---

#### 1.3 강의실 정보 수정
```
PUT /api/v1/groups/{id}
```

**설명:** 강의실의 이름과 설명을 수정합니다.

**경로 파라미터:**
- `id` (Long) - 강의실 ID

**요청 예시:**
```json
{
  "name": "강의실 101 (수정됨)",
  "description": "1층 강의실 - 프로젝터 설치됨"
}
```

**상태 코드:**
- `200 OK` - 강의실 수정 성공
- `404 Not Found` - 강의실을 찾을 수 없음

---

#### 1.4 강의실 삭제
```
DELETE /api/v1/groups/{id}
```

**설명:** 강의실을 삭제합니다. 관련 클라이언트 위치 정보도 정리됩니다.

**경로 파라미터:**
- `id` (Long) - 강의실 ID

**상태 코드:**
- `204 No Content` - 강의실 삭제 성공
- `404 Not Found` - 강의실을 찾을 수 없음

---

#### 1.5 IP 주소로 강의실에 PC 할당
```
POST /api/v1/groups/{id}/assign
```

**설명:** IP 주소를 기반으로 PC를 특정 강의실에 할당합니다.

**경로 파라미터:**
- `id` (Long) - 강의실 ID

**쿼리 파라미터:**
- `ip` (String) - PC의 IP 주소 예: `192.168.10.10`

**상태 코드:**
- `200 OK` - PC 할당 성공
- `404 Not Found` - 해당 IP의 PC를 찾을 수 없음

---

#### 1.6 MAC 주소로 강의실에 PC 할당
```
POST /api/v1/groups/assign/manual
```

**설명:** MAC 주소를 기반으로 PC를 특정 강의실에 할당합니다.

**쿼리 파라미터:**
- `mac` (String) - PC의 MAC 주소 예: `00:1A:2B:3C:4D:5E`
- `groupId` (Long) - 할당할 강의실 ID

**상태 코드:**
- `200 OK` - PC 할당 성공
- `404 Not Found` - 해당 MAC의 PC를 찾을 수 없음

---

### 2. 대시보드 API

#### 2.1 모든 강의실 요약 정보 조회
```
GET /api/v1/dashboard/classrooms
```

**설명:** 온라인 PC 수, 전체 PC 수, 마지막 하트비트 등 강의실별 요약 정보를 조회합니다.

**응답 예시:**
```json
[
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
  },
  {
    "groupId": 2,
    "groupName": "강의실 102",
    "onlinePcs": 12,
    "totalPcs": 18,
    "visible": true,
    "x": 500.5,
    "y": 200.5,
    "width": 400.0,
    "height": 300.0,
    "ipRanges": ["192.168.11.0/24"],
    "teacherPcStatus": "ONLINE"
  }
]
```

**상태 코드:**
- `200 OK` - 강의실 요약 조회 성공

---

#### 2.2 특정 강의실의 PC 상태 조회
```
GET /api/v1/dashboard/classrooms/{classroomId}/pcs
```

**설명:** 지정된 강의실에 속한 모든 PC의 현재 상태(온라인/오프라인, 역할 등)를 조회합니다.

**경로 파라미터:**
- `classroomId` (Long) - 강의실 ID

**응답 예시:**
```json
[
  {
    "id": 1,
    "hostname": "PC-101-001",
    "ipAddress": "192.168.10.10",
    "macAddress": "00:1A:2B:3C:4D:5E",
    "online": true,
    "lastSeen": "2026-03-16T10:52:45",
    "status": "ONLINE"
  },
  {
    "id": 2,
    "hostname": "PC-101-002",
    "ipAddress": "192.168.10.11",
    "macAddress": "00:1A:2B:3C:4D:5F",
    "online": false,
    "lastSeen": "2026-03-16T10:40:00",
    "status": "OFFLINE"
  }
]
```

**상태 코드:**
- `200 OK` - PC 상태 조회 성공

---

#### 2.3 네트워크 트래픽 조회
```
GET /api/v1/dashboard/traffic
```

**설명:** 현재 인바운드/아웃바운드 트래픽(Mbps)을 조회합니다.

**응답 예시:**
```json
{
  "inboundMbps": 45.5,
  "outboundMbps": 32.2
}
```

**상태 코드:**
- `200 OK` - 트래픽 조회 성공

---

### 3. 하트비트 API

#### 3.1 에이전트 하트비트 수신
```
POST /api/v1/heartbeat
```

**설명:** 클라이언트 PC 에이전트로부터 주기적인 생존 신호(하트비트)를 수신합니다.
PC의 IP, MAC, 네트워크 트래픽, 상태 정보 등을 업데이트하고 필요시 명령을 응답으로 전달합니다.
IP 주소 기반으로 강의실 자동 매핑 및 역할(교사/학생) 판별이 수행됩니다.

**인증:** `X-Agent-Token` 헤더

**요청 예시:**
```json
{
  "macAddress": "00:1A:2B:3C:4D:5E",
  "ipAddress": "192.168.10.10",
  "hostname": "PC-101-001",
  "inboundKbps": 5200.0,
  "outboundKbps": 3100.0,
  "positions": []
}
```

> **호환성 참고:** `inboundMbps`/`outboundMbps` (구버전) 및 `traffic` 리스트 형태도 수용합니다.

**응답:**
```json
{
  "status": "ok"
}
```

명령이 대기 중인 경우 응답에 명령 정보가 포함됩니다.

**상태 코드:**
- `200 OK` - 하트비트 수신 성공
- `400 Bad Request` - 잘못된 요청 형식

---

## 에러 코드

모든 엔드포인트는 다음의 표준 HTTP 상태 코드를 사용합니다:

| 상태 코드 | 설명 |
|--------|------|
| `200 OK` | 요청 성공 |
| `201 Created` | 리소스 생성 성공 |
| `204 No Content` | 요청 성공 (응답 본문 없음) |
| `400 Bad Request` | 잘못된 요청 |
| `401 Unauthorized` | 인증 필요 (JWT 토큰 없음/만료) |
| `403 Forbidden` | 권한 없음 |
| `404 Not Found` | 리소스를 찾을 수 없음 |
| `409 Conflict` | 리소스 충돌 (중복 IP 대역 등) |
| `500 Internal Server Error` | 서버 오류 |

---

## 데이터 모델

### ClassroomSummaryDto
강의실의 요약 정보를 나타냅니다.

| 필드 | 타입 | 설명 |
|-----|------|------|
| `groupId` | Long | 강의실 ID |
| `groupName` | String | 강의실 이름 |
| `onlinePcs` | int | 온라인 PC 수 |
| `totalPcs` | int | 전체 PC 수 |
| `visible` | boolean | 대시보드에 표시 여부 |
| `x` | Double | 대시보드 X 위치 |
| `y` | Double | 대시보드 Y 위치 |
| `width` | Double | 대시보드 패널 너비 |
| `height` | Double | 대시보드 패널 높이 |
| `ipRanges` | List<String> | 강의실의 IP 범위 목록 |
| `teacherPcStatus` | String | 교사 PC 상태 |

### PcStatusDto
PC의 상태 정보를 나타냅니다.

| 필드 | 타입 | 설명 |
|-----|------|------|
| `id` | Long | PC ID |
| `hostname` | String | PC 호스트명 |
| `ipAddress` | String | PC IP 주소 |
| `macAddress` | String | PC MAC 주소 |
| `online` | boolean | PC 온라인 여부 |
| `lastSeen` | LocalDateTime | 마지막 하트비트 수신 시간 |
| `status` | String | PC 상태 (ONLINE, OFFLINE, IDLE) |

### NetworkTrafficDto
네트워크 트래픽 정보를 나타냅니다.

| 필드 | 타입 | 설명 |
|-----|------|------|
| `inboundMbps` | double | 인바운드 트래픽 (Mbps) |
| `outboundMbps` | double | 아웃바운드 트래픽 (Mbps) |

---

## 사용 예시

### cURL 예시

#### 로그인 (토큰 발급)
```bash
curl -X POST "http://localhost:8888/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "비밀번호"}'
```

#### 모든 강의실 조회
```bash
curl -X GET "http://localhost:8888/api/v1/groups" \
  -H "Authorization: Bearer <token>"
```

#### 신규 강의실 생성
```bash
curl -X POST "http://localhost:8888/api/v1/groups" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "강의실 103",
    "description": "2층 강의실"
  }'
```

#### 강의실 요약 정보 조회
```bash
curl -X GET "http://localhost:8888/api/v1/dashboard/classrooms" \
  -H "Authorization: Bearer <token>"
```

#### PC 상태 조회
```bash
curl -X GET "http://localhost:8888/api/v1/dashboard/classrooms/1/pcs" \
  -H "Authorization: Bearer <token>"
```

### PowerShell 예시

#### 로그인 (토큰 발급)
```powershell
$login = Invoke-RestMethod -Uri "http://localhost:8888/api/v1/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"username":"admin","password":"비밀번호"}'
$token = $login.token
$headers = @{ Authorization = "Bearer $token" }
```

#### 모든 강의실 조회
```powershell
Invoke-RestMethod -Uri "http://localhost:8888/api/v1/groups" `
    -Headers $headers | ConvertTo-Json
```

#### 신규 강의실 생성
```powershell
$body = @{
    name = "강의실 103"
    description = "2층 강의실"
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://localhost:8888/api/v1/groups" `
    -Method POST `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $body
```

---

## 추가 API 엔드포인트

아래는 현재 구현 완료된 추가 API 엔드포인트입니다.

### 4. 에이전트 관리 API

#### 4.1 에이전트 등록
```
POST /api/v1/agent/register
```
**설명:** 에이전트가 최초 서버에 등록합니다. 설치 키 인증 후 에이전트 토큰을 발급합니다.

#### 4.2 설치 키 조회
```
GET /api/v1/admin/install-key
```
**설명:** 에이전트 설치에 필요한 현재 설치 키를 조회합니다.

#### 4.3 등록된 에이전트 목록
```
GET /api/v1/admin/agents
```
**설명:** 등록된 모든 에이전트 목록을 조회합니다.

#### 4.4 에이전트 토큰 폐기
```
PUT /api/v1/admin/agents/{mac}/revoke
```
**설명:** 특정 에이전트의 인증 토큰을 폐기합니다.

#### 4.5 에이전트 삭제
```
DELETE /api/v1/admin/agents/{mac}
```

---

### 5. 명령 관리 API

#### 5.1 명령 발행
```
POST /api/v1/commands
```
**설명:** 단일/일괄로 PC에 명령을 전송합니다 (종료, 재부팅, 명령 실행, PS1 실행, WOL 등).

**요청 예시:**
```json
{
  "scope": "GROUP",
  "groupId": 1,
  "role": "ALL",
  "type": "SHUTDOWN",
  "payload": ""
}
```

#### 5.2 IP 지정 명령 발행
```
POST /api/v1/commands/to-ip
```

#### 5.3 발행된 명령 목록 조회
```
GET /api/v1/commands
```

#### 5.4 명령 ACK (에이전트)
```
POST /api/v1/agent/commands/{taskId}/ack
```
**설명:** 에이전트가 명령 수신을 확인합니다.

#### 5.5 명령 결과 보고 (에이전트)
```
POST /api/v1/agent/commands/{taskId}/result
```
**설명:** 에이전트가 명령 실행 결과를 보고합니다.

---

### 6. WOL (Wake-on-LAN)

```
POST /api/v1/admin/enqueue
```
**설명:** 단일 PC에 WOL 명령을 큐잉합니다.

```
POST /api/v1/admin/group/wol
```
**설명:** 강의실 전체에 WOL을 일괄 전송합니다. 강의실 내 온라인 에이전트가 매직패킷을 릴레이합니다.

---

### 7. 스크립트 배포 API

#### 7.1 스크립트 업로드
```
POST /api/v1/scripts
Content-Type: multipart/form-data
```
**설명:** PS1 스크립트 파일을 업로드합니다. 해시 검증이 포함됩니다.

#### 7.2 스크립트 다운로드
```
GET /api/v1/scripts/{id}/download
```
**설명:** 업로드된 스크립트를 다운로드합니다 (에이전트 사용).

---

### 8. IP 대역 관리 API

```
GET    /api/v1/ip-ranges              # 전체 IP 대역 조회
POST   /api/v1/ip-ranges              # IP 대역 추가
POST   /api/v1/ip-ranges/batch        # 일괄 IP 대역 추가
DELETE /api/v1/ip-ranges/{id}         # IP 대역 삭제
```

강의실별 IP 대역은 대시보드 API에서도 관리 가능합니다:
```
GET    /api/v1/dashboard/classrooms/{groupId}/ip-ranges
POST   /api/v1/dashboard/classrooms/{groupId}/ip-ranges
DELETE /api/v1/dashboard/classrooms/ip-ranges/{id}
```

---

### 9. 교사 PC 관리 API

```
GET    /api/v1/dashboard/classrooms/{groupId}/teacher-devices
POST   /api/v1/dashboard/classrooms/{groupId}/teacher-devices
DELETE /api/v1/dashboard/classrooms/teacher-devices/{id}
```

별도 컨트롤러:
```
GET    /api/v1/{groupId}/teacher-devices
POST   /api/v1/{groupId}/teacher-devices
DELETE /api/v1/teacher-devices/{id}
```

---

### 10. PC 고장 등록 API

```
POST   /api/v1/dashboard/pcs/broken        # 고장 등록
GET    /api/v1/dashboard/pcs/broken         # 고장 목록
DELETE /api/v1/dashboard/pcs/broken/{mac}   # 고장 해제
PUT    /api/v1/dashboard/pcs/broken/{mac}   # 고장 메모 수정
```

---

### 11. 리포트 API

```
GET /api/v1/reports/pdf              # PDF 리포트 다운로드
GET /api/v1/reports/traffic          # 트래픽 리포트
GET /api/v1/reports/uptime           # 가동률 리포트
GET /api/v1/reports/logs             # 트래픽 로그 조회 (페이징)
```

---

### 12. PC 상태 조회 API

```
GET /api/v1/status/all                      # 전체 PC 상태
GET /api/v1/status/{mac}                    # MAC으로 개별 조회
GET /api/v1/status/role/{role}              # 역할별 조회 (TEACHER/STUDENT)
GET /api/v1/status/classroom/{groupId}      # 강의실별 조회
GET /api/v1/status/performance              # 성능 데이터
```

---

### 13. 대시보드 레이아웃 API

```
GET    /api/v1/dashboards                   # 대시보드 목록
POST   /api/v1/dashboards                   # 대시보드 생성
PUT    /api/v1/dashboards/{id}              # 대시보드 수정
DELETE /api/v1/dashboards/{id}              # 대시보드 삭제
GET    /api/v1/dashboards/{id}/panels       # 패널 목록
POST   /api/v1/dashboards/{id}/panels       # 패널 추가
PUT    /api/v1/dashboards/{id}/panels/{panelId}  # 패널 수정
DELETE /api/v1/dashboards/{id}/panels/{panelId}  # 패널 삭제
```

---

### 14. 위젯 API (텍스트/이미지)

```
GET    /api/v1/dashboard/texts              # 텍스트 위젯 조회
POST   /api/v1/dashboard/texts              # 텍스트 위젯 생성
PUT    /api/v1/dashboard/texts/{id}         # 텍스트 위젯 수정
DELETE /api/v1/dashboard/texts/{id}         # 텍스트 위젯 삭제
POST   /api/v1/dashboard/texts/restore      # 텍스트 위젯 복원

GET    /api/v1/dashboard/images             # 이미지 위젯 조회
POST   /api/v1/dashboard/images             # 이미지 위젯 생성
PUT    /api/v1/dashboard/images/{id}        # 이미지 위젯 수정
DELETE /api/v1/dashboard/images/{id}        # 이미지 위젯 삭제
POST   /api/v1/dashboard/images/upload      # 이미지 파일 업로드

GET    /api/v1/dashboard/widgets            # 범용 위젯 조회
POST   /api/v1/dashboard/widgets            # 범용 위젯 생성
PUT    /api/v1/dashboard/widgets/{id}       # 범용 위젯 수정
DELETE /api/v1/dashboard/widgets/{id}       # 범용 위젯 삭제
```

---

### 15. 대시보드 모듈 API

```
GET    /api/v1/dashboard/modules            # 전체 모듈 조회
GET    /api/v1/dashboard/modules/group/{groupId}  # 그룹별 모듈
POST   /api/v1/dashboard/modules            # 모듈 생성
PUT    /api/v1/dashboard/modules/{id}       # 모듈 수정
DELETE /api/v1/dashboard/modules/{id}       # 모듈 삭제
```

---

### 16. 설정 API

```
GET /api/v1/settings/{key}                  # 설정값 조회
PUT /api/v1/settings/{key}                  # 설정값 변경
```

---

### 17. 감사 로그 API

```
GET /api/v1/admin/audit/logs                # 감사 로그 조회
```

---

> **최종 갱신일**: 2026-03-30 | 포트: 8888 | 인증: JWT Bearer Token
