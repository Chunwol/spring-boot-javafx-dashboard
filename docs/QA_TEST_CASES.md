# QA 테스트 케이스 (Test Cases)

> **문서 버전**: 1.0  
> **최종 갱신일**: 2026-03-30

---

## TC-AUTH: 인증 테스트 케이스

### TC-AUTH-001: 정상 로그인
- **전제조건**: 유효한 사용자 계정 존재
- **절차**:
  1. `POST /api/v1/auth/login` 호출 (`username`, `password`)
  2. 응답 상태 코드 확인
  3. 응답 본문에서 `token` 필드 확인
- **기대결과**: 200 OK, JWT 토큰 반환
- **우선순위**: P1

### TC-AUTH-002: 잘못된 비밀번호 로그인
- **전제조건**: 유효한 사용자명 존재
- **절차**:
  1. `POST /api/v1/auth/login` 호출 (올바른 `username`, 잘못된 `password`)
- **기대결과**: 401 Unauthorized, 토큰 미반환
- **우선순위**: P1

### TC-AUTH-003: 존재하지 않는 사용자 로그인
- **절차**:
  1. `POST /api/v1/auth/login` 호출 (존재하지 않는 `username`)
- **기대결과**: 401 Unauthorized
- **우선순위**: P1

### TC-AUTH-004: 토큰 없이 보호 API 접근
- **절차**:
  1. Authorization 헤더 없이 `GET /api/v1/groups` 호출
- **기대결과**: 401 Unauthorized
- **우선순위**: P1

### TC-AUTH-005: 만료 토큰으로 API 접근
- **전제조건**: JWT 만료 시간이 경과한 토큰
- **절차**:
  1. 만료된 토큰으로 `GET /api/v1/groups` 호출
- **기대결과**: 401 Unauthorized
- **우선순위**: P1

### TC-AUTH-006: 변조 토큰으로 API 접근
- **절차**:
  1. JWT 페이로드를 임의 변경한 토큰으로 API 호출
- **기대결과**: 401 Unauthorized
- **우선순위**: P1

### TC-AUTH-007: 에이전트 정상 등록
- **전제조건**: 올바른 AGENT_INSTALL_KEY 설정
- **절차**:
  1. `POST /api/v1/agent/register` + `X-Agent-Install-Key` 헤더 + MAC 주소
  2. 응답에서 토큰 확인
- **기대결과**: 200 OK, `token` 필드 반환, agent_auth_tokens에 해시 저장
- **우선순위**: P1

### TC-AUTH-008: 잘못된 설치키로 등록
- **절차**:
  1. `POST /api/v1/agent/register` + 잘못된 `X-Agent-Install-Key`
- **기대결과**: 401 Unauthorized 또는 403 Forbidden
- **우선순위**: P1

### TC-AUTH-009: 폐기된 에이전트 토큰 사용
- **전제조건**: `PUT /api/v1/admin/agents/{mac}/revoke`로 토큰 폐기 완료
- **절차**:
  1. 폐기된 토큰으로 `POST /api/v1/heartbeat` 호출
- **기대결과**: 401/403 응답
- **우선순위**: P1

---

## TC-GRP: 강의실 관리 테스트 케이스

### TC-GRP-001: 강의실 생성
- **절차**:
  1. `POST /api/v1/groups` (name: "테스트실 101", description: "1층")
  2. `GET /api/v1/groups` 로 목록 확인
- **기대결과**: 200 OK, 생성된 강의실이 목록에 포함
- **우선순위**: P1

### TC-GRP-002: 중복 이름 강의실 생성
- **전제조건**: "테스트실 101" 이미 존재
- **절차**:
  1. `POST /api/v1/groups` (name: "테스트실 101")
- **기대결과**: 에러 응답 (중복 이름)
- **우선순위**: P1

### TC-GRP-003: 강의실 수정
- **전제조건**: 강의실 ID=1 존재
- **절차**:
  1. `PUT /api/v1/groups/1` (name: "수정된 이름", description: "수정")
  2. `GET /api/v1/groups` 확인
- **기대결과**: 200 OK, 이름/설명 변경 확인
- **우선순위**: P2

