@echo off
REM ===================================
REM BACKEND SERVER STARTUP SCRIPT
REM Windows Batch File
REM ===================================

setlocal enabledelayedexpansion

REM 현재 디렉토리
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

REM 포트 설정 (기본값: 8888)
set SERVER_PORT=8888

REM .env 파일 확인
if not exist "backend.env" (
    if not exist "backend\.env" (
        echo [ERROR] backend.env file not found!
        echo 다음 경로에 backend.env 또는 backend\.env 파일이 있어야 합니다:
        echo   1. %SCRIPT_DIR%backend.env
        echo   2. %SCRIPT_DIR%backend\.env
        pause
        exit /b 1
    )
)

echo.
echo ===================================
echo   CLASSROOM DASHBOARD - BACKEND
echo ===================================
echo.
echo Workspace: %SCRIPT_DIR%
echo.

REM JAR 파일 확인
set JAR_FILE=backend\build\libs\backend-0.0.1-SNAPSHOT.jar

if not exist "%JAR_FILE%" (
    echo [WARNING] JAR file not found: %JAR_FILE%
    echo Build를 수행합니다...
    echo.
    call gradlew.bat :backend:build -q
    if errorlevel 1 (
        echo [ERROR] Build failed!
        pause
        exit /b 1
    )
    echo [OK] Build completed!
    echo.
)

REM Java 버전 확인
for /f "tokens=3" %%a in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION=%%a
)

echo Java Version: %JAVA_VERSION%

REM 서버 실행
echo [INFO] Starting backend server on port %SERVER_PORT%...
echo.

java -jar "%JAR_FILE%"

if errorlevel 1 (
    echo [ERROR] Server startup failed!
    pause
    exit /b 1
)

endlocal
