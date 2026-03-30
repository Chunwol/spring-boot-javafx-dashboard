# 관리자 운영 가이드

> **통합 강의실 PC 관제·원격제어 시스템**  
> **문서 버전**: 1.0 | **최종 갱신일**: 2026-03-30

---

## 1. 시스템 아키텍처 개요

```
사용자 (JavaFX)
      │
      ▼
Nginx (443 HTTPS)  ←──  ZeroSSL 인증서
      │
      ▼
Backend (8888)
      │
      ├── REST API (/api/v1/*)
      ├── WebSocket (/ws)
      └── SQLite DB (db/mydatabase.db)
      │
      ▼
PC 에이전트 (N대)
      └── 하트비트 (120초), 명령 실행, WOL 릴레이
```

---

## 2. 서버 운영

### 2.1 백엔드 서버 시작

#### 개발 환경 (직접 실행)
```bash
# Windows
.\gradlew.bat :backend:bootRun

# Linux/Mac
./gradlew :backend:bootRun
```

#### 프로덕션 환경 (Docker)
```bash
# 전체 스택 시작
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f backend
```

### 2.2 주요 환경 변수

| 변수 | 기본값 | 설명 | 변경 필요 여부 |
|------|--------|------|-------------|
| `SERVER_PORT` | 8888 | 서버 포트 | 보통 유지 |
| `JWT_SECRET` | _(기본값)_ | JWT 서명키 | **⚠️ 반드시 변경** |
| `AGENT_INSTALL_KEY` | `INST-CHANGE-ME-BEFORE-DEPLOY` | 에이전트 설치키 | **⚠️ 반드시 변경** |
| `DB_URL` | `jdbc:sqlite:db/mydatabase.db` | DB 연결 | 환경에 맞게 |
| `HEARTBEAT_TIMEOUT_MINUTES` | 5 | 오프라인 판정 시간(분) | 환경에 맞게 |
| `TRAFFIC_ALERT_THRESHOLD_MBPS` | 100 | 트래픽 경보 임계치(Mbps) | 환경에 맞게 |
| `ALERT_EMAIL` | _(빈 문자열)_ | 경보 이메일 주소 | 필요시 설정 |
| `LOG_LEVEL` | INFO | 로깅 레벨 | DEBUG(문제 해결 시) |

> `.env` 파일에서 관리합니다. 상세 가이드: [ENV_GUIDE.md](../ENV_GUIDE.md)

### 2.3 헬스 체크

```bash
# 서버 상태 확인
curl https://서버주소/health

# API 접근 확인
curl https://서버주소/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"비밀번호"}'
```

### 2.4 데이터베이스 관리

#### 백업
```bash
# SQLite DB 파일 복사 (서버 중단 불필요)
cp db/mydatabase.db db/backup-$(date +%Y%m%d).db

# Docker 환경
docker cp backend-container:/app/db/mydatabase.db ./backup.db
```

#### Flyway 마이그레이션
- 서버 시작 시 자동 실행 (V1~V19)
- 새 마이그레이션 추가 시 `backend/src/main/resources/db/migration/V20__*.sql` 파일 생성
- **주의**: 기존 마이그레이션 파일은 절대 수정하지 마세요

### 2.5 로그 관리

#### 로그 위치
- 콘솔 출력 (기본)
- Docker: `docker-compose logs -f backend`

#### 로그 레벨 변경
```env
# .env 파일에서
LOG_LEVEL=DEBUG    # 문제 해결 시
LOG_LEVEL=INFO     # 일반 운영 시
LOG_LEVEL=WARN     # 최소 로깅
```

---

## 3. 에이전트 관리

### 3.1 에이전트 배포 절차

#### 1단계: 설치키 확인
1. 프론트엔드 → 탭 4 (관리) → 에이전트 설치 관리
2. "설치키 조회" 클릭 → 설치키 복사

#### 2단계: 에이전트 파일 준비
| 파일 | 설명 |
|------|------|
| `agent-1.0.0.jar` | 에이전트 JAR (agent/build/libs/) |
| `config.json` | 서버URL + 설치키 설정 파일 |

config.json 예시:
```json
{
  "serverUrl": "https://pelmon.kro.kr",
  "installKey": "INST-xxxxxxxx"
}
```

#### 3단계: PC에 설치
```powershell
# 에이전트 디렉터리 생성
mkdir C:\PCAgent

# 파일 복사
copy agent-1.0.0.jar C:\PCAgent\
copy config.json C:\PCAgent\

# 서비스 설치 (관리자 권한)
cd C:\PCAgent
.\install.bat
```

