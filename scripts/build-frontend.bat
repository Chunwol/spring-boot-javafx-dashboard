@echo off
REM 프론트엔드 EXE 패키지 빌드

setlocal
cd /d "%~dp0.."

echo ==================================
echo   BUILD FRONTEND
echo ==================================
echo.

call gradlew.bat :frontend:packageExe -x test
if errorlevel 1 (
    echo [ERROR] Frontend build failed.
    exit /b 1
)

echo.
echo [OK] Frontend build completed.
echo Output: frontend\build\package\ClassroomDashboard
endlocal
exit /b 0