### TC-GRP-004: 강의실 삭제 + 연관 데이터 정리
- **전제조건**: 강의실 ID=1에 IP 대역 2건, 교사 장치 1건, PC 3대 배정
- **절차**:
  1. `DELETE /api/v1/groups/1`
  2. IP 대역 목록에서 해당 강의실 대역 제거 확인
  3. 교사 장치 목록에서 해당 강의실 장치 제거 확인
  4. 배정된 PC의 groupId가 null로 변경 확인
- **기대결과**: 204 No Content, 연관 데이터 모두 정리됨
- **우선순위**: P1

---

## TC-IPR: IP 대역 관리 테스트 케이스

### TC-IPR-001: CIDR 형식 대역 등록
- **절차**:
  1. `POST /api/v1/ip-ranges` (groupId: 1, cidr: "192.168.10.0/24")
  2. 응답 확인
- **기대결과**: 200 OK, 대역 등록 완료
- **우선순위**: P1

### TC-IPR-002: start-end 형식 대역 등록
- **절차**:
  1. `POST /api/v1/ip-ranges` (groupId: 1, startIp: "192.168.10.1", endIp: "192.168.10.50")
- **기대결과**: 200 OK
- **우선순위**: P1

### TC-IPR-003: 겹치는 대역 등록 시 409 Conflict
- **전제조건**: 192.168.10.0/24 이미 등록
- **절차**:
  1. `POST /api/v1/ip-ranges` (groupId: 2, cidr: "192.168.10.0/25")
- **기대결과**: 409 Conflict
- **우선순위**: P1

### TC-IPR-004: 잘못된 CIDR 형식
- **절차**:
  1. `POST /api/v1/ip-ranges` (cidr: "abc.def.ghi/99")
- **기대결과**: 400 Bad Request
- **우선순위**: P2

### TC-IPR-005: 대역 삭제 후 캐시 무효화
- **전제조건**: 192.168.10.0/24 등록, 해당 대역 PC가 강의실 자동 매핑됨
- **절차**:
  1. `DELETE /api/v1/ip-ranges/{id}`
  2. 해당 대역 PC가 다음 하트비트에서 groupId 변경 확인
- **기대결과**: 삭제 성공, 다음 하트비트에서 재매핑 (또는 null)
- **우선순위**: P1

### TC-IPR-006: 일괄 대역 등록
- **절차**:
  1. `POST /api/v1/ip-ranges/batch` (여러 대역 배열)
- **기대결과**: 전체 등록 성공
- **우선순위**: P2

---

## TC-HB: 하트비트 테스트 케이스

### TC-HB-001: 최초 하트비트 → PC 자동 등록
- **절차**:
  1. 새로운 MAC 주소로 `POST /api/v1/heartbeat` 전송
  2. `GET /api/v1/status/{mac}` 으로 PC 확인
- **기대결과**: pc_master에 새 레코드 생성, status=ONLINE
- **우선순위**: P1

### TC-HB-002: IP 매칭 → 강의실 자동 배정
- **전제조건**: 192.168.10.0/24 대역이 강의실 1에 등록
- **절차**:
  1. IP 192.168.10.15로 하트비트 전송
  2. PC의 groupId 확인
- **기대결과**: groupId = 1 (자동 배정)
- **우선순위**: P1

### TC-HB-003: IP 미매칭 → groupId null
- **전제조건**: 10.0.0.0/8 대역 미등록
- **절차**:
  1. IP 10.0.0.100으로 하트비트 전송
- **기대결과**: groupId = null
- **우선순위**: P1

### TC-HB-004: 교사 장치 등록 IP → TEACHER 역할
- **전제조건**: 192.168.10.1이 강의실 1의 교사 장치로 등록
- **절차**:
  1. IP 192.168.10.1로 하트비트 전송
  2. PC의 role 확인
- **기대결과**: role = TEACHER
- **우선순위**: P1

### TC-HB-005: 대기 명령 하트비트 응답 포함
- **전제조건**: MAC AA:BB:CC:DD:EE:FF에 SHUTDOWN 명령 QUEUED 상태
- **절차**:
  1. 해당 MAC으로 하트비트 전송
  2. 응답 본문 확인