#### 4단계: 등록 확인
- 프론트엔드 관리 탭에서 에이전트 목록 확인
- MAC 주소, IP, 마지막 접속 시간 확인

### 3.2 에이전트 일괄 배포

여러 PC에 동시 배포 시:
1. 배포 서버에서 `agent-1.0.0.jar` + `config.json` 준비
2. GPO, SCCM, 또는 PowerShell Remoting으로 배포
3. 각 PC에서 서비스 등록

```powershell
# PowerShell Remoting 예시
$pcs = @("PC-101-001", "PC-101-002", "PC-101-003")
$pcs | ForEach-Object {
    Invoke-Command -ComputerName $_ -ScriptBlock {
        mkdir C:\PCAgent -Force
        # 파일 복사 및 서비스 등록
    }
}
```

### 3.3 에이전트 토큰 관리

| 작업 | 방법 |
|------|------|
| **토큰 폐기** | 관리 탭 → 에이전트 목록 → 토큰 폐기 버튼 |
| **토큰 재발급** | 폐기 후 에이전트가 자동 재등록 (최대 3회 시도) |
| **에이전트 삭제** | 관리 탭 → 에이전트 목록 → 삭제 버튼 |

> **보안 주의**: 무허가 PC가 발견되면 즉시 해당 에이전트 토큰을 폐기하세요.

### 3.4 에이전트 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| 등록 실패 (exit code 2) | 설치키 불일치 또는 서버 미접속 | config.json 확인, 방화벽 확인 |
| 401/403 반복 | 토큰 폐기됨 | 에이전트 재시작 → 자동 재등록 |
| 하트비트 미전송 | 서비스 중단 | 서비스 상태 확인, watchdog 스크립트 활용 |
| 명령 실행 실패 | 권한 부족 | 에이전트를 관리자 권한으로 실행 |

#### Watchdog 스크립트
```powershell
# 에이전트 프로세스 감시 (scripts/agent-watchdog.ps1)
# 작업 스케줄러에 등록하여 5분 주기 실행 권장
```

---

## 4. 강의실 초기 설정

### 4.1 설정 순서

```
1. 강의실 생성
   └── 이름/설명 입력
2. IP 대역 등록
   └── CIDR (192.168.10.0/24) 또는 범위 (10.1~10.50)
3. 교사 PC 등록 (선택)
   └── 교사 PC의 IP/MAC 주소
4. 에이전트 배포
   └── 해당 강의실 PC에 에이전트 설치
5. 대시보드 패널 배치
   └── 메인 탭에서 강의실 패널 추가 및 배치
```

### 4.2 IP 대역 설계 가이드

| 구성 | 예시 | 설명 |
|------|------|------|
| 강의실별 서브넷 | 192.168.10.0/24 | 강의실 1: .10 대역 |
| | 192.168.11.0/24 | 강의실 2: .11 대역 |
| 교사 PC IP | 192.168.10.1 | 각 강의실의 .1 번 |
| 포인트 | 대역이 겹치면 등록 거부됨 | 409 Conflict |

### 4.3 중복 대역 주의
- 하나의 IP는 하나의 강의실에만 속할 수 있습니다.
- 대역 등록 시 기존 대역과의 겹침이 자동 검출됩니다.
- 겹치는 대역 등록 시도 시 409 Conflict 에러가 발생합니다.

---

## 5. 보안 운영

### 5.1 초기 보안 설정 (필수)

| 항목 | 작업 | 위치 |
|------|------|------|
| JWT 시크릿 변경 | 32자 이상 랜덤 문자열 설정 | `.env` → `JWT_SECRET` |
| 설치키 변경 | 고유한 설치키 설정 | `.env` → `AGENT_INSTALL_KEY` |
| 관리자 비밀번호 | 기본 비밀번호 변경 | DB 또는 API |
| HTTPS 활성화 | SSL 인증서 설정 | Nginx 설정 |

### 5.2 에이전트 인증 체계

```
에이전트 ──(설치키)──▶ 서버 등록
                         │
                         ▼
                   토큰 발급 (agt_UUID)
                         │
                         ▼
에이전트 ──(X-Agent-Token)──▶ 하트비트/명령
                         │
서버 ──(SHA-256 해시 비교)──▶ 인증 확인
```

- 토큰은 서버에 **해시(SHA-256)로만 저장** (평문 미저장)
- 설치키 검증 시 **타이밍 어택 방어** 적용 (MessageDigest.isEqual)

### 5.3 감사 로그

| 기록 이벤트 | 설명 |
|-----------|------|
| HEARTBEAT | PC 하트비트 수신 |
| PC_GROUP_REASSIGNED | PC 강의실 재배정 |
| REVOKE_TOKEN | 에이전트 토큰 폐기 |
| 명령 실행 | 원격 명령 발행/ACK/결과 |

