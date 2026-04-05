@echo off
REM 백엔드 개발 서버 실행 (Gradle bootRun)

setlocal
cd /d "%~dp0.."

echo ==================================
echo   RUN BACKEND
echo ==================================
echo.

call gradlew.bat :backend:bootRun
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [ERROR] Backend run failed.
    exit /b %EXIT_CODE%
)

endlocal
exit /b 0