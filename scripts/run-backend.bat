@echo off
REM Backend Only - Windows
REM Usage: run-backend.bat

setlocal enabledelayedexpansion

set PROJECT_ROOT=%~dp0
cd /d "%PROJECT_ROOT%.."

echo.
echo ============================================================
echo === Backend Only (Port 8888)
echo ============================================================
echo.

if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found
    exit /b 1
)

echo Building...
call gradlew.bat clean :backend:build -x test
if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

echo.
echo [OK] Build successful. Starting Backend...
echo Backend: http://localhost:8888
echo Press Ctrl+C to stop
echo.

call gradlew.bat :backend:bootRun
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo Backend run failed with code %EXIT_CODE%
    exit /b %EXIT_CODE%
)

endlocal
exit /b 0
