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
| Windows | `C:\ProgramData\PCAgent\config.json` |
| Linux | `~/.pcagent/config.json` |

> 레거시 포터블 실행은 여전히 `%APPDATA%\PCAgent\config.json` 을 읽습니다.

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
| "정상 동작하는 PCAgent 자체 Java 25 런타임을 찾을 수 없습니다" | 번들 JRE/runtime 누락 또는 손상 | 최신 수동 설치 세트로 재설치하여 자체 JRE 복구 |
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

## 7.5. MSI 인스톨러 (권장 신규 배포 방식)

`.bat / .ps1` 의존성을 제거한 **단일 MSI 패키지** 방식. JDK 25 jpackage가 자동 생성.

### 빌드 (관리자 PC, 1회 설정)

```cmd
:: 1. WiX Toolset 3.14 설치 (관리자 PowerShell)
winget install WiXToolset.WiXToolset --silent --accept-package-agreements --accept-source-agreements

:: 2. MSI 빌드
gradlew.bat :agent:jpackageMsi
```

결과: `dist/msi/PCAgent-1.0.0.msi` (≈ 51 MB)

### 배포 (관리자 PC → 사용자 PC)

사용자 PC에서 MSI 더블클릭 → 자동 설치:
- `C:\Program Files\PCAgent\` 에 파일 복사
- 작업 스케줄러 `PCAgent` 등록 (`LocalSystem`, 부팅 시 자동 실행)
- 다음 부팅 시 자동 시작 (즉시 시작도 가능)
- 로그: `C:\ProgramData\PCAgent\agent.log`

### 제거

제어판 → 프로그램 추가/제거 → **PCAgent** → 제거

또는 명령어:
```cmd
msiexec /x PCAgent-1.0.0.msi /qn
```

→ 작업 스케줄러 작업 자동 중지 + 삭제 + 파일 제거.

### MSI 방식의 이점

| 항목 | 기존 .bat + .ps1 | MSI |
|---|---|---|
| 설치 파일 수 | 6개 (jar, ps1×2, bat×2, config) + jre 폴더 | **1개 (.msi)** |
| PowerShell 의존 | `-ExecutionPolicy Bypass` 사용 | 없음 |
| Task Scheduler 사용 | SYSTEM 권한 작업 (AV 의심) | 부팅 시 자동 실행 작업 |
| 자동 재시작 | watchdog.ps1 폴링 | 에이전트 자체 재시도 + 작업 스케줄러 |
| 무인 배포 (GPO/SCCM) | 어려움 | **표준 지원** |
| Defender SmartScreen | 미서명 PowerShell → 차단 가능 | msiexec = Microsoft 플랫폼 |
| 평판 누적 | 매 빌드 새 .bat 해시 | 일관된 MSI ProductCode |

### 작업 스케줄러 관리 (설치 후)

```cmd
:: 상태 확인
schtasks /Query /TN PCAgent /V /FO LIST

:: 수동 실행
schtasks /Run /TN PCAgent

:: 작업 삭제
schtasks /Delete /TN PCAgent /F
```

### 방화벽 / 전원 정책

MSI는 OS 설정을 건드리지 않음. 에이전트가 **첫 실행 시 자체 적용**:
- 방화벽 규칙(`netsh advfirewall firewall add rule`) 자동 등록 (이미 있으면 무시)
- 절전/최대절전 비활성화(`powercfg`) 매 기동 시 재적용

GPO로 사전 푸시한 환경에서는 그냥 두면 됨 (중복 등록 시 netsh가 무시).

---

## 8. 보안 고려사항

| 항목 | 설명 |
|------|------|
| **토큰 저장** | config.json에 평문 저장 → 파일 접근 권한 관리 필요 |
| **서버 통신** | HTTPS 사용 권장 (TLS 1.2+) |
| **명령 실행** | 서버 인증된 명령만 실행 (토큰 검증) |
| **스크립트 검증** | SHA-256 해시 검증 후에만 실행 |
| **최소 권한** | 에이전트 서비스는 필요한 최소 권한으로 실행 |

---

## 9. 안티바이러스 / Defender 화이트리스트 가이드

에이전트는 정상 동작을 위해 보안 SW가 **악성 행위로 오인할 수 있는 패턴**을 다수 사용합니다.
배포 전 IT 담당자가 다음 항목을 안티바이러스 예외에 등록해야 합니다.

### 9.1 행위 기반 탐지 우회 (예외 등록 권장)

| 동작 | 사유 | 안티바이러스 신호 |
|------|------|------|
| SYSTEM 권한 작업 스케줄러 | 사용자 로그아웃 무관 24/7 동작 보장 | Trojan.Persistence |
| AtStartup 트리거 | 부팅 시 자동 시작 | Trojan.AutoStart |
| 12초 주기 외부 HTTPS POST | 서버에 상태 보고 (heartbeat) | C2 Beacon |
| 시스템 정보 수집 (MAC/IP/HW) | PC 식별 + 강의실 매핑 | Reconnaissance |
| TCP 18777 인바운드 listen | P2P 업데이트 캐시 (대역폭 절감) | Backdoor Port |
| 원격 명령 실행 (cmd/PS) | 일괄 종료/WOL/스크립트 배포 | Remote Code Execution |
| 절전/최대절전 비활성 | 모니터링 연속성 | System Tampering |
| 방화벽 규칙 추가 | 통신 보장 | Firewall Bypass |
| `Get-CimInstance Win32_Process` (uninstall) | 잔존 워치독 강제 종료 | Process Enumeration |

### 9.2 Windows Defender 예외 등록 (관리자 명령어)

```powershell
# 폴더 단위 예외
Add-MpPreference -ExclusionPath "C:\Program Files\PCAgent"

