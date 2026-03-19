# Dashboard Performance Baseline

## 목적
- 통합 새로고침 API와 위젯 API의 응답 시간을 재현 가능하게 기록
- 백엔드 메모리 사용량을 최소 기준으로 추적
- 변경 전후 성능 비교 기준점 제공

## 측정 스크립트
- 실행 파일: `scripts/profile-dashboard.ps1`
- 기본 측정 대상:
  - `/api/v1/dashboard/refresh`
  - `/api/v1/dashboard/classrooms`
  - `/api/v1/dashboard/texts`
  - `/api/v1/dashboard/images`

## 실행 방법
```powershell
cd D:\Qsync\spring-boot-javafx-dashboard
.\scripts\profile-dashboard.ps1
```

## 기록 항목
- 평균 응답 시간(ms)
- 최소/최대 응답 시간(ms)
- 페이로드 크기(bytes)
- 포트 8080 리스너 프로세스의 Working Set / Private Memory(MB)

## 해석 기준
- Unified refresh 평균 응답 시간:
  - 100ms 이하: 매우 양호
  - 100ms ~ 250ms: 허용
  - 250ms 초과: 추적 필요
- Backend Working Set:
  - 200MB 이하 유지 목표

## 최신 측정 결과
- 최신 결과는 `scripts/profile-dashboard.latest.json`에 저장됨
- 본 문서는 대표 수치만 갱신하고, 세부 샘플은 JSON 파일을 기준으로 확인

### 2026-03-19 11:38:59
- 측정 조건:
  - Base URL: `http://localhost:8080/api/v1`
  - Iterations: 5
  - Warmup: 1
- API 응답 시간:

| Endpoint | Avg (ms) | Min (ms) | Max (ms) | Payload (bytes) |
|------|------:|------:|------:|------:|
| Unified refresh | 38.93 | 33.52 | 49.11 | 1542 |
| Classrooms | 19.80 | 17.58 | 23.17 | 1455 |
| Texts | 12.93 | 12.01 | 13.79 | 2 |
| Images | 11.86 | 11.38 | 12.31 | 2 |

- Backend memory:
  - PID: 8420
  - Working Set: 119.45MB
  - Private Memory: 422.12MB

- 해석:
  - Unified refresh 평균 38.93ms로 응답 시간은 매우 양호
  - Working Set 기준으로는 목표(200MB 이하) 충족
  - Private Memory는 422.12MB로 추가 추적 필요