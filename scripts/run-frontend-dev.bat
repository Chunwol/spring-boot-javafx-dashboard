@echo off
REM Frontend Only - Windows (Development)
REM Usage: run-frontend-dev.bat

setlocal EnableDelayedExpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Frontend Only (Development Mode)
echo ============================================================
echo Make sure Backend is running on http://localhost:8888
echo.

set API_HOST=localhost
set API_PORT=8888

REM Read from frontend.env if exists
if exist "frontend\.env" (
    for /f "usebackq tokens=1,2 delims==" %%A in ("frontend\.env") do (
        if "%%A"=="API_SERVER_HOST" set API_HOST=%%B
        if "%%A"=="API_SERVER_PORT" set API_PORT=%%B
    )
)

echo Connecting to: http://!API_HOST!:!API_PORT!
echo Press Ctrl+C to stop
echo.

call gradlew.bat :frontend:run
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo Frontend run failed with code %EXIT_CODE%
    exit /b %EXIT_CODE%
)

endlocal
exit /b 0
