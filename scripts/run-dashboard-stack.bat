@echo off
REM Integrated startup runner for Dashboard stack (Windows)
REM Usage:
REM   scripts\run-dashboard-stack.bat
REM   scripts\run-dashboard-stack.bat --autostart

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%I in ("%SCRIPT_DIR%\..") do set "PROJECT_ROOT=%%~fI"

set "AUTOSTART_MODE=0"
if /I "%~1"=="--autostart" set "AUTOSTART_MODE=1"

if "%AUTOSTART_MODE%"=="0" (
    echo.
    echo ============================================================
    echo === Qsync Dashboard Integrated Runner
    echo ============================================================
    echo Project Root: %PROJECT_ROOT%
    echo.
)

if not exist "%PROJECT_ROOT%\gradlew.bat" (
    echo ERROR: gradlew.bat not found
    exit /b 1
)

if "%AUTOSTART_MODE%"=="0" (
    echo [0/2] Starting Docker server stack...
)
docker compose up -d --build
if errorlevel 1 (
    echo Docker Compose start failed!
    exit /b 1
)

if "%AUTOSTART_MODE%"=="0" (
    echo [1/2] Waiting for backend to become reachable...
)
for /L %%N in (1,1,12) do (
    powershell -NoProfile -Command "if (Test-NetConnection -ComputerName 127.0.0.1 -Port 8888 -WarningAction SilentlyContinue).TcpTestSucceeded { exit 0 } else { exit 1 }"
    if not errorlevel 1 goto BACKEND_READY
    timeout /t 5 /nobreak >nul
)

:BACKEND_READY

if "%AUTOSTART_MODE%"=="0" (
    echo [2/2] Starting Agent...
)
start "" /D "%PROJECT_ROOT%" cmd /c scripts\run-agent.bat

if "%AUTOSTART_MODE%"=="0" (
    echo.
    echo ============================================================
    echo === Startup complete (Docker server + detached clients)
    echo ============================================================
    echo Backend:  http://localhost:8888
    echo Dashboard: http://localhost:8888
    echo Agent:    scripts\run-agent.bat
    echo ============================================================
    echo.
)

endlocal
exit /b 0
