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

echo Building Agent JAR...
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