# 프로세스 단위 예외
Add-MpPreference -ExclusionProcess "C:\Program Files\PCAgent\jre\bin\java.exe"
Add-MpPreference -ExclusionProcess "C:\Program Files\PCAgent\agent.jar"
```

### 9.3 그룹 정책(GPO) 권장 설정 (대규모 배포)

1. **Defender Application Control**: PCAgent 경로 화이트리스트
2. **AppLocker**: `C:\Program Files\PCAgent\*` 허용 규칙
3. **방화벽 GPO**: 사전 푸시로 install.bat의 firewall 단계 생략 (`--skip-firewall`)
4. **전원 정책 GPO**: 절전 OFF를 사전 적용 (`--skip-power-policy`)

### 9.4 옵트아웃 설치 옵션

설치 시 일부 단계를 건너뛸 수 있습니다:

```cmd
# 전원 정책 변경 건너뜀 (랩탑/배터리 사용 환경)
install.bat --skip-power-policy

# 방화벽 규칙 추가 건너뜀 (GPO로 사전 푸시한 경우)
install.bat --skip-firewall

# 두 가지 동시
install.bat --skip-power-policy --skip-firewall
```

---

## 10. 코드 서명 (선택 — 권장)

서명되지 않은 .jar/.ps1는 Windows SmartScreen의 평판 검사를 통과하지 못해
"게시자 알 수 없음" 경고가 표시됩니다. 코드 서명 인증서로 서명하면:

- Defender 휴리스틱 탐지 점수 ~15% 감소
- SmartScreen "알 수 없는 게시자" 경고 제거
- `-ExecutionPolicy Bypass` 대신 `Signed` 정책 사용 가능

### 10.1 인증서

| 종류 | 비용/년 | 특징 |
|------|------|------|
| Standard Code Signing | $300~500 | 일반 서명 (시간 경과 후 평판 누적) |
| EV (Extended Validation) | $1200~2000 | SmartScreen 즉시 신뢰 (HSM 동글 필요) |

권장 발급처: Sectigo, DigiCert, GlobalSign, SSL.com

### 10.2 JAR 서명 (jarsigner — JDK 포함)

```bash
jarsigner -keystore mycert.p12 -storetype PKCS12 \
    -tsa http://timestamp.sectigo.com/rfc3161 \
    agent-package/agent.jar my-alias
```

### 10.3 PowerShell 스크립트 서명

```powershell
$cert = Get-PfxCertificate -FilePath mycert.p12
foreach ($f in 'agent-watchdog.ps1','install-helper.ps1') {
    Set-AuthenticodeSignature -FilePath "agent-package/$f" `
        -Certificate $cert `
        -TimestampServer http://timestamp.sectigo.com/rfc3161
}
```

### 10.4 빌드 파이프라인 자동화

`agent/build.gradle` 의 `deployPackage` 태스크에 서명 단계를 추가하여
CI/CD에서 자동 서명할 수 있습니다. (옵션, 인증서 보관소 분리 권장)
