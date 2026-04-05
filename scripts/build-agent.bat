@echo off
REM 에이전트 JAR 빌드

setlocal
cd /d "%~dp0.."

echo ==================================
echo   BUILD AGENT
echo ==================================
echo.

call gradlew.bat :agent:build -x test
if errorlevel 1 (
    echo [ERROR] Agent build failed.
    exit /b 1
)

echo.
echo [OK] Agent build completed.
echo Output: agent\build\libs
endlocal
exit /b 0