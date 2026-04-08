# 800대 대량 안정배포 아키텍처 설계서 (무타협)

본 문서는 30GB급 대용량 파일을 800대 PC에 안정적으로 배포하기 위한 목표 아키텍처를 정의한다.
기존 명령 전송 기능의 확장이 아니라, 운영 가능한 배포 시스템(Distribution System)으로 설계한다.

---

## 1. 목표와 원칙

### 1.1 목표
- 단일/다중 대상 모두 동일한 배포 파이프라인으로 처리
- 30GB 단일 파일 및 다중 파일 묶음(패키지) 배포 지원
- 800대 동시 운영 환경에서 실패 내성, 재시도, 가시성, 감사 추적 보장
- 지정된 허용 경로(allowlist)만 파일 쓰기 허용

### 1.2 비목표
- 중앙 서버에서 800대로 직접 동시 푸시(비효율 구조)
- 단순 파일 다운로드 링크 제공만으로 운영 완료 처리

### 1.3 설계 원칙
- 무결성 우선: 전송 성공보다 해시 일치가 우선
- 재개 가능성: 네트워크 중단 후 중간 지점부터 복구
- 웨이브 제어: 대량 배포는 단계적 확산
- 관측 가능성: 모든 배포 단계를 이벤트로 기록
- 제로 트러스트: 경로/권한/서명 검증 기본

---

## 2. 요구사항 및 SLO

### 2.1 기능 요구사항
- 파일 업로드, 버전 관리, 메타데이터 관리
- 배포 Job 생성(대상군, 웨이브, 대역폭 정책, 시간창)
- 에이전트 다운로드/검증/원자적 반영(atomic move)
- 실패 재시도, 부분 실패 격리, 롤백 실행

### 2.2 비기능 요구사항
- RPO: 0 (배포 로그 및 상태 손실 없음)
- RTO: 30분 이내 배포 오케스트레이터 복구
- 데이터 무결성: SHA-256 필수, 옵션으로 SHA-512
- 보안: 서명 검증(권장), TLS 1.2+

### 2.3 운영 SLO
- 대규모 배포 성공률: 99.5% 이상 (자동 재시도 포함)
- 에이전트 배포 상태 보고 지연: p95 15초 이하
- 오케스트레이터 API 가용성: 월 99.9% 이상

---

## 3. 현실 용량 계산 (기준)

- 30GB x 800대 = 24TB 총 전송량
- 중앙 서버 1Gbps 단일 링크의 이론 최대 처리량은 약 125MB/s
- 중앙 직배 방식은 장시간 점유 + 병목 + 실패 파급이 커 운영 리스크가 높다

결론:
- 중앙 직접 배포는 금지
- 오브젝트 스토리지 + 캐시/미러 + 웨이브 + 피어 분산 구조를 사용

---

## 4. 권장 아키텍처 (Best Practice)

### 4.1 구성 요소
1. Distribution Control Plane (백엔드)
- Job/웨이브/상태 오케스트레이션
- 정책 검증(경로, 시간창, 대상군)

2. Artifact Store (S3 호환 권장)
- 원본/청크/메타데이터 저장
- 멀티파트 업로드

3. Regional Cache/Relay (선택이 아니라 권장)
- 건물/서브넷 단위 미러 노드
- 원본 재다운로드 폭증 방지

4. Agent Downloader
- 청크 병렬 다운로드
- resume, checksum, bandwidth shaping

5. Telemetry Pipeline
- 이벤트 수집(큐) + 시계열 저장 + 대시보드

### 4.2 데이터 흐름
1. 관리자 업로드 -> 서버가 파일 청크화 및 manifest 생성
2. Job 생성 -> 대상군/웨이브/정책 확정
3. Wave 0 (카나리 1~3%)
4. Wave 1 (10%)
5. Wave 2 (30%)
6. Wave 3 (잔여)
7. 각 에이전트는 가장 가까운 캐시/릴레이에서 청크 수신
8. 전체 파일 해시 검증 후 staging -> 원자적 이동
9. 성공/실패 이벤트 집계, 실패군만 재시도

---

## 5. 전송 프로토콜 설계