- **기대결과**: 응답에 SHUTDOWN 명령 포함, 상태→DELIVERED
- **우선순위**: P1

### TC-HB-006: 5분 미수신 → OFFLINE
- **전제조건**: PC가 ONLINE 상태
- **절차**:
  1. 5분간 하트비트 미전송
  2. 스케줄러 실행 후 상태 확인
- **기대결과**: status = OFFLINE
- **우선순위**: P1

---

## TC-CMD: 명령 시스템 테스트 케이스

### TC-CMD-001: 단일 PC 종료 명령
- **절차**:
  1. `POST /api/v1/commands` (scope: SINGLE, macAddress: "AA:BB:CC:DD:EE:FF", type: SHUTDOWN)
  2. 명령 상태 확인
- **기대결과**: 명령 QUEUED 생성
- **우선순위**: P1

### TC-CMD-002: 강의실 전체 명령
- **전제조건**: 강의실 1에 PC 5대 배정
- **절차**:
  1. `POST /api/v1/commands` (scope: GROUP, groupId: 1, role: ALL, type: REBOOT)
  2. command_queue 조회
- **기대결과**: 5건 명령 QUEUED 생성
- **우선순위**: P1

### TC-CMD-003: 명령 라이프사이클 전체
- **절차**:
  1. 명령 발행 → QUEUED 확인
  2. 에이전트 하트비트 → DELIVERED 확인
  3. `POST /agent/commands/{taskId}/ack` → ACKED 확인
  4. `POST /agent/commands/{taskId}/result` (exitCode: 0) → COMPLETED 확인
- **기대결과**: 각 단계에서 상태 정상 전환
- **우선순위**: P1

### TC-CMD-004: WOL 릴레이 명령
- **전제조건**: 강의실 1에 온라인 PC 1대, 오프라인 PC 2대
- **절차**:
  1. `POST /api/v1/admin/group/wol` (groupId: 1)
  2. 온라인 PC의 command_queue 확인
- **기대결과**: 온라인 PC에 WOL_RELAY 명령 생성 (targetMacs에 오프라인 PC MAC 포함)
- **우선순위**: P1

### TC-CMD-005: 타임아웃 재큐잉
- **전제조건**: 명령 DELIVERED 상태 180초 경과
- **절차**:
  1. 명령 타임아웃 스케줄러 실행 대기
  2. 명령 상태 확인
- **기대결과**: 상태 QUEUED로 재설정, retryCount +1
- **우선순위**: P2

### TC-CMD-006: 최대 재시도 초과
- **전제조건**: retryCount = 3인 명령
- **절차**:
  1. 다시 타임아웃 발생
- **기대결과**: 재큐잉 안 함 (FAILED 또는 현 상태 유지)
- **우선순위**: P2

---

## TC-SCR: 스크립트 테스트 케이스

### TC-SCR-001: 스크립트 업로드 + SHA-256
- **절차**:
  1. `POST /api/v1/scripts` (multipart, test.ps1 파일)
  2. 응답의 sha256 필드 확인
  3. 로컬에서 계산한 SHA-256과 비교
- **기대결과**: SHA-256 일치
- **우선순위**: P1

### TC-SCR-002: 스크립트 다운로드 + 해시 헤더
- **전제조건**: test.ps1 업로드 완료 (ID=1)
- **절차**:
  1. `GET /api/v1/scripts/1/download`
  2. 응답 헤더 `X-Script-SHA256` 확인
  3. 다운로드 파일 내용이 원본과 일치 확인
- **기대결과**: 헤더 포함, 파일 내용 일치
- **우선순위**: P1

### TC-SCR-003: Path Traversal 방어
- **절차**:
  1. 파일명에 `../../etc/passwd` 포함된 요청
- **기대결과**: 요청 거부 (400 또는 403)
- **우선순위**: P1

---

## TC-RPT: 보고서 테스트 케이스

### TC-RPT-001: PDF 보고서 생성
- **전제조건**: 최근 7일간 트래픽 데이터 존재
- **절차**:
  1. `GET /api/v1/reports/pdf?startDate=2026-03-23&endDate=2026-03-30`
  2. 응답 Content-Type 확인
  3. PDF 파일 열기
