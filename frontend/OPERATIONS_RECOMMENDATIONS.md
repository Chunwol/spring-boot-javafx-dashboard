**Frontend Operations Recommendations**

목표: 데스크톱 JavaFX 클라이언트의 운영(로그, 패키징, 자동 업데이트, 모니터링)을 단순화하고 유지보수 부담을 낮춥니다.

- **로깅 & 진단**: `logback.xml`을 통해 사용자 홈 디렉토리(~/.classroom-dashboard/logs)에 회전 로그를 저장합니다. 로그 레벨은 `LOG_LEVEL`로 제어하고, 오류 발생 시 진단 번들을 생성하는 UI(로그 압축)를 제공하면 운영 대응이 용이합니다.
- **패키징**: Windows용 `jpackage`를 사용하여 `app-image`/`msi`를 생성해 배포합니다. `frontend/build.gradle`의 `packagePortable`/`packageMsi` 태스크를 CI에서 호출할 수 있도록 Windows 빌드 에이전트에 `jpackage` 실행 환경을 확보하세요.
- **자동 업데이트**: 현재 에이전트의 OTA 패턴을 활용 (서버에 새 릴리스 업로드 → 클라이언트가 다운로드 및 검증 → 교체 + 검증 → 롤백). 데스크톱 클라이언트의 경우 업데이트 트리거를 에이전트가 담당하게 하거나, 자체 자동업데이트 모듈을 추가할 수 있습니다. 서명된 아티팩트(디지털 서명)를 권장합니다.
- **프로세스 관리**: Windows 환경에서는 NSSM 또는 Windows 서비스(스크립트)로 등록해 자동 재시작을 보장합니다. GUI 충돌 시 watchdog 또는 scheduled task로 재시작 로직을 보강하세요.
- **모니터링**: 백엔드에서 이미 Prometheus/metrics를 사용 중이므로, 프론트엔드는 최소한의 상태 지표(연결상태, 최근 재시도, 버전)를 노출해 중앙에서 수집하게 하세요.
- **보안**: `frontend/.env`는 로컬 전용으로 유지하고 절대 리포지토리에 민감 정보를 남기지 마십시오. 토큰/refresh token은 OS 키체인(Windows Credential Manager / macOS Keychain / Secret Service) 사용을 권장합니다.

간단 체크리스트
- `frontend/.env.template` 제공 (완료)
- 배포 파이프라인에서 `jpackage` 단계 설정
- OTA를 이용한 서명된 아티팩트 배포 정책 수립
- 로그 압축(진단 번들) 생성 UI 추가
- 모니터링 지표(버전, WS 연결 상태) 수집
