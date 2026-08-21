# Deployment Checklist

- [ ] `web-frontend`: typecheck, unit test, production build 통과
- [ ] `backend`: 전체 테스트와 Boot JAR 빌드 통과
- [ ] `agent`: 전체 테스트와 배포 JAR 빌드 통과
- [ ] 환경 파일에 기본 비밀값이 남아 있지 않음
- [ ] DB 백업과 이전 Docker 이미지 태그 확보
- [ ] 새 이미지 로드 후 `app` 서비스만 재생성
- [ ] `/actuator/health`, `/`, 로그인, WebSocket, noVNC 확인
- [ ] 자동 로그인 쿠키에 `HttpOnly`, `SameSite=Strict` 적용 확인
- [ ] HTTPS 운영이면 `JWT_REFRESH_COOKIE_SECURE=true` 확인
- [ ] 실제 PC 명령은 승인된 테스트 대상에서만 확인
