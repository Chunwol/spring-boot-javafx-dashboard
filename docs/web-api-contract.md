# Web API Contract

> 기준: 현재 작업 트리의 `backend/src/main`, `common/src/main`, `frontend/src/main` 구현을 직접 대조한 결과다. 기존 Swagger 설명이나 과거 문서와 충돌하면 이 문서에 적힌 현재 소스 동작을 우선한다.

## 1. 기본 규약

- REST 기본 경로: `/api/v1`
- 기본 콘텐츠 형식: JSON (`application/json`)
- 사용자 인증: `Authorization: Bearer <JWT>`
- 에이전트 인증: `X-Agent-Token: <agent-token>`
- 최초 에이전트 등록: `X-Agent-Install-Key: <install-key>`
- 사용자 JWT는 서버 세션을 만들지 않는 stateless 방식이다. 기본 만료 시간은 설정상 1시간이며 refresh/logout API는 없다.
- 날짜/시간은 Jackson `JavaTimeModule`을 통해 ISO-8601 문자열로 직렬화된다.
- JSON 필드명은 별도 표기가 없으면 Java 프로퍼티의 camelCase다.
- `204 No Content` 응답에는 본문이 없다.

### 인증 등급

| 표기 | 실제 요구사항 |
|---|---|
| `PUBLIC` | 사용자 JWT 없이 접근 가능 |
| `INSTALL` | `X-Agent-Install-Key`를 서비스에서 검증 |
| `AGENT` | Spring Security는 통과시키지만 `AgentTokenInterceptor`가 `X-Agent-Token`을 검증 |
| `VIEW` | `ADMIN`, `USER`, `VIEWER` 중 하나의 사용자 JWT |
| `WRITE` | `ADMIN` 또는 `USER` 사용자 JWT. `VIEWER`는 거부 |
| `ADMIN` | `ADMIN` 사용자 JWT |

Spring Security의 전역 기본값은 `GET /api/v1/** -> VIEW`, 그 외 `/api/v1/** -> WRITE`다. 컨트롤러의 `@PreAuthorize`가 있으면 더 좁은 권한이 적용된다.

예외 공개 경로는 `/actuator/health`, `/actuator/health/**`, 로그인, 에이전트 등록, 에이전트 전용 경로, `/ws/**`, Swagger/OpenAPI다. `/actuator/prometheus`는 `ADMIN` 전용이다.

### 공통 오류 형식

컨트롤러 예외 처리기의 표준 오류는 다음 형식이다.

```json
{
  "timestamp": "2026-08-07T16:00:00",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "입력값 검증에 실패했습니다.",
  "path": "/api/v1/commands",
  "errors": [
    { "field": "type", "reason": "type 값이 올바르지 않습니다.", "rejectedValue": "..." }
  ]
}
```

단, 다음 응답은 이 형식과 다르다.

- 잘못된 에이전트 토큰: `401 {"error":"Invalid or missing agent token"}`
- 일부 컨트롤러의 직접 `Map` 오류: `{"error":...}` 또는 `{"message":...}`
- 청크 업로드 오류: `{code,message,retryable,timestamp,details?}`
- Spring Security가 필터 단계에서 만든 401/403: 표준 `ApiErrorResponse`가 보장되지 않는다.

## 2. REST 엔드포인트

`JavaFX` 열은 현재 프론트 소스에서 직접 또는 서비스 래퍼를 통해 호출하는지 나타낸다. 동적 URL 조합은 동일한 대표 경로로 표시했다.

### 인증, 사용자, 설정

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| POST | `/auth/login` | PUBLIC | `LoginRequest` | `200 LoginResponse` | O |
| GET | `/users` | ADMIN | 없음 | `200 UserResponseDto[]` | O |
| GET | `/users/{id}` | ADMIN | path `id` | `200 UserResponseDto` | O |
| POST | `/users` | ADMIN | `UserSaveRequestDto` | `201 UserResponseDto` | O |
| PUT | `/users/{id}` | ADMIN | `UserUpdateRequestDto` | `200 UserResponseDto` | O |
| DELETE | `/users/{id}` | ADMIN | path `id` | `204` | O |
| GET | `/settings/{key}` | ADMIN, USER | path `key` | `200 {key,value}` | O |
| PUT | `/settings/{key}` | ADMIN, USER | `{value:string}` | `204` | O |
| PUT | `/admin/change-password` | ADMIN | `{currentPassword,newPassword}`; 새 비밀번호 8자 이상 | `200 {status:"ok"}` | O |

### 관리자와 에이전트 등록

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| POST | `/agent/register` | INSTALL | header + `AgentRegisterRequest` | `201 AgentRegisterResponse` | X |
| GET | `/agent/registration-status` | ADMIN | 없음 | `200 {enabled:boolean}` | O |
| PUT | `/agent/registration-status` | ADMIN | `{enabled:boolean}` | `204` | O |
| GET | `/agent/config/download` | ADMIN | 없음 | `200 application/json`, attachment `config.json` | O |
| GET | `/admin/install-key` | ADMIN | 없음 | `200 {configured,keyPreview,key}` | O |
| PUT | `/admin/install-key` | ADMIN | 없음 | 새 설치키를 담은 동일 응답 | O |
| GET | `/admin/agents` | ADMIN | 없음 | `200 AgentAdminView[]` | O |
| PUT | `/admin/agents/{mac}/revoke` | ADMIN | path `mac` | `200 {status:"revoked",mac}` | O |
| DELETE | `/admin/agents/{mac}` | ADMIN | path `mac` | `200 {status:"deleted",mac}` | O |
| GET | `/admin/audit/logs` | ADMIN | `action?`, `actor?`, `limit=100` (1..500) | `200 AuditLogView[]` | X |
| POST | `/admin/enqueue` | ADMIN | query `mac,type,scriptUrl?` | `200 {status:"queued"}` | X |
| POST | `/admin/group/wol` | ADMIN | query `groupId` | `200 {status,queued,...}` | X |

