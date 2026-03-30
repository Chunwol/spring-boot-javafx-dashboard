# Backend - 작업 현황 및 다음 단계

> **최종 갱신일**: 2026-03-30

## 완료된 작업

### ✅ 1. Gradle 멀티모듈 설정
- 루트 `settings.gradle`에 `:backend`, `:frontend`, `:common`, `:agent` 모듈 구성
- Java 17 호환, Gradle 9.x 환경 안정화

### ✅ 2. DB 마이그레이션 (Flyway)
- `V1__init.sql` ~ `V19` 까지 안정적으로 운영 중
- 테이블: `pc_master`, `classroom_groups`, `classroom_ip_ranges`, `classroom_teacher_devices`, `traffic_logs`, `command_queue`, `client_positions`, `dashboard_modules`, `script_artifacts`, `audit_logs`, `users`

### ✅ 3. JWT 인증
- `/api/v1/auth/login` 토큰 발급
- `Authorization: Bearer <token>` 기반 보호
- 에이전트는 별도 `X-Agent-Token` 채널 사용
- ⚠️ Refresh token은 미구현 (향후 과제)

### ✅ 4. 프론트엔드 (JavaFX)
- 로그인 화면 → JWT 획득 → 대시보드 전환
- Grafana 스타일 대시보드: 강의실 패널, 텍스트/이미지 위젯
- Edit Mode / Focus Mode / 드래그·리사이즈 / PC 그리드
- AtlantaFX PrimerLight 테마 적용
- MVVM 아키텍처 진행 중 (DashboardViewModel, DashboardUIService)

### ✅ 5. 대시보드 모듈 개선
- 강의실 자동 매핑 (IP 대역 기반)
- 교사PC/학생PC 역할 자동 판별
- PC 관리 화면 (일괄 명령, 고장 등록, WOL)
- 강의실 설정 (IP 대역 등록, 교사PC 등록)

### ✅ 6. Docker & Docker Compose
- Dockerfile (Backend, Agent), Nginx 리버스 프록시
- docker-compose.yml 프로덕션 구성
- SSL/TLS (ZeroSSL) 자동 인증서

### ✅ 7. 보고서 및 PDF
- iTextPDF 기반 PDF 리포트 생성
- 강의실별 가동률, 트래픽 요약, 명령 이력
- MailService 통한 이메일 발송 (예외 처리 포함)

### ✅ 8. 코드 품질 개선 (2026-03-30)
- 데드코드 제거: `AutoGroupingService`, `UserDetailsServiceImpl`, `ApiCompatibilityController`, `TrafficLogRepository`, `ApiController(user)` 삭제
- Silent catch → SLF4J 로깅 전환 (전체 모듈)
- `@Transactional` 누락 보완 (`CommandController.ack/result`, `GroupController.deleteGroup`)
- N+1 쿼리 최적화: `ClassroomValidationService` → DB 쿼리 전환, `LogController` → DB 페이징/정렬
- `MailService` 예외 처리 추가

## 미완료 / 향후 과제

- [ ] **CI/CD**: GitHub Actions 자동 빌드·테스트 파이프라인
- [ ] **Observability**: Micrometer 메트릭 + Prometheus/Grafana 연동
- [ ] **JWT Refresh Token**: 토큰 갱신 메커니즘
- [ ] **테스트 확대**: 단위/통합 테스트 커버리지 확대
- [ ] **RBAC**: 관리자/교사 권한 분리

## 참고 문서

- [ENV_GUIDE.md](../ENV_GUIDE.md) - 환경 설정값 가이드
- [DEPLOYMENT.md](../DEPLOYMENT.md) - 상세 배포 가이드
- [DEPLOYMENT_CHECKLIST.md](../DEPLOYMENT_CHECKLIST.md) - 배포 체크리스트
- [API_DOCUMENTATION.md](../API_DOCUMENTATION.md) - REST API 문서
