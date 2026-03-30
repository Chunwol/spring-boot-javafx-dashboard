# 배포 체크리스트 (Deployment Checklist)

## 빌드 상태 ✅

생성된 아티팩트:
- ✅ `backend/build/libs/backend-0.0.1-SNAPSHOT.jar` (Spring Boot App)
- ✅ `agent/build/libs/agent-1.0.0.jar` (Fat JAR with dependencies)
- ✅ `frontend/build/libs/dashboard-0.0.1.jar` (Shadow JAR with all dependencies)

컴파일 상태: **BUILD SUCCESSFUL**

---

## 배포 파일들

### 생성된 파일 목록

#### 1. Dockerfile들
| 파일 | 목적 | 상태 |
|------|------|------|
| `Dockerfile` | Backend 컨테이너 | ✅ 멀티스테이지 빌드 |
| `agent/Dockerfile` | Agent 컨테이너 | ✅ 멀티스테이지 빌드 |
| `frontend/Dockerfile` | Frontend 컨테이너 | ✅ JavaFX 디스플레이 지원 |

#### 2. 빌드 스크립트
| 파일 | OS | 목적 |
|------|-----|------|
| `scripts/build-all.sh` | Linux/Mac | 모든 Java 모듈 빌드 |
| `scripts/build-all.bat` | Windows | 모든 Java 모듈 빌드 |
| `scripts/build-docker.sh` | Linux/Mac | Docker 이미지 빌드 |
| `scripts/build-docker.bat` | Windows | Docker 이미지 빌드 |

#### 3. 배포 스크립트
| 파일 | OS | 목적 |
|------|-----|------|
| `scripts/deploy.sh` | Linux/Mac | 전체 배포 자동화 |
| `scripts/deploy.bat` | Windows | 전체 배포 자동화 |

#### 4. 설정 파일
| 파일 | 목적 |
|------|------|
| `.env` | 환경 변수 (보안, git-ignored) |
| `.env.example` | 환경 변수 템플릿 |
| `.dockerignore` | Docker 빌드 최적화 |
| `agent/.dockerignore` | Docker 빌드 최적화 |
| `frontend/.dockerignore` | Docker 빌드 최적화 |

#### 5. 문서
| 파일 | 내용 |
|------|------|
| `DEPLOYMENT.md` | 상세 배포 가이드 |
| `DEPLOYMENT_CHECKLIST.md` | 배포 체크리스트 (이 파일) |

---

## 빠른 배포 가이드

### Windows에서 배포 (권장)
```powershell
# 1단계: 모든 스크립트 실행 권한 부여
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# 2단계: 자동 배포 스크립트 실행
.\scripts\deploy.bat
```

### Linux/Mac에서 배포
```bash
# 1단계: 스크립트 권한 부여
chmod +x scripts/deploy.sh
chmod +x scripts/build-all.sh
chmod +x scripts/build-docker.sh

# 2단계: 자동 배포 스크립트 실행
./scripts/deploy.sh
```

---

## 사전 체크리스트

배포 전에 다음을 확인하세요:

### ✅ 시스템 요구사항
- [ ] Docker 설치 (v20.10+)
- [ ] Docker Compose 설치 (v2.0+)
- [ ] Java 17 설치 (선택사항, JAR 직접 실행 시)
- [ ] Git 설치 (선택사항, 소스 관리용)

### ✅ 환경 설정
- [ ] `.env` 파일 존재 여부 확인
- [ ] `.env` 파일의 모든 환경 변수 설정 확인:
  ```
  SERVER_PORT=8888
  JWT_SECRET=<your_secret>
  AGENT_INSTALL_KEY=<your_key>
  ```

### ✅ 네트워크 설정
- [ ] 외부 도메인: `pelmon.kro.kr` DNS 레코드 확인
- [ ] 포트 개방: 80 (HTTP), 443 (HTTPS) 외부 접근 가능
- [ ] 포트 차단: 8888 (Backend) 외부 접근 불가

### ✅ SSL/TLS 인증서
- [ ] ZeroSSL EAB 자격증명 준비 (`.env` 파일에 저장)
- [ ] 이메일 주소 준비 (인증서 갱신 알림용)

### ✅ 데이터베이스
- [ ] 데이터베이스 초기화 스크립트 준비
- [ ] 백업 위치 준비

---

## 배포 단계별 검증

### 1단계: Java 아티팩트 빌드
```bash
# Windows
.\scripts\build-all.bat

# Linux/Mac
./scripts/build-all.sh
```
**예상 결과:**
- ✅ `backend/build/libs/backend-0.0.1-SNAPSHOT.jar` ← 크기 ~50MB
- ✅ `agent/build/libs/agent-1.0.0.jar` ← 크기 ~30MB
- ✅ `frontend/build/libs/dashboard-0.0.1.jar` ← 크기 ~80MB

