@echo off
REM Build All Modules - Windows
REM Usage: scripts\build-all.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build All Modules (Windows)
echo ============================================================
echo.

REM 0. Clean up stale processes
echo [0/4] Cleaning up stale database/cache processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

REM 1. Common module
echo [1/4] Building Common Module...
call gradlew.bat :common:build -x test
if !errorlevel! equ 0 (
    echo [OK] Common module built successfully
) else (
    echo [ERROR] Common module build failed
    exit /b 1
)
echo.

REM 2. Backend module
echo [2/4] Building Backend + Web Dashboard...
call gradlew.bat :backend:bootJar -x test
if !errorlevel! equ 0 (
    echo [OK] Backend JAR: backend\build\libs\backend-0.0.1-SNAPSHOT.jar
) else (
    echo [ERROR] Backend build failed
    exit /b 1
)
echo.

REM 3. Agent module
echo [3/4] Building Agent Module...
call gradlew.bat :agent:build -x test
if !errorlevel! equ 0 (
    echo [OK] Agent JAR: agent\build\libs\agent-1.0.0.jar
) else (
    echo [ERROR] Agent build failed
    exit /b 1
)
echo.

echo.
echo [4/4] Collecting release artifacts to dist\
if not exist dist mkdir dist
if not exist dist\backend mkdir dist\backend
if not exist dist\agent mkdir dist\agent

copy /Y backend\build\libs\backend-0.0.1-SNAPSHOT.jar dist\backend\backend.jar >nul
copy /Y agent\build\libs\agent-1.0.0.jar dist\agent\agent.jar >nul

echo.
echo ============================================================
echo All modules built successfully!
echo ============================================================
echo.
echo Generated artifacts:
echo   - Backend + Web: dist\backend\backend.jar
echo   - Agent:         dist\agent\agent.jar
echo.
echo Next steps:
echo   1. Deploy backend.jar on the server
echo   2. Open the web dashboard at the backend URL
echo   3. Distribute agent.jar only to managed PCs
echo.

endlocal
