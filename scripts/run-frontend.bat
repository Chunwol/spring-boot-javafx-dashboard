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

if not exist "frontend\build\libs\dashboard-0.0.1-all.jar" (
    echo Building Frontend JAR first...
    call gradlew.bat :frontend:shadowJar -x test
)

echo.
echo Starting Frontend GUI...
echo Press Ctrl+C to stop
echo.

java -jar frontend\build\libs\dashboard-0.0.1-all.jar
endlocal
