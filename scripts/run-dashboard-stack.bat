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

if not exist "%PROJECT_ROOT%\scripts\backend-bootstrap.ps1" (
    echo ERROR: scripts\backend-bootstrap.ps1 not found
    exit /b 1
)

if "%AUTOSTART_MODE%"=="0" (
    echo [0/3] Cleaning up stale database/cache processes...
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$redisProcs = Get-Process -Name 'redis-server' -ErrorAction SilentlyContinue; if ($redisProcs) { $redisProcs | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 300 }; $postgresProcs = Get-Process -Name 'postgres' -ErrorAction SilentlyContinue; if ($postgresProcs) { $postgresProcs | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500 }"

if "%AUTOSTART_MODE%"=="0" (
    echo [1/3] Bootstrap PostgreSQL/Redis...
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\backend-bootstrap.ps1" -NoBanner
if errorlevel 1 (
    echo Bootstrap failed!
    exit /b 1
)

set "BACKEND_LISTENING=0"
for /f "delims=" %%A in ('powershell -NoProfile -Command "if (Test-NetConnection -ComputerName 127.0.0.1 -Port 8888 -WarningAction SilentlyContinue).TcpTestSucceeded { '1' } else { '0' }"') do set "BACKEND_LISTENING=%%A"

if "%BACKEND_LISTENING%"=="1" (
    if "%AUTOSTART_MODE%"=="0" (
        echo [2/3] Backend already running on 127.0.0.1:8888. Skip start.
    )
) else (
    if "%AUTOSTART_MODE%"=="0" (
        echo [2/3] Starting Backend...
    )
    start "Qsync Backend - bootRun" cmd /c "cd /d "%PROJECT_ROOT%" && gradlew.bat :backend:bootRun"
    timeout /t 6 /nobreak >nul
)

if "%AUTOSTART_MODE%"=="0" (
    echo [3/3] Starting Frontend...
)
start "Qsync Frontend - run" cmd /c "cd /d "%PROJECT_ROOT%" && gradlew.bat :frontend:run"

if "%AUTOSTART_MODE%"=="0" (
    echo.
    echo ============================================================
    echo === Startup complete (detached windows)
    echo ============================================================
    echo Backend:  http://localhost:8888
    echo Frontend: JavaFX window
    echo ============================================================
    echo.
)

endlocal
exit /b 0
