# CI Reproduction Guide

## Java modules

```bash
./gradlew :common:test :agent:test :backend:unitTest -PskipWebFrontendBuild=true --no-daemon
./gradlew :backend:integrationTest -PskipWebFrontendBuild=true --no-daemon
```

## Web frontend

```bash
cd web-frontend
npm ci
npm run typecheck
npm test
npm run build
```

## Release build

```bash
./gradlew :backend:bootJar :agent:build --no-daemon
```

JavaFX UI 테스트는 폐기됐으며 CI의 `web-ui` 작업이 브라우저 화면을 검증합니다.
