@echo off
REM Run Agent Only - Windows
REM Usage: scripts\run-agent.bat

setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
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
echo Config dir: %SCRIPT_DIR%
echo Press Ctrl+C to stop
echo.

java "-Dpcagent.config.dir=%SCRIPT_DIR%" -jar agent\build\libs\agent-1.0.0.jar
endlocal
