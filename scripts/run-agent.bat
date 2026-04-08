@echo off
REM Run Agent Only - Windows
REM Usage: scripts\run-agent.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Agent Monitor Service
echo ============================================================
echo.

if not exist "agent\build\libs\agent-1.0.0.jar" (
    echo Building Agent JAR first...
    call gradlew.bat :agent:build -x test
)

echo.
echo Starting Agent service...
echo Press Ctrl+C to stop
echo.

java -jar agent\build\libs\agent-1.0.0.jar
endlocal
