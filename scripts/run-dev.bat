@echo off
REM Development Mode - Integrated (Windows)
REM Usage: scripts\run-dev.bat
REM Builds project and starts both Backend and Frontend in separate terminal windows

setlocal enabledelayedexpansion

REM Navigate to parent directory (project root)
cd /d "%~dp0.."
set PROJECT_ROOT=%cd%

echo.
echo ============================================================
echo === Qsync Dashboard - Development Mode (Integrated)
echo ============================================================
echo Project Root: %PROJECT_ROOT%
echo.

REM Check if gradle wrapper exists
if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found
    exit /b 1
)

if not exist "scripts\backend-bootstrap.ps1" (
    echo ERROR: scripts\backend-bootstrap.ps1 not found
    exit /b 1
)

echo [0/5] Cleaning up stale database/cache processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\cleanup-runtime.ps1" -NoBanner

echo [1/5] Bootstrap PostgreSQL/Redis and backup settings...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\backend-bootstrap.ps1"
if errorlevel 1 (
    echo Bootstrap failed!
    exit /b 1
)

REM Build the project (skip clean — locked JARs on Windows cause unnecessary failures)
echo [2/5] Building project...
call gradlew.bat build -x test
if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

echo.
echo [OK] Build successful
echo.

REM Start backend in new terminal window
echo [3/5] Starting Backend in new window (port 8888)...
start "Backend - http://localhost:8888" cmd /c "cd /d "%PROJECT_ROOT%" && gradlew.bat :backend:bootRun"
timeout /t 6 /nobreak

echo.

REM Start frontend in new terminal window
echo [4/5] Starting Frontend in new window...
start "Frontend - JavaFX GUI" cmd /c "cd /d "%PROJECT_ROOT%" && gradlew.bat :frontend:run"

echo [5/5] Starting Agent in new window...
start "Agent - PCAgent" /D "%PROJECT_ROOT%" cmd /c scripts\run-agent.bat

echo.
echo ============================================================
echo === Services Running ===
echo ============================================================
echo Backend:  http://localhost:8888
echo Frontend: Check Frontend window
echo Agent:    scripts\run-agent.bat
echo.
echo To stop:
echo   - Close Backend window
echo   - Close Frontend window
echo   - Close Agent window
echo ============================================================
echo.

pause
endlocal