- **기대결과**: `application/pdf`, 유효한 PDF (요약, 가동률, 트래픽 표 포함)
- **우선순위**: P2

### TC-RPT-002: 트래픽 리포트
- **절차**:
  1. `GET /api/v1/reports/traffic?startDate=2026-03-23&endDate=2026-03-30`
- **기대결과**: JSON 배열 (일별 인바운드/아웃바운드 집계)
- **우선순위**: P2

---

## TC-SEC: 보안 테스트 케이스

### TC-SEC-001: SQL Injection 방어
- **절차**:
  1. `POST /api/v1/groups` (name: `"'; DROP TABLE users; --"`)
  2. DB 정상 동작 확인
- **기대결과**: SQL 실행 안 됨, JPA 파라미터 바인딩으로 안전 처리
- **우선순위**: P1

### TC-SEC-002: XSS 방어
- **절차**:
  1. 강의실 이름에 `<script>alert('xss')</script>` 입력
  2. API 응답에서 스크립트 태그 이스케이프 확인
- **기대결과**: 스크립트 실행 불가
- **우선순위**: P1

### TC-SEC-003: 에이전트 토큰 해시 저장 확인
- **절차**:
  1. 에이전트 등록 후 `agent_auth_tokens` 테이블 확인
  2. `token_hash` 컬럼이 SHA-256 해시인지 확인
- **기대결과**: 평문 토큰 미저장, 해시만 저장
- **우선순위**: P1

### TC-SEC-004: 설치키 타이밍 어택 방어
- **절차**:
  1. 올바른 설치키와 잘못된 설치키의 응답 시간 비교 (여러 회 측정)
- **기대결과**: 응답 시간 차이 유의미하지 않음 (MessageDigest.isEqual 사용)
- **우선순위**: P2

### TC-SEC-005: 파일 업로드 크기 제한
- **절차**:
  1. 25MB 파일 업로드 시도 (`POST /api/v1/scripts`)
- **기대결과**: 업로드 거부 (최대 20MB)
- **우선순위**: P2

---

## TC-E2E: E2E 시나리오 테스트

### TC-E2E-001: 에이전트 신규 설치 전체 흐름
1. 에이전트 JAR + config.json (serverUrl, installKey만 설정) 배포
2. 에이전트 시작 → 자동 등록 확인
3. 하트비트 시작 → 서버에 PC 등록 확인
4. 프론트엔드 대시보드에서 PC 온라인 표시 확인
5. 관리 탭에서 에이전트 목록에 표시 확인

### TC-E2E-002: 강의실 설정 + 자동 매핑
1. 프론트엔드에서 강의실 "실습실 A" 생성
2. IP 대역 192.168.10.0/24 등록
3. 해당 대역 에이전트 하트비트 전송
4. 대시보드에서 "실습실 A" 패널에 PC 표시 확인
5. 교사 PC (192.168.10.1) 등록
6. 다음 하트비트 후 교사 PC 아이콘/라벨 확인

### TC-E2E-003: 원격 명령 실행
1. PC 관리 탭에서 특정 PC 선택
2. "명령 실행" 선택 → 명령어 입력 (예: `hostname`)
3. 명령 상태 탭에서 QUEUED → DELIVERED → ACKED → COMPLETED 추적
4. 결과(stdout: 호스트명) 확인

### TC-E2E-004: WOL 그룹 실행
1. 강의실에 온라인 PC 1대, 오프라인 PC 2대 설정
2. 강의실 패널 "전체 켜기(WOL)" 클릭
3. 온라인 에이전트 로그에서 WOL_RELAY 수신 확인
4. 매직패킷 전송 로그 확인

### TC-E2E-005: 스크립트 배포 + 실행
1. 관리 화면에서 PS1 스크립트 업로드
2. PC 관리 탭에서 대상 PC 선택 → "스크립트 실행"
3. 에이전트에서 스크립트 다운로드 + SHA-256 검증 확인
4. 실행 결과 (stdout/stderr) 서버 보고 확인
5. 명령 상태 패널에서 결과 확인
