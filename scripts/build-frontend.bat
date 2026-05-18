@echo off
REM Build Frontend Only - Windows (ShadowJAR)
REM Usage: scripts\build-frontend.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build Frontend Only (ShadowJAR)
echo ============================================================
echo.

echo [0/1] Cleaning up stale processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

echo [1/1] Building Frontend ShadowJAR...
call gradlew.bat :frontend:shadowJar -x test
if !errorlevel! neq 0 (
    echo [ERROR] Frontend build failed
    exit /b 1
)

echo.
echo [OK] Frontend JAR created:
echo   frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar
echo.
echo Run with: java -jar frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar
echo.

endlocal
