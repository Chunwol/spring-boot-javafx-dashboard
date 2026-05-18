# PC 에이전트 설치·운영 가이드

> **통합 강의실 PC 관제·원격제어 시스템 — 에이전트**  
> **문서 버전**: 1.0 | **최종 갱신일**: 2026-03-30

---

## 1. 개요

PC 에이전트는 각 강의실 PC에 설치되어 다음 역할을 수행합니다:

- **하트비트 전송**: 120초 주기로 서버에 PC 상태(IP, MAC, 트래픽) 보고
- **원격 명령 실행**: 서버가 전달한 종료/재부팅/명령/스크립트 실행
- **WOL 릴레이**: 오프라인 PC를 대신 깨우는 매직패킷 전송

### 시스템 요구사항
| 항목 | 요구사항 |
|------|---------|
| OS | Windows 10/11 |
| Java | 17 이상 |
| 네트워크 | 서버(포트 443 또는 8888)에 접근 가능 |
| 권한 | 관리자 권한 (원격 명령 실행, WOL) |

---

## 2. 설치

### 2.1 필요 파일

| 파일 | 설명 | 위치 |
|------|------|------|
| `agent-1.0.0.jar` | 에이전트 실행 파일 | `agent/build/libs/` |
| `config.json` | 서버 연결 설정 | 직접 작성 또는 프론트엔드에서 다운로드 |

### 2.2 config.json 작성

```json
{
  "serverUrl": "https://서버주소",
  "installKey": "서버에-설정된-설치키"
}
```

| 필드 | 설명 | 예시 |
|------|------|------|
| `serverUrl` | 백엔드 서버 URL | `https://pelmon.kro.kr` |
| `installKey` | 에이전트 설치 인증키 | `INST-xxxxxxxx` |

> **설치키 확인 방법**: 프론트엔드 → 탭 4 (관리) → 에이전트 설치 관리 → "설치키 조회"

### 2.3 설치 절차

#### 방법 1: 수동 설치

```powershell
# 1. 설치 디렉터리 생성
mkdir C:\PCAgent

# 2. 파일 복사
copy agent-1.0.0.jar C:\PCAgent\
copy config.json C:\PCAgent\

# 3. 동작 테스트 (콘솔에서 실행)
cd C:\PCAgent
java -jar agent-1.0.0.jar
# → "등록 성공", "하트비트 전송" 메시지 확인 후 Ctrl+C

# 4. Windows 서비스 등록 (관리자 권한)
.\scripts\install.bat
```

#### 방법 2: 스크립트 설치

```powershell
# 관리자 PowerShell에서 실행
.\scripts\install.bat
```

install.bat이 수행하는 작업:
1. `C:\PCAgent` 디렉터리 생성
2. JAR + config.json 복사
3. Windows 서비스 등록 (자동 시작)
4. 서비스 시작

#### 방법 3: 프론트엔드에서 config.json 다운로드

1. 프론트엔드 → 탭 4 (관리) → 에이전트 설치 관리
2. "config.json 다운로드" 클릭
3. 다운로드된 파일과 `agent-1.0.0.jar`을 PC에 복사

### 2.4 설치 확인

설치 완료 후 다음을 확인합니다:

1. **에이전트 로그**: 콘솔 또는 서비스 로그에서 "등록 성공" 메시지 확인
2. **프론트엔드 확인**: 관리 탭 → 에이전트 목록에서 해당 PC 표시 확인
3. **대시보드 확인**: 메인 탭에서 강의실 패널의 온라인 PC 수 증가 확인

---

### 2.5 서비스 설치(옵션): NSSM 사용 권장

Windows 환경에서는 작업 스케줄러 + PowerShell 워치독 대신 NSSM(Non-Sucking Service Manager)을 사용해 에이전트를 서비스로 등록하는 것을 권장합니다. NSSM은 실행 명령과 인수, 재시작 정책 등을 간편히 설정할 수 있어 운영·모니터링이 쉽습니다.

간단 사용법:

1. `nssm.exe`를 배포 패키지 또는 `scripts/`에 넣습니다.
2. 관리자 권한으로 설치 스크립트를 `--use-nssm` 플래그와 함께 실행합니다:

```powershell
.\scripts\install.bat --use-nssm
```

