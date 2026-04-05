@echo off
REM 프론트엔드 개발 실행 (Gradle run)

setlocal EnableDelayedExpansion
cd /d "%~dp0.."

echo ==================================
echo   RUN FRONTEND DEV
echo ==================================
echo.

set API_HOST=localhost
set API_PORT=8888

if exist "frontend.env" (
    for /f "usebackq tokens=1,2 delims==" %%A in ("frontend.env") do (
        if "%%A"=="API_SERVER_HOST" set API_HOST=%%B
        if "%%A"=="API_SERVER_PORT" set API_PORT=%%B
    )
)

echo Connecting to: http://!API_HOST!:!API_PORT!
echo.

call gradlew.bat :frontend:run
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Frontend dev run failed.
    exit /b %EXIT_CODE%
)

endlocal
exit /b 0