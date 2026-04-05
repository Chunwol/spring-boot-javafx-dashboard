@echo off
REM 백엔드 JAR 빌드

setlocal
cd /d "%~dp0.."

echo ==================================
echo   BUILD BACKEND
echo ==================================
echo.

call gradlew.bat :backend:build -x test
if errorlevel 1 (
    echo [ERROR] Backend build failed.
    exit /b 1
)

echo.
echo [OK] Backend build completed.
echo Output: backend\build\libs
endlocal
exit /b 0