@echo off
REM Build Frontend Only - Windows (ShadowJAR)
REM Usage: scripts\build-frontend.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build Frontend Only (Platform JARs)
echo ============================================================
echo.

echo [0/1] Cleaning up stale processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

echo [1/1] Building Frontend platform JARs...
call gradlew.bat :frontend:publishPlatformJars -x test
if !errorlevel! neq 0 (
    echo [ERROR] Frontend build failed
    exit /b 1
)

echo.
echo [OK] Frontend platform JARs created:
echo   dist\frontend\frontend-win.jar
echo   dist\frontend\frontend-mac.jar
echo   dist\frontend\frontend-mac-aarch64.jar
echo   dist\frontend\frontend-linux.jar
echo.
echo Run on Windows with: java -jar dist\frontend\frontend-win.jar
echo.

endlocal