### 2단계: Docker 이미지 빌드
```bash
# Windows
.\scripts\build-docker.bat

# Linux/Mac
./scripts/build-docker.sh
```
**예상 결과:**
```
$ docker images | grep dashboard
dashboard-backend:latest       <image-id>  <size>
dashboard-agent:latest         <image-id>  <size>
dashboard-frontend:latest      <image-id>  <size>
```

### 3단계: Docker Compose 스택 시작
```bash
docker-compose up -d
```
**예상 결과:**
```
$ docker-compose ps
NAME      STATUS          PORTS
nginx     Up 2 seconds    0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp
app       Up 2 seconds    8888/tcp
```

### 4단계: 헬스체크
```bash
# HTTP → HTTPS 리다이렉트 확인
curl -I http://pelmon.kro.kr

# HTTPS 연결 확인
curl -I https://pelmon.kro.kr/health

# Backend API 확인
curl https://pelmon.kro.kr/api/health
```

---

## 성공 표시

다음 로그가 보이면 배포 성공입니다:

### Backend 로그
```
Started Application in X seconds (JVM running for Y seconds)
```

### Nginx 로그
```
nginx_1 | [notice] signal process started
```

### 브라우저에서 확인
```
GET https://pelmon.kro.kr/health
Response: {"status":"UP","components":{...}}
```

---

## 배포 후 단계

### 1. Frontend 실행
```bash
# JAR로 실행 (그래픽 필요)
java -jar frontend/build/libs/dashboard-0.0.1.jar

# 또는 Docker로 실행 (X11 필요)
docker-compose run frontend
```

### 2. Agent 배포 (학생 PC)
```bash
# Windows에서
java -jar agent-1.0.0.jar

# 또는 설치 스크립트 사용
scripts\install-agent-windows.bat agent-1.0.0.jar
```

### 3. 모니터링 시작
```bash
# 실시간 로그 보기
docker-compose logs -f

# 컨테이너 상태 모니터링
watch docker-compose ps
```

---

## 문제 해결

### Backend가 시작되지 않음
```bash
# 로그 확인
docker-compose logs backend

# 포트 충돌 확인
lsof -i :8888

# 데이터베이스 초기화 재시도
docker exec app mysql -u root -p < scripts/init-db.sql
```

### Frontend 연결 안 됨
```bash
# Backend 헬스 확인
curl https://pelmon.kro.kr/health

# WebSocket 확인
curl -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" \
  https://pelmon.kro.kr/ws/stomp
```

### 인증서 갱신 실패
```bash
# 인증서 상태 확인
docker exec nginx certbot certificates

# 로그 확인
docker logs nginx | grep certbot

# 수동 갱신
docker exec nginx certbot renew --force-renewal
```

---

## 배포 완료 체크리스트

- [ ] Backend 컨테이너 실행 중
- [ ] Nginx 리버스 프록시 실행 중
- [ ] HTTPS 인증서 유효
- [ ] Frontend에서 로그인 가능
- [ ] Agent에서 하트비트 수신
- [ ] 데이터베이스 초기화 완료
- [ ] 백업 정책 수립

---

## 주요 포트 설정

| 포트 | 서비스 | 외부 접근 | 용도 |
|------|--------|---------|------|
| 80 | Nginx | ✅ 허용 | HTTP → HTTPS 리다이렉트 |
| 443 | Nginx | ✅ 허용 | HTTPS API & WebSocket |
| 8888 | Backend | ❌ 차단 | 내부 통신 (Nginx 경유) |

---

## 성능 최적화 (선택사항)

### 데이터베이스 인덱스
```sql
CREATE INDEX idx_pc_group ON pc(group_id);
CREATE INDEX idx_command_status ON command(status);
CREATE INDEX idx_heartbeat_pc ON heartbeat(pc_id, created_at DESC);
```

### JVM 튜닝 (.env)
```env
JAVA_OPTS=-Xmx2048m -Xms1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

---

## 운영 팁

### 정기 유지보수
```bash
# 주간 백업
docker exec app tar czf /backup/db_$(date +%Y%m%d).tar.gz /app/db

# 월간 로그 정리
docker-compose logs --tail 100 > logs_$(date +%Y%m%d).txt

# 분기별 인증서 점검
docker exec nginx certbot certificates
```

### 모니터링
```bash
# 실시간 리소스 모니터링
watch -n 5 'docker stats --no-stream'

# 로그 수집
docker-compose logs > full_logs_$(date +%Y%m%d_%H%M%S).txt
```

---

**배포 완료 날짜**: ___________

**배포 담당자**: ___________

**검증자**: ___________

---

*마지막 업데이트: 2026-03-18*