설치 스크립트는 자동으로 `java -jar` 실행 인자를 설정하여 서비스로 등록하고, 서비스 자동 시작을 설정합니다. 시스템 레벨에서 서비스로 관리하면 작업 스케줄러 + 워치독 조합보다 복구·관리성이 개선됩니다.

참고: NSSM 바이너리는 별도 배포물이므로 라이선스를 확인한 뒤 배포 패키지에 포함하세요.


## 3. 설정 상세

### 3.1 config.json 전체 옵션

```json
{
  "serverUrl": "https://pelmon.kro.kr",
  "installKey": "INST-xxxxxxxx",
  "agentToken": "",
  "heartbeatIntervalSec": 120,
  "jitterSec": 30
}
```

| 필드 | 기본값 | 설명 |
|------|--------|------|
| `serverUrl` | `http://localhost:8888` | 백엔드 서버 URL |
| `installKey` | _(빈 문자열)_ | 에이전트 설치 인증키 |
| `agentToken` | _(빈 문자열)_ | 발급된 인증 토큰 (자동 저장됨) |
| `heartbeatIntervalSec` | 120 | 하트비트 전송 간격 (초) |
| `jitterSec` | 30 | 하트비트 지터 (0~jitter 범위 랜덤 추가) |

> **자동 저장**: 최초 등록 성공 시 서버가 발급한 `agentToken`이 자동으로 config.json에 저장됩니다.

### 3.2 환경변수 오버라이드

config.json 대신 환경변수로 설정할 수 있습니다:

| 환경변수 | 대응 필드 |
|---------|----------|
| `AGENT_SERVER_URL` | serverUrl |
| `AGENT_INSTALL_KEY` | installKey |
| `AGENT_TOKEN` | agentToken |
| `AGENT_HEARTBEAT_INTERVAL_SEC` | heartbeatIntervalSec |
| `AGENT_JITTER_SEC` | jitterSec |

환경변수가 설정되면 config.json 값보다 우선합니다.

### 3.3 설정 파일 위치

| OS | 경로 |
|----|------|
| Windows | `%APPDATA%\PCAgent\config.json` |
| Linux | `~/.pcagent/config.json` |

---

## 4. 동작 원리

### 4.1 자동 등록 흐름

```
에이전트 시작
    │
    ├── agentToken 있음? → 하트비트 루프 시작
    │
    └── agentToken 없음?
         │
         ▼
    POST /api/v1/agent/register
    + X-Agent-Install-Key 헤더
         │
         ├── 성공 → 토큰 저장 → 하트비트 루프 시작
         │
         └── 실패 → 30초 대기 → 재시도 (최대 3회)
                                    │
                                    └── 3회 실패 → 종료 (exit code 2)
```

### 4.2 하트비트

- **주기**: `heartbeatIntervalSec` ± `jitterSec` (기본 90~150초)
- **인증**: `X-Agent-Token` 헤더
- **전송 데이터**: MAC 주소, IP 주소, 호스트명, 에이전트 버전, 인바운드/아웃바운드 트래픽(Kbps)
- **응답 처리**: 서버가 대기 명령을 응답에 포함하면 즉시 실행

### 4.3 명령 실행

| 명령 | 실행 방법 | 타임아웃 |
|------|----------|----------|
| `SHUTDOWN` | `shutdown /s /t 10 /c "원격 종료 명령"` | 30초 |
| `REBOOT` | `shutdown /r /t 10` | 30초 |
| `RUN_CMD` | `cmd.exe /c <payload.cmd>` | 30초 |
| `RUN_PS1` | 스크립트 다운로드 → SHA-256 검증 → `powershell.exe -ExecutionPolicy Bypass -File` | 5분 |
| `WOL_RELAY` | 대상 MAC에 UDP 매직패킷 전송 (포트 9) | 즉시 |

#### 명령 실행 흐름
```
하트비트 응답에서 명령 수신
    │
    ▼
ACK 전송 (POST /agent/commands/{taskId}/ack)
    │
    ▼
별도 스레드에서 명령 실행
    │
    ▼
결과 보고 (POST /agent/commands/{taskId}/result)
    ├── exitCode: 종료 코드
    ├── stdout: 표준 출력 (최대 4KB)
    └── stderr: 표준 에러 (최대 4KB)
```

### 4.4 스크립트 실행 상세

1. 서버에서 스크립트 다운로드: `GET /api/v1/scripts/{id}/download`
2. 응답 헤더 `X-Script-SHA256`과 다운로드 파일의 SHA-256 비교
3. **해시 불일치 시 실행 거부** (보안)
4. 임시 파일로 저장 → PowerShell 실행
5. 실행 완료 후 임시 파일 자동 삭제