`AgentAdminView` 필드: `macAddress,pcName,ipAddress,status,role,groupId,groupName,lastSeen,tokenActive,tokenIssuedAt,tokenLastUsedAt,orphan`.

`AuditLogView` 필드: `id,actor,action,targetType,targetId,detailJson,ipAddress,createdAt`.

`/agent/config/download` 응답 필드: `serverUrl,installKey,agentToken:"",heartbeatIntervalSec:12,jitterSec:3,githubOwner:"",githubRepo:""`.

### 알림

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/alerts` | VIEW | query `limit=30`, `unreadOnly=false` | `200 AlertDto[]` | O |
| GET | `/alerts/unread-count` | VIEW | 없음 | `200 {unread:number}` | O |
| POST | `/alerts/{alertId}/ack` | ADMIN, USER | 빈 본문 허용 | `200 AlertDto` | O |
| POST | `/alerts/ack-all` | ADMIN, USER | 빈 본문 허용 | `200 {acknowledged:number}` | O |

### 강의실, PC, 네트워크 상태

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/dashboard/classrooms` | VIEW | Spring `Pageable` (`page,size,sort`) | `200 Page<ClassroomSummaryDto>` | O |
| GET | `/dashboard/pcs` | VIEW | 없음 | `200 PcStatusDto[]` (비페이지) | O |
| GET | `/dashboard/classrooms/{classroomId}/pcs` | VIEW | path + `Pageable` | `200 Page<PcStatusDto>` | O |
| GET | `/dashboard/classrooms/{classroomId}/traffic/pcs` | VIEW | path | `200 PcTrafficView[]` | O |
| GET | `/dashboard/traffic` | VIEW | 없음 | `200 NetworkTrafficDto` | O |
| GET | `/dashboard/traffic/summary` | VIEW | 없음 | 위와 동일한 alias | X |
| GET | `/dashboard/config/traffic-thresholds` | VIEW | 없음 | `{globalMbps,classroomMbps,pcMbps}` | O |
| GET | `/dashboard/refresh` | VIEW | 없음 | `DashboardRefreshDto` | O |
| GET | `/status/all` | VIEW | 없음 | `PcStatusDto[]` | O |
| GET | `/status/{mac}` | VIEW | path `mac` | `PcStatusDto` | X |
| GET | `/status/role/{role}` | VIEW | `TEACHER` 또는 `STUDENT` | `PcStatusDto[]` | X |
| GET | `/status/classroom/{groupId}` | VIEW | path `groupId` | `PcStatusDto[]` | X |
| GET | `/status/performance` | VIEW | 없음 | `PerformanceSnapshot` | O |

`PcTrafficView` 필드: `pcName,ipAddress,macAddress,status,inboundMbps,outboundMbps,updatedAt`. 최근 10분 로그 중 PC별 최신값을 사용한다.

`PerformanceSnapshot` 최상위 필드: `timestamp,pc,command,token,cache,operational,pressure`. `pc`에는 `total,online,offline,onlineRatio`, `command`에는 `queued,delivered,acked,completed,failed,inFlight,staleDelivered,staleAcked`, `token`에는 `active,revoked`가 있다.

