# 배포 가이드 (DEPLOYMENT.md)

이 문서는 통합 대시보드 시스템의 배포 절차와 환경 설정, 권장 사항을 안내합니다.

---

## 1. 배포 환경
- OS: Windows Server 2019/2022, Ubuntu 20.04+ 등
- Java: 25 이상
- Docker, docker-compose 권장
- Nginx(SSL), SQLite(내장)

## 2. 사전 준비
1. 서버 사양 확인 (CPU 2코어+, RAM 4GB+ 권장)
2. Java 25+ 설치 및 환경변수 등록
3. (선택) Docker, docker-compose 설치
4. 방화벽: 443(HTTPS), 8888(로컬) 포트 오픈

## 3. 배포 절차
### 3.1 Docker 기반 배포
```bash
git clone <repo-url>
cd spring-boot-javafx-dashboard
docker-compose build
docker-compose up -d
```
- Nginx, Backend, Frontend 컨테이너 자동 기동
- SSL 인증서: scripts/init-ssl.sh로 ZeroSSL 발급

### 3.2 수동 배포 (비권장)
1. gradlew :backend:bootRun, :frontend:run 별도 실행
2. Nginx 수동 설정 및 리버스 프록시 구성

## 4. 환경 변수
- .env 파일 참고 (SERVER_PORT, JWT_SECRET, AGENT_INSTALL_KEY 등)
- 상세: ENV_GUIDE.md

## 5. 배포 후 점검
- https://서버주소 접속, 로그인/대시보드 정상 동작 확인
- docker-compose logs -f backend, nginx 등으로 에러 확인

## 6. 롤백/복구
- scripts/daily-backup.ps1로 백업
- docker-compose down, up -d로 재기동

---

> 상세 배포 체크리스트는 DEPLOYMENT_CHECKLIST.md 참고
