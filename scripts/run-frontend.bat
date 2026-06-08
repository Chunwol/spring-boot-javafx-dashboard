@echo off
REM Run Frontend (Packaged JAR) - Windows
REM Usage: scripts\run-frontend.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Frontend (Packaged JAR)
echo ============================================================
echo.

set "JAR_PATH=dist\frontend\frontend-win.jar"

if not exist "%JAR_PATH%" (
    echo Building current-platform Frontend JAR first...
    call gradlew.bat :frontend:publishCurrentPlatformJar -x test
)

echo.
echo Starting Frontend GUI...
echo Press Ctrl+C to stop
echo.

java -jar "%JAR_PATH%"
set EXIT_CODE=%ERRORLEVEL%
endlocal
exit /b %EXIT_CODE%