### 강의실 관리와 배치

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/groups?page=0&size=20` | ADMIN | 페이지 query | `Page<ClassroomGroup>` | O |
| POST | `/groups` | ADMIN | `ClassroomGroup` | `200 ClassroomGroup` | O |
| PUT | `/groups/{id}` | ADMIN | `ClassroomGroup` | `200 ClassroomGroup` | O |
| DELETE | `/groups/{id}` | ADMIN | path | `204` | O |
| POST | `/groups/{id}/assign` | ADMIN | query `ip` | `200` 또는 `404` | X |
| POST | `/groups/assign/manual` | ADMIN | query `mac,groupId` | `200` 또는 `404` | X |
| POST | `/groups/{id}/positions` | ADMIN | `ClientPosition` | `200 ClientPosition` | X |
| GET | `/groups/{id}/positions` | ADMIN | path | `ClientPosition[]` | X |
| GET | `/ip-ranges?groupId=&page=0&size=50` | VIEW | 필터/페이지 query | `Page<ClassroomIpRange>` | O |
| POST | `/ip-ranges` | WRITE | `ClassroomIpRange` | `200 ClassroomIpRange` | O |
| POST | `/ip-ranges/batch` | WRITE | `IpRangeBatchRequest` | `{created,items:ClassroomIpRange[]}` | X |
| DELETE | `/ip-ranges/{id}` | WRITE | path | `200` 빈 본문 | O |
| GET | `/dashboard/classrooms/{groupId}/ip-ranges` | VIEW | path | `ClassroomIpRange[]` | O |
| POST | `/dashboard/classrooms/{groupId}/ip-ranges` | WRITE | `ClassroomIpRange`; path가 `groupId`를 덮어씀 | `200 ClassroomIpRange` | O |
| DELETE | `/dashboard/classrooms/ip-ranges/{id}` | WRITE | path | `204` | O |
| GET | `/dashboard/classrooms/{groupId}/teacher-devices` | VIEW | path | `ClassroomTeacherDevice[]` | O |
| POST | `/dashboard/classrooms/{groupId}/teacher-devices` | WRITE | `ClassroomTeacherDevice`; path가 `groupId`를 덮어씀 | `200 ClassroomTeacherDevice` | O |
| DELETE | `/dashboard/classrooms/teacher-devices/{id}` | WRITE | path | `204` | O |
| POST | `/dashboard/pcs/broken` | WRITE | `{macAddress?,ipAddress?,note?,seatNumber?}`; MAC/IP 중 하나 필수 | `200 PcStatusDto` | O |
| GET | `/dashboard/pcs/broken` | VIEW | 없음 | `PcStatusDto[]` | O |
| PUT | `/dashboard/pcs/broken/{macAddress}` | WRITE | `{note}` | `200 PcStatusDto` | O |
| DELETE | `/dashboard/pcs/broken/{macAddress}` | WRITE | path | `200 PcStatusDto` | O |

`seatNumber`는 JSON 문자열로 받고 양의 정수로 파싱한다. IP만 주어지고 기존 PC가 없으면 IP 범위로 강의실을 찾은 뒤 `BROKEN-IP-...` MAC의 placeholder를 만들며 이름을 `미사용 PC {seatNumber}`로 설정한다.

### 원격 명령과 에이전트 하트비트

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| POST | `/commands` | ADMIN | `CommandRequestDto` | `202 CommandEnqueueResult` | O |
| POST | `/commands/to-ip` | ADMIN | `{ipAddress,type,scriptUrl?,payloadJson?,broadcastIp?}` | `202 CommandEnqueueResult` | O |
| GET | `/commands` | ADMIN | `status?`, `taskId?`, `page=0`, `size=20` | taskId 지정 시 `CommandView`, 아니면 `Page<CommandView>` | O |
| POST | `/commands/{taskId}/cancel` | ADMIN | 없음 | `{taskId,status:"CANCELLED",completedAt?}` | O |
| GET | `/agent/commands/next` | AGENT | 없음 | 명령 시 `200 {command}`, 없으면 최대 10초 뒤 `204` | X (agent 사용) |
| POST | `/agent/commands/{taskId}/ack` | AGENT | 본문 선택/현재 미사용 | `{status:"ok",...}` | X (agent 사용) |
| POST | `/agent/commands/{taskId}/result` | AGENT | `{exitCode,stdout?,stderr?}` | `{status:"ok",...}` | X (agent 사용) |
| POST | `/heartbeat` | AGENT | `HeartbeatDto` | `{command?:object}` | X (agent 사용) |
| POST | `/heartbeat/offline` | AGENT | `HeartbeatOfflineDto` | `{accepted:true}` | X (agent 사용) |

`CommandEnqueueResult`은 실행 경로에 따라 `queued,taskIds,duplicateSkipped`가 기본이며 WOL에서는 `leaderMac,broadcastIp,message` 등이 추가될 수 있다.

`CommandView` 필드: `taskId,macAddress,commandType,status,payloadJson,scriptUrl,createdAt,deliveredAt,ackedAt,completedAt,exitCode,stdout,stderr,retryCount,isExecuted,verificationStatus,verificationStartedAt,verificationDeadlineAt,verificationObservedOfflineAt,verificationCompletedAt,scmateResetStatus,scmateResetRequestedAt,scmateResetCompletedAt` 및 조회 가능한 경우 `pcName,ipAddress,groupId,groupName,pcStatus,role`.

상태 기본 필터는 `QUEUED,DELIVERED,ACKED`이며 `status`는 쉼표로 여러 값을 지정할 수 있다. 취소 API는 모든 명령 종류의 `QUEUED` 상태에 적용되고 이미 전달된 명령은 `409`로 거부한다. ACK/result는 에이전트 토큰에서 얻은 MAC과 명령 소유 MAC을 비교한다. 동일 결과 재전송은 `alreadyAcked`, `alreadyFinal`, `commandStatus` 필드로 멱등 처리된다.

### 대시보드 레이아웃과 패널

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/dashboards` | VIEW | 없음 | `DashboardLayout[]` | O |
| POST | `/dashboards` | WRITE | `DashboardLayout` | `201 DashboardLayout` | O |
| PUT | `/dashboards/{id}` | WRITE | `DashboardLayout` | `200 DashboardLayout` | 부분 사용 |
| DELETE | `/dashboards/{id}` | WRITE | path | `204` | O |
| GET | `/dashboards/{id}/panels` | VIEW | path | `DashboardPanel[]` | O |
| POST | `/dashboards/{id}/panels` | WRITE | `DashboardPanelUpsertRequest` | `201 DashboardPanel` | O |
| PUT | `/dashboards/{id}/panels/{panelId}` | WRITE | `DashboardPanelUpsertRequest` | `200 DashboardPanel` | O |
| DELETE | `/dashboards/{id}/panels/{panelId}` | WRITE | path | `204` | O |
| PUT | `/dashboards/{id}/panels/batch-position` | WRITE | `DashboardPanelBatchPositionRequest` | `DashboardPanel[]` | O |
| POST | `/dashboards/{id}/layouts/backup` | WRITE | `{name?,description?,layoutData?}` | `201 DashboardLayoutBackup` | X |
| GET | `/dashboards/{id}/layouts/backups?page=0&size=20` | VIEW | 페이지 query | `Page<DashboardLayoutBackup>` | X |
| GET | `/dashboards/{id}/layouts/backups/{backupId}` | VIEW | path | `DashboardLayoutBackup` | X |
| POST | `/dashboards/{id}/layouts/restore/{backupId}` | WRITE | 없음 | `DashboardLayoutBackup` | X |
| DELETE | `/dashboards/{id}/layouts/backups/{backupId}` | WRITE | path | `204` | X |

