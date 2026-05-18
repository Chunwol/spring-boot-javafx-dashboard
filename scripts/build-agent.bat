@echo off
REM Build Agent Only - Windows
REM Usage: scripts\build-agent.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build Agent Module Only
echo ============================================================
echo.

echo [0/1] Cleaning up stale processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

echo [1/1] Building Agent JAR...
call gradlew.bat :agent:build -x test
if !errorlevel! neq 0 (
    echo [ERROR] Agent build failed
    exit /b 1
)

echo.
echo [OK] Agent JAR created:
echo   agent\build\libs\agent-1.0.0.jar
echo.

endlocal
