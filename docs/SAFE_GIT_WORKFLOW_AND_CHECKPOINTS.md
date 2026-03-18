# Safe Git Workflow (Local Checkpoints)

## 목적
- 편집 중 파일 손상/회귀 시 즉시 복구.
- 큰 변경을 작은 안전 단위로 기록.

## 기본 원칙
1. 작업 시작 전 checkpoint commit.
2. 위험 편집(대형 파일, 대규모 치환) 전 별도 commit.
3. 기능 단위로 commit 분리.
4. 빌드 통과 후 commit.

## 권장 명령
- 상태 확인
  - `git status --short --branch`
- 작업 시작 체크포인트
  - `git add -A`
  - `git commit -m "chore: checkpoint before <task>"`
- 기능 완료 커밋
  - `git add <changed files>`
  - `git commit -m "feat: <summary>"`
- 빌드 검증 후 태그
  - `git tag checkpoint-YYYYMMDD-HHMM`

## 복구 명령
- 직전 커밋으로 파일 복구
  - `git restore --source=HEAD -- <path>`
- 특정 체크포인트 비교
  - `git log --oneline --decorate --graph -n 20`
  - `git show <commit>`

## 브랜치 정책
- `main` 직접 장기 작업 금지.
- `feature/<short-topic>` 브랜치에서 작업.
- 하루 1회 이상 로컬 checkpoint.

## 이번 프로젝트 특이사항
- Qsync 디렉토리에서는 빌드 산출물/스냅샷 이슈가 생길 수 있으므로,
  큰 리팩터 전에는 반드시 checkpoint commit 후 진행.