현재 JavaFX `DashboardLayoutService`의 백업/복원은 위 서버 API가 아니라 사용자 홈의 `Dashboard Backups` 폴더에 JSON 파일을 직접 저장한다.

### 대시보드 모듈, 텍스트, 이미지

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/dashboard/modules` | VIEW | 없음 | `DashboardModule[]` | X |
| GET | `/dashboard/modules/group/{groupId}` | VIEW | path | `DashboardModule[]` | X |
| POST | `/dashboard/modules` | WRITE | `DashboardModule` | `200 DashboardModule` | X |
| PUT | `/dashboard/modules/{id}` | WRITE | `DashboardModule` | `200 DashboardModule` | X |
| DELETE | `/dashboard/modules/{id}` | WRITE | path | `204` | X |
| GET | `/dashboard/widgets` | VIEW | 없음 | `DashboardWidgetItem[]` | X |
| POST | `/dashboard/widgets` | WRITE | `DashboardWidgetItem` | `201 DashboardWidgetItem` | X |
| PUT | `/dashboard/widgets/{id}` | WRITE | 부분 `DashboardWidgetItem` | `200 DashboardWidgetItem` | X |
| DELETE | `/dashboard/widgets/{id}` | WRITE | path | `204` | X |
| GET | `/dashboard/texts?dashboardId=` | VIEW | 선택 필터 | `WidgetTextView[]` | O |
| POST | `/dashboard/texts` | WRITE | `WidgetTextInput` | `201 WidgetTextView` | O |
| PUT | `/dashboard/texts/{id}` | WRITE | 부분 `WidgetTextInput` | `200 WidgetTextView` | O |
| PUT | `/dashboard/texts/{id}/rename` | WRITE | `{name?,dashboardId?}` | `200 WidgetTextView` | O |
| DELETE | `/dashboard/texts/{id}` | WRITE | path | `204` | O |
| POST | `/dashboard/texts/restore` | WRITE | `WidgetTextInput[]` | `{restored,ids}` | O |
| GET | `/dashboard/images?dashboardId=` | VIEW | 선택 필터 | `WidgetImageView[]` | O |
| POST | `/dashboard/images` | WRITE | `WidgetImageInput` | `201 WidgetImageView` | X |
| PUT | `/dashboard/images/{id}` | WRITE | 부분 `WidgetImageInput` | `200 WidgetImageView` | O |
| DELETE | `/dashboard/images/{id}` | WRITE | path | `204` | O |
| POST | `/dashboard/images/upload` | WRITE | multipart `file`; query `x=100,y=100,width=360,height=240,dashboardId?` | `201 WidgetImageView` | O |
| GET | `/files/{filename}` | VIEW | path; 하위 경로 금지 | 파일 inline 응답 | O (직접 URL 로드) |

`WidgetTextView`: `id,groupId,dashboardId,type,x,y,width,height,visible,text,config`.

`WidgetImageView`: `id,groupId,dashboardId,type,x,y,width,height,visible,imageUrl,config`.

입력은 공통 필드 `groupId,dashboardId,x,y,width,height,visible` 외의 키를 `config` JSON에 저장한다. 텍스트는 보통 `text,name,font...`, 이미지는 `imageUrl,name...`을 추가한다. 이미지 업로드는 10 MiB 이하이며 `Content-Type`이 `image/*`여야 한다.

### 보고서와 로그

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| GET | `/reports/monthly` | VIEW | 없음 | `MonthlyReport[]` | X |
| GET | `/reports/monthly/{yearMonth}` | VIEW | `YYYY-MM` | `MonthlyReport` | X |
| POST | `/reports/monthly/{yearMonth}/regenerate` | WRITE | `YYYY-MM` | `MonthlyReport` | X |
| GET | `/reports/pdf` | VIEW | `type=WEEKLY`, `year?`, `month?`, `fromDate?`, `toDate?` | `application/pdf` attachment | O |
| GET | `/reports/traffic` | VIEW | `from?`, `to?`, `groupId?` | `TrafficReportView` | X |
| GET | `/reports/uptime` | VIEW | `from?`, `to?`, `groupId?` | `UptimeReportView` | X |
| GET | `/reports/logs?page=0&size=50` | VIEW | 페이지 query | `Page<{timestamp,message}>` | X |

`TrafficReportView`: `from,to,totalInboundKbps,totalOutboundKbps,dailyInbound,byTimeSlot`.

`UptimeReportView`: `from,to,averageUptimePct,groups[]`; 각 그룹 행은 보고서 서비스가 만든 동적 맵이다.

PDF 기간은 `fromDate/toDate` 둘 다 유효하면 사용자 지정 기간, 아니면 `MONTHLY + year + month`, 그 외에는 현재 주 월요일부터 일요일까지다. 잘못된 날짜 문자열은 오류가 아니라 기본 기간으로 조용히 대체된다.

### 스크립트 및 수집 파일 전송

| Method | Path | Auth | Request | Success response | JavaFX |
|---|---|---|---|---|---|
| POST | `/scripts` | ADMIN | multipart `file` | `{id,sha256,downloadUrl}` | X (현재는 청크 API 사용) |
| POST | `/file-chunks` | ADMIN | query `uploadId,chunkIndex,totalChunks,chunkHash`; multipart `file` | `ChunkUploadResult` | O |
| POST | `/file-chunks/assemble` | ADMIN | query `uploadId,originalFilename,totalChunks`; 빈 POST | `AssembleResult` | O |
| GET | `/scripts/{id}/download` | AGENT | optional `Range` | 파일 다운로드 | X (agent 사용) |
| POST | `/agent/uploads` | AGENT | multipart `file`; query `originalFilename?,taskId?` | `{id,sha256,taskId?,downloadUrl}` | X (agent 사용) |
| POST | `/agent/file-chunks` | AGENT | 관리자 청크 API와 동일 | `ChunkUploadResult` | X (agent 사용) |
| POST | `/agent/file-chunks/assemble` | AGENT | query + `taskId?` | `AssembleResult`, 관리자 다운로드 URL로 변환 | X (agent 사용) |
| GET | `/agent/uploads/{id}/download` | ADMIN | optional `Range` | 파일 다운로드 | O |

`ChunkUploadResult`: `uploadId,chunkIndex,totalChunks,uploadedChunks,missingChunks,missingChunkIndexesPreview,resumed,complete`.

`AssembleResult`: `id,sha256,sizeBytes,downloadUrl,deduplicated,manifestKey`; 에이전트 조립에는 `taskId`가 추가될 수 있다.

업로드 제약:

- 전역 multipart 기본 한도는 파일/요청 각각 200 MiB이며 환경 변수로 변경 가능하다.
- JavaFX는 관리자 파일을 8 MiB 청크로 나눈다.
- `uploadId`: `[A-Za-z0-9_-]` 8~120자.
- `chunkHash`: SHA-256 64자리 hex.
- `totalChunks`: 1~200,000.
- 동일 청크가 크기와 해시까지 같으면 `resumed:true`로 재사용한다.
- 에이전트 수집 아티팩트 총량 기본 한도는 30 GiB이며 초과 시 `507`을 반환한다.

다운로드 동작:

- `Content-Disposition: attachment`, `Content-Type: application/octet-stream`.
- `Accept-Ranges: bytes`, `X-Script-SHA256` 헤더를 제공한다.
- 단일 byte range만 지원하며 정상 range는 `206`, 잘못된/multi-range는 `416`과 `Content-Range: bytes */<size>`다.
- Nginx 가속 모드에서는 애플리케이션이 `X-Accel-Redirect`를 반환하고 실제 본문 전송은 Nginx가 담당한다.
- JavaFX `downloadToFile`은 Range 재개를 사용하지 않고 처음부터 스트리밍 저장한다.

### 파일 배포 작업

모든 엔드포인트는 `ADMIN` 전용이다. 쓰기 요청은 `app.file-distribution.security.enabled=true`일 때 추가로 아래 서명을 요구한다.

- `X-FD-Timestamp`: Unix epoch seconds
- `X-FD-Signature`: `hex(HMAC-SHA256(sharedSecret, timestamp + "\n" + canonicalPayload))`
- 기본 허용 시각 오차: 60초
- 기본값상 HTTPS 필수

| Method | Path | Request | Success response | JavaFX |
|---|---|---|---|---|
| POST | `/file-distribution/jobs` | `FileDistributionCreateInput` | `FileDistributionCreateResult` | O |
| GET | `/file-distribution/jobs` | 없음 | 최근 50개 `FileDistributionJobView[]` | O |
| GET | `/file-distribution/jobs/{jobId}` | path | `FileDistributionStatusView` | O |
| GET | `/file-distribution/jobs/{jobId}/events` | path | `{id,eventType,detailJson,createdAt}[]` | O |
| POST | `/file-distribution/jobs/{jobId}/cancel` | 없음 | `{jobId,status:"CANCELLED"}` | X |
| POST | `/file-distribution/jobs/{jobId}/waves/{wave}/cancel` | 없음 | `{jobId,wave,status:"CANCELLED"}` | X |
| POST | `/file-distribution/jobs/{jobId}/waves/{wave}/start` | 없음 | `{jobId,wave,status:"IN_PROGRESS"}` | X |
| POST | `/file-distribution/jobs/{jobId}/retry-failed` | `{wave?}` | `{jobId,retried,waves}` | X |
| POST | `/file-distribution/jobs/{jobId}/rollback` | `{rollbackFromJobId,waveSize=20,canaryOnly=false,groupId?}` | `{sourceJobId,rollbackFromJobId,newJobId,status}` | X |
| POST | `/file-distribution/jobs/{jobId}/agents/{agentId}/result` | `{success,errorMessage?}` | `{jobId,agentId,accepted:true}` | X |

`FileDistributionCreateInput`: `uploadId` 필수, `originalFilename="unknown.bin"`, `totalChunks=1`, `waveSize=5`, `canaryOnly=false`, `groupId?`, `artifactId?`, `targetVersion?`, `sourceMode="HYBRID_PEER"`, `rampUpFactor?`.

`FileDistributionCreateResult`: `jobId,status,uploadId,artifactId,targetVersion,sourceMode,rampUpFactor`.

`FileDistributionStatusView`: `jobId,uploadId,status,artifactId,targetVersion,sourceMode,rampUpFactor,totalAgents,completed,failed,inProgress,cancelled,progress,createdAt,completedAt,cancelledAt,waves`.

`FileDistributionJobView`: `jobId,uploadId,originalFilename,status,artifactId,targetVersion,sourceMode,rampUpFactor,createdAt,completedAt,cancelledAt,errorMessage`.

### 호환용 구버전 경로

다음 경로는 현재 동작하지만 `@Deprecated`/`@Hidden`이며 새 웹 프론트에서는 사용하지 않는다.

| Method | Deprecated path | Canonical path |
|---|---|---|
| GET | `/classrooms` | `/dashboard/classrooms` |
| GET | `/{groupId}/teacher-devices` | `/dashboard/classrooms/{groupId}/teacher-devices` |
| POST | `/{groupId}/teacher-devices` | `/dashboard/classrooms/{groupId}/teacher-devices` |
| DELETE | `/teacher-devices/{id}` | `/dashboard/classrooms/teacher-devices/{id}` |
| POST | `/pcs/broken` | `/dashboard/pcs/broken` |

## 3. 주요 DTO와 엔티티 스키마

### 인증/사용자

- `LoginRequest`: `username:string` 필수/최대 50, `password:string` 필수/최대 100.
- `LoginResponse`: `username,token,role`.
- `UserSaveRequestDto`: `username,password,email` 필수, `role?` = `ADMIN|USER|VIEWER`.
- `UserUpdateRequestDto`: `username?,email?,role?`.
- `UserResponseDto`: `id,username,email,role`.

### 에이전트/하트비트

- `AgentRegisterRequest`: `macAddress` 필수 표준 MAC, `hostname` 필수/최대 100, `ipAddress` 필수 IPv4, `osInfo?` 최대 200, `agentVersion?` 최대 40.
- `AgentRegisterResponse`: `agentToken,message`.
- `HeartbeatDto`: `macAddress,hostname,ipAddress,agentSessionId,agentVersion,trafficSamples?,inboundKbps?,outboundKbps?,inboundMbps?,outboundMbps?`. `trafficSamples`는 `[[inKbps,outKbps],...]`이며 있으면 단일값보다 우선한다.
- `HeartbeatOfflineDto`: `macAddress,hostname,ipAddress,agentSessionId,agentVersion,reason`.

### 명령

- `CommandRequestDto`: `scope` 필수 `SINGLE|GROUP`, `role?` = `TEACHER|STUDENT|ALL`, `type` 필수 = `SHUTDOWN|REBOOT|RUN_CMD|RUN_PS1|WOL|PCON|COLLECT_FILES|LIST_FILES|AGENT_UPDATE|REMOTE_CONTROL_START|REMOTE_CONTROL_STOP`, `macAddress?`, `groupId?`, `payloadJson?` 최대 4000, `scriptUrl?` 최대 500, `targetMacs?` 최대 2000개, `broadcastIp?`, `isAdminOperation?`.

### 대시보드 조회

- `ClassroomSummaryDto`: `groupId,groupName,onlinePcs,totalPcs,teacherPcStatus,runningCommandCount,lastHeartbeat,ipRanges,visible,x,y,width,height`.
- `PcStatusDto`: `macAddress,pcName,ipAddress,groupId,groupName,online,lastSeen,status,role,agentVersion,brokenNote`.
- `NetworkTrafficDto`: `inboundMbps,outboundMbps`.
- `DashboardRefreshDto`: `classrooms:ClassroomSummaryDto[]`, `traffic:NetworkTrafficDto`, `texts:object[]`, `images:object[]`.
- `AlertDto`: `id,sourceMac,message,severity,alertType,acknowledged,acknowledgedBy,acknowledgedAt,createdAt`.

### 강의실/배치

- `ClassroomGroup`: `id,name,description,createdAt`.
- `ClassroomIpRange`: `id,groupId,groupName,cidr,startIp,endIp,description`.
- `IpRangeBatchRequest`: `groupId` 필수, `basePrefix?`, `rangeSpec` 필수/최대 1000, `description?`.
- `ClassroomTeacherDevice`: `id,groupId,groupName,ipAddress,macAddress,description`.
- `ClientPosition`: `id,macAddress,ipAddress,groupId,x,y`.

### 레이아웃/위젯

- `DashboardLayout`: `id,name,description,displayOrder,createdAt`.
- `DashboardPanel`: `id,dashboardId,panelType,refGroupId,configJson,x,y,width,height,zIndex,visible,panelName,createdAt,updatedAt`.
- `DashboardPanelUpsertRequest`: `panelType?`(생성 시 서비스에서 필수), `refGroupId?,configJson?,x?,y?,width?,height?,zIndex?,visible?,panelName?`.
- `DashboardPanelBatchPositionRequest`: `{items:[{panelId,x?,y?,width?,height?,zIndex?,visible?}]}`.
- `DashboardLayoutBackup`: `id,dashboardId,name,description,layoutData,createdAt,createdBy,isAutomatic`.
- `DashboardModule`: `id,groupId,type,config,x,y,createdAt`.
- `DashboardWidgetItem`: `id,groupId,dashboardId,type,config,x,y,width,height,visible,createdAt`.

### 보고서

- `MonthlyReport`: `id,yearMonth,totalPcs,avgOnlinePcs,peakOnlinePcs,onlineRatio,totalCommands,failedCommands,totalAlerts,totalTrafficGb,summaryJson,generatedAt`.

### Spring Page 형식

페이지 응답은 Spring Data JSON 형태다. 웹 클라이언트가 의존해야 할 핵심 필드는 다음과 같다.

```json
{
  "content": [],
  "number": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true,
  "empty": true,
  "sort": {}
}
```

현재 JavaFX `ApiClient.getPagedList`는 요청 URL에 `page=`가 없으면 첫 응답의 `totalPages`를 읽고 나머지 페이지를 동시에 모두 요청해 하나의 리스트로 합친다. 웹에서는 대량 데이터 화면에 이 동작을 그대로 복제하지 말고 서버 페이지네이션 또는 가상 스크롤을 사용하는 편이 안전하다.

## 4. STOMP/WebSocket 계약

### 연결

- SockJS endpoint: `/ws`
- JavaFX가 사용하는 raw WebSocket transport URL: `/ws/websocket`
- 브라우저 SockJS 클라이언트는 `/ws`를 사용한다.
- broker destination prefix: `/topic`
- application destination prefix: `/app`
- 서버가 허용하는 WebSocket origin pattern: `*`
- 기본 메시지 제한: 1 MiB
- 기본 send buffer 제한: 2 MiB
- 기본 send timeout: 20초

현재 `@MessageMapping` 핸들러가 없으므로 클라이언트가 보낼 유효한 `/app/**` 메시지는 없다.

### 구독 토픽

| Topic | Payload | JavaFX 동작 |
|---|---|---|
| `/topic/dashboard` | 현재 `{ "eventType": "PC_STATUS" }` | 내용을 사용하지 않고 전체 대시보드 재조회 트리거로 사용 |
| `/topic/alerts` | JSON 문자열: `id,sourceMac,message,severity,alertType,acknowledged,createdAt` | 문자열을 알림 모델로 파싱 |

JavaFX는 STOMP `CONNECT` 헤더에 `Authorization: Bearer <JWT>`를 넣지만, 백엔드에는 이를 검증하는 `ChannelInterceptor`가 없다. HTTP handshake도 `/ws/**`가 `permitAll`이므로 현재 토픽 구독은 인증/역할 검사를 받지 않는다.

연결 실패 시 JavaFX는 30초 후 재연결한다. 웹 구현도 재연결 backoff와 연결 중 중복 연결 방지가 필요하다.

## 5. 현재 JavaFX 클라이언트 전송 동작

- API URL은 기본적으로 `http(s)://host:port/api/v1`, WebSocket은 `ws(s)://host:port/ws/websocket`으로 구성한다. 포트 443일 때만 자동으로 HTTPS/WSS를 선택한다.
- 연결 timeout 5초, 일반 요청 timeout 8초.
- 일반 GET만 네트워크 전송 오류 시 1회 재시도한다. POST/PUT/DELETE는 재시도하지 않는다.
- 모든 `ApiClient` 요청에 로그인 JWT를 Bearer 헤더로 붙인다.
- 청크 업로드 timeout은 청크당 5분, assemble은 2시간이다.
- 파일 다운로드 timeout은 1시간이며 64 KiB 단위로 로컬 디스크에 쓴다.
- 이미지/일반 multipart helper는 파일 전체를 메모리에 읽는다. 스크립트 helper만 8 MiB 청크 스트리밍이다.
- `DashboardLayoutService`는 이름과 달리 서버를 호출하지 않고 로컬 파일 시스템에 백업한다.
- CSV/PDF/config/수집 파일 저장, 인쇄, 클립보드, 폴더 압축, VNC viewer 실행은 JavaFX/Windows 로컬 API에 직접 의존한다.

## 6. 웹 브라우저 호환성 및 정합성 갭

### P0 - 웹 프론트 시작 전에 해결

1. **REST CORS 설정이 없다.** Security 설정은 `.cors(withDefaults())`만 호출하고 `CorsConfigurationSource`, MVC CORS 매핑, `@CrossOrigin`이 없다. 다른 origin의 SPA는 로그인 전 preflight부터 실패할 수 있다. 가장 단순한 운영안은 같은 origin의 reverse proxy다. 분리 origin이면 `Authorization`, `Content-Type`, `Range`, 파일 배포 서명 헤더를 허용하고 `Content-Disposition`, `Content-Length`, `Content-Range`, `Accept-Ranges`, `X-Script-SHA256`를 노출해야 한다.
2. **WebSocket이 인증되지 않는다.** `/ws/**`는 공개이고 STOMP Bearer 헤더를 검증하지 않는다. 현재 누구나 `/topic/dashboard`, `/topic/alerts`를 구독할 수 있다. CONNECT 인증과 SUBSCRIBE destination 권한 검사가 필요하다.
3. **이미지 URL과 JWT가 맞지 않는다.** `/files/{filename}`은 `VIEW` JWT가 필요하지만 JavaFX `Image`와 브라우저 `<img src>`는 Bearer 헤더를 자동 부착하지 않는다. 업로드 응답의 `imageUrl`도 `SERVER_BASE_URL` 기반 절대 URL이라 외부 브라우저에서 내부 호스트를 가리킬 수 있다. same-origin cookie, 짧은 signed URL, 공개 전용 이미지 경로, 또는 `fetch -> Blob URL` 중 하나로 계약을 바꿔야 한다.
4. **파일 배포 HMAC은 브라우저에 둘 수 없다.** 보안 기능을 켜면 쓰기 요청에 shared secret 서명이 필요하지만 현재 JavaFX `ApiClient`도 서명 헤더를 만들지 않는다. 기본값이 `enabled=false`라서만 호출된다. 비밀키를 SPA에 넣지 말고 백엔드가 JWT 사용자 요청을 받아 내부에서 서명하거나 HMAC 계층을 사용자 API에서 제거해야 한다.

### P1 - 인증 및 API 계약 정리

5. **JWT refresh/logout 계약이 없다.** 브라우저 새로고침 후 인증 유지 방식과 만료 복구를 새로 정해야 한다. `localStorage`에 장기 토큰을 두는 방식은 XSS 노출 위험이 있으므로 짧은 access token + HttpOnly refresh cookie/BFF 또는 메모리 토큰 정책을 검토해야 한다. 현재 CSRF가 비활성화되어 있으므로 인증을 쿠키로 옮길 경우 CSRF 방어도 함께 다시 켜야 한다.
6. **401/403 오류 JSON이 일관되지 않다.** 컨트롤러 예외는 `ApiErrorResponse`, 에이전트 인터셉터는 단순 맵, Spring Security 필터 거부는 별도 기본 응답이다. 웹의 공통 에러 처리 전에 인증 entry point/access denied handler를 표준화하는 편이 좋다.
7. **월간 보고서 재생성 권한이 설명과 다르다.** `/reports/monthly/{yearMonth}/regenerate` 설명은 ADMIN 전용이라고 쓰였지만 메서드에 `@PreAuthorize`가 없어 실제로는 `ADMIN`과 `USER` 모두 호출할 수 있다.
8. **중복된 API 계열이 세 개다.** 레이아웃 패널(`/dashboards/**`), 모듈(`/dashboard/modules/**`), 자유 위젯(`/dashboard/widgets|texts|images`)이 공존한다. 현재 JavaFX는 레이아웃 패널과 text/image API를 주로 사용한다. 웹에서는 이 두 계열을 우선 canonical로 정하고 사용하지 않는 모듈/일반 위젯 API를 섞지 않아야 한다.
9. **서버 백업과 로컬 백업 의미가 다르다.** 서버에는 레이아웃 backup/restore API가 있지만 JavaFX는 로컬 JSON 파일만 사용한다. 웹에서는 서버 백업을 채택할지 브라우저 파일 내보내기/가져오기를 유지할지 기능 정의가 필요하다.

### P2 - 브라우저 UX/성능 이관

10. **로컬 OS 기능을 대체해야 한다.** 파일/폴더 선택은 `<input type=file>` 및 File System Access API, CSV/PDF/config 저장은 브라우저 download, 인쇄는 `window.print`, 클립보드는 Clipboard API로 바꿀 수 있다. 사용자 홈 고정 경로와 임의 디렉터리 쓰기는 브라우저에서 불가능하다.
11. **VNC viewer 직접 실행은 일반 웹에서 불가능하다.** 현재 프론트는 로컬 `vncviewer.exe`를 `ProcessBuilder`로 실행하고 로컬 UltraVNC 설정을 수정한다. 웹에서는 전용 URL protocol handler, 설치된 작은 launcher, 또는 noVNC 같은 웹 게이트웨이가 필요하다.
12. **대용량 파일 재개 UI가 없다.** 서버는 Range 다운로드와 resumable 청크 업로드를 지원하지만 JavaFX 다운로드는 Range 재개를 쓰지 않는다. 웹에서는 업로드 상태 영속화, 실패 청크 재전송, 다운로드 재개 여부를 명시해야 한다.
13. **비페이지 목록이 있다.** `/dashboard/pcs`, `/status/all`, `/admin/agents`는 전체 리스트를 반환한다. 현재 약 850대 규모는 가능하지만 웹 표/필터는 가상화하고, 장기적으로 서버 필터/페이지 계약을 추가하는 것이 안전하다.
14. **페이지 크기 상한이 일관되지 않다.** 일부 `page/size` 엔드포인트는 `size`를 상한 없이 `PageRequest`에 전달한다. JavaFX의 전 페이지 병렬 수집까지 복제하면 큰 응답과 순간 요청 폭증이 생길 수 있다.
15. **mixed content 위험이 있다.** JavaFX는 포트가 443이 아니면 HTTP/WS를 자동 선택한다. HTTPS로 서비스되는 웹 페이지에서는 HTTP API와 WS 연결이 브라우저에 의해 차단되므로 배포 URL은 반드시 HTTPS/WSS 또는 같은 origin proxy를 사용해야 한다.

## 7. 웹 프론트 권장 연결 방식

현재 계약을 가장 적게 바꾸는 구성은 다음과 같다.

1. 웹 정적 파일과 API를 동일 HTTPS origin에서 제공한다.
2. reverse proxy가 `/api/v1/** -> backend`, `/ws/** -> backend WebSocket`으로 전달한다.
3. 브라우저 코드는 상대 경로 `/api/v1`과 SockJS `/ws`만 사용한다.
4. REST에는 Bearer JWT를 붙이되 refresh/logout 정책을 먼저 결정한다.
5. STOMP CONNECT에서 JWT를 검증하고 `/topic/**` 구독 권한을 서버에서 강제한다.
6. 보호 이미지와 파일은 인증 가능한 fetch/blob 또는 signed URL로 제공한다.
7. 파일 배포 HMAC 비밀키는 브라우저 밖에 둔다.
