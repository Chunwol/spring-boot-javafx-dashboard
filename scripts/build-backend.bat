@echo off
REM Build Backend Only - Windows
REM Usage: scripts\build-backend.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build Backend Only
echo ============================================================
echo.

echo [0/1] Cleaning up stale database/cache processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

echo [1/1] Building Backend JAR...
call gradlew.bat :backend:bootJar -x test
if !errorlevel! neq 0 (
    echo [ERROR] Backend build failed
    exit /b 1
)

echo.
echo [OK] Backend JAR created:
echo   backend\build\libs\backend-0.0.1-SNAPSHOT.jar
echo.

endlocal