조회: `GET /api/v1/admin/audit/logs`

### 5.4 정기 보안 점검

| 주기 | 점검 항목 |
|------|----------|
| 매일 | 에이전트 목록 확인 (미등록/무허가 PC) |
| 주간 | 감사 로그 검토 |
| 월간 | JWT 시크릿/설치키 변경 고려 |
| 분기 | 의존성 CVE 스캔, 서버 업데이트 |

---

## 6. 모니터링·경보

### 6.1 자동 스케줄러

| 주기 | 작업 | 경보 조건 |
|------|------|----------|
| 1분 | 트래픽 이상 감지 | PC당 300Mbps, 강의실당 1000Mbps, 전체 5000Mbps 초과 |
| 5분 | 오프라인 감지 | 5분 이내 하트비트 없는 ONLINE PC |
| 1분 | 명령 타임아웃 | DELIVERED/ACKED 180초 초과 |
| 1분 | 시스템 부하 | 큐 백로그, 지연 명령 수 |
| 2분 | SCMATE 감시 | 관리 작업 ACK 후 PC OFFLINE |

### 6.2 이메일 경보 설정

```env
# .env
ALERT_EMAIL=admin@school.ac.kr
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=school-alert@gmail.com
MAIL_PASSWORD=앱-비밀번호
```

> 메일 설정이 없으면 경보가 로그에만 기록됩니다 (시스템 장애 없음).

---

## 7. 백업·복구

### 7.1 백업 대상

| 대상 | 경로 | 방법 |
|------|------|------|
| SQLite DB | `db/mydatabase.db` | 파일 복사 |
| 업로드 파일 | `uploads/` | 디렉터리 복사 |
| 환경 설정 | `.env` | 파일 복사 |

### 7.2 자동 백업 스크립트 (권장)

```powershell
# daily-backup.ps1
$date = Get-Date -Format "yyyyMMdd"
$backupDir = "D:\Backups\dashboard\$date"
New-Item -ItemType Directory -Force $backupDir
Copy-Item "db\mydatabase.db" "$backupDir\mydatabase.db"
Copy-Item -Recurse "uploads" "$backupDir\uploads"
Copy-Item ".env" "$backupDir\.env"
Write-Host "백업 완료: $backupDir"
```

### 7.3 복구 절차

1. 서버 중지
2. 백업 파일을 원래 경로에 복사
3. 서버 재시작
4. 정상 동작 확인

---

## 8. 트러블슈팅

### 8.1 서버 시작 실패

| 증상 | 원인 | 해결 |
|------|------|------|
| 포트 충돌 | 8888 포트 사용 중 | `SERVER_PORT` 변경 또는 기존 프로세스 종료 |
| DB 오류 | 마이그레이션 실패 | DB 파일 백업 후 삭제 → 서버 재시작 |
| 메모리 부족 | JVM 힙 부족 | `-Xmx512m` 이상 설정 |

### 8.2 프론트엔드 문제

| 증상 | 원인 | 해결 |
|------|------|------|
| 로그인 실패 | 서버 미실행 또는 네트워크 | 서버 상태 확인, 방화벽 확인 |
| 패널 미표시 | WebSocket 끊김 | 자동 HTTP 폴링으로 복구 (15초) |
| 위치 미저장 | API 오류 | 편집 모드에서 재배치 |

### 8.3 에이전트 문제

| 증상 | 원인 | 해결 |
|------|------|------|
| 하트비트 미전송 | 서비스 중단 | 서비스 재시작, watchdog 설정 |
| 명령 미실행 | 권한 부족 | 관리자 권한으로 서비스 실행 |
| WOL 미작동 | 네트워크 차단 | 스위치/방화벽 설정 확인 |

---

## 9. Docker 운영

### 9.1 주요 명령어

```bash
# 전체 스택 시작/중지/재시작
docker-compose up -d
docker-compose down
docker-compose restart backend

# 로그 확인
docker-compose logs -f backend
docker-compose logs --tail=100 nginx

# 컨테이너 상태
docker-compose ps
docker stats
```

### 9.2 SSL 인증서 관리

```bash
# 초기 인증서 발급
docker exec nginx /opt/init-ssl.sh

# 수동 갱신
docker exec nginx /opt/renew-ssl.sh
```

### 9.3 업데이트 절차

```bash
# 1. 새 이미지 빌드
docker-compose build

# 2. 서비스 업데이트 (무중단)
docker-compose up -d --build backend

# 3. 상태 확인
docker-compose ps
docker-compose logs -f backend
```
