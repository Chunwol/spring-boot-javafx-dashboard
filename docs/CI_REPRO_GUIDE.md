# CI 재현 가이드

CI 실패 시 아래 순서로 로컬에서 동일하게 재현할 수 있습니다.

## 1) 빌드 파이프라인 (`build`)
```bash
./gradlew :common:compileJava :backend:compileJava :agent:compileJava :frontend:compileJava -x test
```

## 2) 단위 테스트 파이프라인 (`unit`)
```bash
./gradlew :common:test :agent:test :backend:unitTest :frontend:unitTest
```

## 3) 통합/API 스모크 파이프라인 (`integration`)
```bash
./gradlew :backend:integrationTest
```

## 4) JavaFX UI 스모크 파이프라인 (`ui-smoke`)
Linux/CI:
```bash
xvfb-run -a ./gradlew :frontend:uiSmokeTest
```

Windows/macOS:
```bash
./gradlew :frontend:uiSmokeTest
```

## 참고
- 통합 테스트는 임시 SQLite DB 및 임시 업로드 디렉터리를 자동 생성/정리합니다.
- CI 리포트는 각 워크플로우 아티팩트(`test-results`, `test-reports`)에서 확인할 수 있습니다.
