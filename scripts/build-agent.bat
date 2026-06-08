@echo off
chcp 65001 >nul 2>&1
:: ============================================================
:: 에이전트 빌드 + 배포 패키지 생성 (Windows)
:: 출력:
::   agent\build\libs\agent-1.0.0.jar
::   dist\agent-1.0.0-deploy.zip  ← 배포 패키지
:: 사용법: scripts\build-agent.bat
:: ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo  에이전트 빌드 + 배포 패키지 생성
echo ============================================================
echo.

echo [1/2] 에이전트 JAR 빌드 중 (테스트 건너뜀)...
call gradlew.bat :agent:jar -x test
if !errorlevel! neq 0 (
    echo [오류] 에이전트 JAR 빌드 실패
    pause
    exit /b 1
)
echo [OK] JAR 생성: agent\build\libs\agent-1.0.0.jar
echo.

echo [2/2] 배포 패키지 생성 중 (dist\agent-1.0.0-deploy.zip)...
call gradlew.bat :agent:deployPackage
if !errorlevel! neq 0 (
    echo [오류] 배포 패키지 생성 실패
    pause
    exit /b 1
)
echo.
echo ============================================================
echo  완료!
echo.
echo  배포 패키지: dist\agent-1.0.0-deploy.zip
echo  패키지 내용:
echo    agent-package\
echo      agent-1.0.0.jar       - 실행 파일
echo      config.json           - 설정 템플릿 (serverUrl/installKey 수정 필요)
echo      install.bat           - 설치 스크립트 (관리자 권한으로 실행)
echo      uninstall.bat         - 제거 스크립트
echo      agent-watchdog.ps1    - 워치독 (자동 재시작)
echo      docs\AGENT_INSTALL_GUIDE.md
echo.
echo  [ 배포 전 필수 ]
echo    config.json 에서 serverUrl 과 installKey 를 실제 값으로 수정하세요.
echo    (installKey는 대시보드 → 설정 → 에이전트 설치 관리에서 확인)
echo ============================================================
echo.
pause
endlocal
