# 배포 체크리스트 (DEPLOYMENT_CHECKLIST.md)

배포 전/후 필수 점검 항목을 정리합니다.

---

## 1. 사전 점검
- [ ] 서버 사양/OS/Java 버전 확인
- [ ] 방화벽 포트(443, 8888) 오픈
- [ ] .env 파일 환경변수 최신화
- [ ] SSL 인증서 준비/갱신
- [ ] DB/업로드 폴더 백업

## 2. 배포 절차
- [ ] docker-compose build 정상 완료
- [ ] docker-compose up -d 정상 기동
- [ ] Nginx 리버스 프록시/SSL 정상 동작
- [ ] Backend/Frontend 서비스 기동 확인

## 3. 배포 후 점검
- [ ] https://서버주소 접속 및 로그인
- [ ] 대시보드/명령/PC 관리 등 주요 기능 정상 동작
- [ ] docker-compose logs -f backend 에러 없음
- [ ] 에이전트 하트비트/명령 정상 동작
- [ ] 백업 스크립트 정상 동작

## 4. 롤백/복구
- [ ] 백업 파일 복원 테스트
- [ ] docker-compose down/up -d로 재기동

---

> 상세 배포 환경/설정은 DEPLOYMENT.md, ENV_GUIDE.md 참고