### 4.5 WOL 릴레이

서버로부터 `WOL_RELAY` 명령을 받으면:
1. payload에서 대상 MAC 주소 목록 + broadcastIP 추출
2. 각 MAC에 대해 매직패킷(6×0xFF + 16×MAC) 생성
3. UDP 포트 9로 브로드캐스트 전송

---

## 5. 트러블슈팅

### 5.1 에이전트가 시작되지 않음

| 증상 | 원인 | 해결 |
|------|------|------|
| "Java를 찾을 수 없습니다" | Java 미설치 또는 PATH 미등록 | JDK 25+ 설치 후 환경변수 설정 |
| "config.json 찾을 수 없음" | 설정 파일 누락 | config.json 생성 (2.2절 참조) |
| exit code 2 | 등록 3회 실패 | config.json의 serverUrl/installKey 확인 |

### 5.2 등록 실패

| 증상 | 원인 | 해결 |
|------|------|------|
| 401 Unauthorized | 설치키 불일치 | installKey 확인 (프론트엔드에서 재조회) |
| Connection refused | 서버 미실행 또는 네트워크 차단 | 서버 상태 및 방화벽 확인 |
| SSL 에러 | 인증서 문제 | serverUrl에 올바른 HTTPS URL 설정 |

### 5.3 하트비트 문제

| 증상 | 원인 | 해결 |
|------|------|------|
| 401/403 반복 | 토큰 폐기됨 | config.json에서 agentToken 삭제 후 재시작 |
| 주기적 타임아웃 | 네트워크 불안정 | 네트워크 연결 확인, jitterSec 증가 |
| 잘못된 MAC 표시 | 가상 NIC 선택됨 | 주 NIC를 물리 어댑터로 설정 |

### 5.4 명령 실행 실패

| 증상 | 원인 | 해결 |
|------|------|------|
| "Access Denied" | 관리자 권한 부족 | 에이전트 서비스를 관리자 권한으로 실행 |
| PS1 해시 불일치 | 파일 손상/변조 | 스크립트 재업로드 |
| 5분 타임아웃 | 스크립트 실행 시간 초과 | 스크립트 최적화 또는 분할 |

---

## 6. 제거

### 6.1 서비스 제거

```powershell
# 관리자 PowerShell에서 실행
.\scripts\uninstall.bat
```

uninstall.bat이 수행하는 작업:
1. Windows 서비스 중지
2. 서비스 등록 해제
3. 설치 파일 삭제 (선택)

### 6.2 수동 제거

```powershell
# 서비스 중지
Stop-Service PCAgent -Force

# 서비스 삭제
sc.exe delete PCAgent

# 파일 삭제
Remove-Item -Recurse -Force C:\PCAgent
```

### 6.3 서버에서 에이전트 삭제

프론트엔드 → 탭 4 (관리) → 에이전트 목록 → 해당 에이전트 삭제 버튼

---

## 7. 하드웨어 수집 정보

에이전트가 수집하는 정보:

| 항목 | 수집 방법 | 용도 |
|------|----------|------|
| MAC 주소 | OSHI (물리 NIC) | PC 고유 식별자 |
| IP 주소 | OSHI | 강의실 자동 매핑 |
| 호스트명 | OSHI | PC 이름 표시 |
| 인바운드 트래픽(Kbps) | OSHI (바이트 델타 → Kbps) | 트래픽 모니터링 |
| 아웃바운드 트래픽(Kbps) | OSHI (바이트 델타 → Kbps) | 트래픽 모니터링 |
| 에이전트 버전 | 빌드 정보 | 버전 관리 |

> **NIC 선택 기준**: 루프백 제외, 첫 번째 물리 NIC 자동 선택

---

## 8. 보안 고려사항

| 항목 | 설명 |
|------|------|
| **토큰 저장** | config.json에 평문 저장 → 파일 접근 권한 관리 필요 |
| **서버 통신** | HTTPS 사용 권장 (TLS 1.2+) |
| **명령 실행** | 서버 인증된 명령만 실행 (토큰 검증) |
| **스크립트 검증** | SHA-256 해시 검증 후에만 실행 |
| **최소 권한** | 에이전트 서비스는 필요한 최소 권한으로 실행 |
