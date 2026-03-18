# API 문서

## 개요
이 문서는 강의실 통합 대시보드 REST API의 모든 엔드포인트를 설명합니다.

**API 베이스 URL:** `http://localhost:8080/api/v1`

**Swagger UI:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

**OpenAPI Spec:** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

---

## 인증

현재 개발 환경에서는 모든 엔드포인트가 인증 없이 접근 가능합니다.
프로덕션 환경에서는 JWT 토큰 기반 인증이 필요합니다.

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

**요청 예시:**
```json
{
  "macAddress": "00:1A:2B:3C:4D:5E",
  "ipAddress": "192.168.10.10",
  "hostname": "PC-101-001",
  "traffic": {
    "inboundMbps": 5.2,
    "outboundMbps": 3.1
  },
  "timestamp": "2026-03-16T10:52:50"
}
```

**응답:**
```json
{
  "status": "ok"
}
```

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
| `404 Not Found` | 리소스를 찾을 수 없음 |
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

#### 모든 강의실 조회
```bash
curl -X GET "http://localhost:8080/api/v1/groups"
```

#### 신규 강의실 생성
```bash
curl -X POST "http://localhost:8080/api/v1/groups" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "강의실 103",
    "description": "2층 강의실"
  }'
```

#### 강의실 요약 정보 조회
```bash
curl -X GET "http://localhost:8080/api/v1/dashboard/classrooms"
```

#### PC 상태 조회
```bash
curl -X GET "http://localhost:8080/api/v1/dashboard/classrooms/1/pcs"
```

### PowerShell 예시

#### 모든 강의실 조회
```powershell
$response = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/groups" -Method GET -UseBasicParsing
$response.Content | ConvertFrom-Json | ConvertTo-Json
```

#### 신규 강의실 생성
```powershell
$body = @{
    name = "강의실 103"
    description = "2층 강의실"
} | ConvertTo-Json

$response = Invoke-WebRequest -Uri "http://localhost:8080/api/v1/groups" `
    -Method POST `
    -Header @{"Content-Type"="application/json"} `
    -Body $body `
    -UseBasicParsing
```

---

## 향후 확장 API

다음의 API들이 개발 예정입니다:

- **명령 관리**: PC 원격 제어 (종료, 재부팅, WOL 등)
- **스크립트 배포**: PS1 스크립트 업로드 및 배포
- **로그 조회**: PC 작업 로그 및 에러 로그 조회
- **리포트 생성**: 트래픽, 가동률 등 통계 리포트

---

## 지원

API 관련 질문이나 버그 보고는 [프로젝트 이슈](https://github.com/example/dashboard/issues)를 통해 주시기 바랍니다.