### 5.1 파일 포맷
- 파일 단위를 배포하지 말고 package manifest 기준으로 배포
- manifest 항목:
  - packageId, version, totalSize
  - chunkSize (권장 16MB~64MB)
  - chunks[]: index, offset, size, sha256
  - fileHashSha256, optionalSignature

### 5.2 청크 전략
- 기본 청크: 32MB
- 에이전트 병렬도: 4~8 (환경별 정책)
- 재시도: 지수 백오프 + 지터
- 실패 임계치 초과 시 웨이브 자동 중단

### 5.3 재개(Resume)
- 로컬 상태 파일에 완료 chunk index bitmap 기록
- 재시작 시 미완료 chunk만 요청
- 완성 후 전체 해시 재검증

### 5.4 원자적 반영
- target.tmp에 완성 -> hash verify -> target.final rename
- 반영 중 전원/프로세스 중단 시 tmp 재사용 가능

---

## 6. 보안 설계 (필수)

### 6.1 경로 제한
- 허용 폴더 목록만 배포 가능
- 상대경로/상위경로(..) 금지
- 시스템 중요 경로 기본 차단

### 6.2 인증/인가
- 배포 API: 관리자 RBAC + 2인 승인(대량 배포)
- 에이전트 다운로드 URL: 단기 서명 URL(예: 10~30분)

### 6.3 무결성/위변조 방지
- chunk hash + file hash 이중 검증
- 권장: 패키지 서명(Ed25519 또는 RSA-PSS)

### 6.4 감사 추적
- 누가/언제/무엇을/어디로 배포했는지 immutable 로그
- 보존기간: 최소 1년 권장

---

## 7. 스케줄링/오케스트레이션

### 7.1 웨이브 정책
- 기본: 1% -> 10% -> 30% -> 59%
- 각 웨이브 종료 기준:
  - 성공률 임계치 충족
  - 치명 오류율 임계치 이하

### 7.2 동시성 제한
- 전체 동시 다운로드 제한
- 서브넷/강의실별 토큰 버킷 제한
- 근무시간/야간 시간창 정책

### 7.3 자동 중단/자동 롤백
- 해시 불일치, 디스크 부족, 반복 실패군 급증 시 자동 중단
- 이미 반영된 대상군에는 rollback package 배포

---

## 8. 상태 모델 (권장)

### 8.1 Job 상태
- CREATED
- VALIDATING
- STAGING
- DISTRIBUTING
- PAUSED
- FAILED
- COMPLETED
- ROLLING_BACK
- ROLLED_BACK

### 8.2 Agent 상태
- QUEUED
- DOWNLOADING
- VERIFYING
- STAGED
- APPLYING
- SUCCEEDED
- FAILED_RETRYABLE
- FAILED_FATAL

---

## 9. DB 스키마 (초안)

1. distribution_package
- id, version, size_bytes, sha256, signature, created_at

2. distribution_chunk
- package_id, chunk_index, offset, size, sha256

3. distribution_job
- id, package_id, target_selector, policy_json, status, created_by, created_at

4. distribution_wave
- id, job_id, wave_no, target_count, started_at, ended_at, status

5. distribution_target_state
- job_id, agent_id, wave_no, status, progress_pct, bytes_downloaded, error_code, updated_at

6. distribution_event
- id, job_id, agent_id, type, payload_json, occurred_at

---

## 10. 관측/운영 대시보드

필수 지표:
- wave별 성공률/실패율
- 평균 다운로드 속도, p95
- chunk 재시도율
- 해시 실패 건수
- 에이전트 디스크 부족 건수
- 서브넷별 병목

필수 알람:
- 해시 실패율 임계치 초과
- retry 폭증
- 특정 릴레이 노드 장애
- 분당 이벤트 수 급감(텔레메트리 파이프라인 장애 신호)

---

## 11. 신뢰성/복구 전략

### 11.1 단일 장애점 제거
- 오케스트레이터 이중화
- 스토리지 버전드 버킷 + 라이프사이클
- 메시지 큐(이벤트 내구성)

### 11.2 DR
- 메타데이터 DB 주기 백업
- 스토리지 cross-region 복제(선택)
- Job 재시작 시 마지막 wave/agent 상태에서 복구

