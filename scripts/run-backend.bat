@echo off
REM Backend server stack (Docker) - Windows
REM Usage: run-backend.bat [host-port]
REM   Default: BACKEND_HOST_PORT in .env (or 8888)
REM   Hyper-V port blocked? Try: run-backend.bat 8000
REM   Permanent fix (admin CMD): net stop winnat && net start winnat

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%.."

echo.
echo ============================================================
echo  Backend Server Stack (Docker)
echo ============================================================
echo.

REM Command-line port argument takes priority
if not "%~1"=="" set BACKEND_HOST_PORT=%~1

REM Fall back to 8888
if "%BACKEND_HOST_PORT%"=="" set BACKEND_HOST_PORT=8888

echo [INFO] Backend host port: %BACKEND_HOST_PORT%
echo.
echo Starting backend + postgres + redis via Docker Compose...

set BACKEND_HOST_PORT=%BACKEND_HOST_PORT%
docker compose up -d --build backend postgres redis
set COMPOSE_EXIT=%ERRORLEVEL%

REM Verify backend is actually running (exit code is unreliable on Windows)
docker compose ps backend 2>nul | findstr /i "backend" | findstr /i "Up" >nul 2>nul
set PS_EXIT=%ERRORLEVEL%

if %COMPOSE_EXIT% NEQ 0 (
    if %PS_EXIT% NEQ 0 (
        echo.
        echo ERROR: Backend container failed to start.
        echo.
        docker compose logs backend --tail=15 2>nul
        echo.
        echo -- Windows Hyper-V port reservation fix --
        echo   Option 1: Use a different port
        echo             scripts\run-backend.bat 8000
        echo   Option 2: Reset WinNAT (requires admin CMD)
        echo             net stop winnat
        echo             net start winnat
        echo             then retry: scripts\run-backend.bat
        echo.
        exit /b 1
    )
)

echo.
echo  Backend stack started successfully.
echo.
echo    Backend API : http://localhost:%BACKEND_HOST_PORT%
echo    Database    : postgres:5432  (internal Docker network)
echo    Cache       : redis:6379     (internal Docker network)
echo.
echo  Useful commands:
echo    docker compose ps
echo    docker compose logs -f backend
echo    docker compose down
echo.

endlocal
exit /b 0
