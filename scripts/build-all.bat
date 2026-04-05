@echo off
REM 모든 모듈 빌드 배치 파일
REM 공통, 백엔드, 에이전트, 프론트엔드 순서로 빌드

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo ================================
echo Building Dashboard Multi-Module
echo ================================
echo.

REM 1. Common 모듈 빌드
echo [1/4] Building Common Module...
call gradlew.bat :common:build -x test
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Common module built successfully ^& echo.
) else (
    echo. ^& echo *** Common module build failed *** ^& echo.
    exit /b 1
)

REM 2. Backend 모듈 빌드
echo [2/4] Building Backend Module...
call scripts\build-backend.bat
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Backend JAR created: backend\build\libs\backend-0.0.1-SNAPSHOT.jar ^& echo.
) else (
    echo. ^& echo *** Backend build failed *** ^& echo.
    exit /b 1
)

REM 3. Agent 모듈 빌드
echo [3/4] Building Agent Module...
call scripts\build-agent.bat
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Agent JAR created: agent\build\libs\agent-1.0.0.jar ^& echo.
) else (
    echo. ^& echo *** Agent build failed *** ^& echo.
    exit /b 1
)

REM 4. Frontend EXE 빌드
echo [4/4] Building Frontend Module...
call scripts\build-frontend.bat
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Frontend EXE created: frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe ^& echo.
) else (
    echo. ^& echo *** Frontend build failed *** ^& echo.
    exit /b 1
)

echo.
echo ================================
echo All modules built successfully!
echo ================================
echo.
echo Generated artifacts:
echo   • Backend:  backend\build\libs\backend-0.0.1-SNAPSHOT.jar
echo   • Agent:    agent\build\libs\agent-1.0.0.jar
echo   • Frontend: frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe
echo.
echo Next steps:
echo   1. Run scripts\run-backend.bat
echo   2. Run scripts\run-frontend-dev.bat or scripts\run-frontend.bat
echo   3. Run scripts\run-agent.bat
echo.

endlocal