---

## 12. 구현 로드맵 (권장)

### Phase A (4~6주) - 기반
- 패키지/청크/manifest
- 단일 대상 안정 다운로드 + resume + hash verify
- 허용 경로 정책

### Phase B (4~6주) - 대량화
- Job/Wave 오케스트레이션
- 그룹 배포, 동시성 제어
- 이벤트 파이프라인 + 운영 대시보드

### Phase C (6~10주) - 800대 운영급
- 릴레이/캐시 노드
- 자동 중단/롤백
- 카나리 정책 자동화
- 대규모 장시간 soak test

---

## 13. 운영 자동화(실무 적용)

현재 구현 기준으로 운영자가 즉시 사용할 수 있는 자동화 항목:

1. 실패 대상 재전송
- API: `POST /api/v1/file-distribution/jobs/{jobId}/retry-failed`
- 옵션: 특정 wave만 재전송 가능
- 동작: `FAILED` 대상만 `QUEUED`로 리셋 후 해당 wave 재시작

2. 롤백 배포 생성
- API: `POST /api/v1/file-distribution/jobs/{jobId}/rollback`
- 입력: `rollbackFromJobId`, `waveSize`, `canaryOnly`, `groupId`
- 동작: 안정 Job 아티팩트 기준으로 새 롤백 Job 생성 후 배포

3. 대시보드 탭 기반 운영
- 최근 Job 목록, 자동 갱신(5초), 상태 배너 알림
- Job 취소, 실패 재전송, 롤백 실행 버튼 제공

4. 운영 스크립트
- `scripts/smoke-test-file-distribution.ps1`
  : 최근 Job 조회/상세 조회 + 실패 재전송/롤백 호출(옵션)
- `scripts/dr-file-distribution-check.ps1`
  : DR 점검용 Job 상태 스냅샷 저장

권장 운영 절차:
1. 실패 Job 감지 -> 실패 재전송 실행
2. 실패율 지속 증가 시 -> 롤백 배포 생성
3. DR 점검 스크립트 주기 실행(일/주 단위)

---

## 13. 성능/부하 테스트 기준

### 13.1 테스트 시나리오
1. 5GB 파일 200대
2. 30GB 파일 200대
3. 30GB 파일 800대 (웨이브)
4. 네트워크 손실 3%, 지연 50ms 조건
5. 릴레이 노드 1개 장애 주입

### 13.2 합격 기준
- 해시 불일치 0건
- 자동 재개 성공률 99%+
- 웨이브 중단/재개 정상 동작
- 최종 성공률 99.5%+

---

## 14. 운영 정책 템플릿

- 최대 동시 다운로드(전체): 120
- 강의실당 동시 다운로드: 8
- 기본 청크: 32MB
- 에이전트 병렬 청크: 6
- 재시도: 최대 8회, 지수 백오프
- 야간 배포 창: 22:00~06:00

---

## 15. 리스크와 대응

1. 중앙 회선 포화
- 대응: 웨이브 + 릴레이 + 대역폭 제한

2. 디스크 부족/권한 실패
- 대응: 사전 용량 검사 + 정책 차단 + 사유 코드화

3. 파일 변조/손상
- 대응: hash + signature + immutable audit

4. 에이전트 버전 파편화
- 대응: 최소 버전 게이트 + 사전 에이전트 롤아웃

---

## 16. 최종 권고

- 800대/30GB 급은 파일 전송 기능이 아니라 배포 플랫폼으로 접근해야 한다.
- 1차 목표는 전송 성공이 아니라 검증된 반영 성공으로 정의해야 한다.
- 무타협 운영을 목표로 한다면, 릴레이/웨이브/무결성/관측 체계를 반드시 포함해야 한다.

---

## 17. 즉시 실행 액션 (다음 스프린트)

1. 배포 도메인 모델/DB 스키마 확정
2. 청크/manifest 규격 고정
3. 에이전트 resume 다운로드 PoC
4. 허용 경로 정책 엔진 구현
5. Job/Wave API 최소 버전 구축
6. 50대 파일럿 환경에서 장시간 검증
