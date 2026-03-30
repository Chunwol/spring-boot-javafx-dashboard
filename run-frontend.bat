@echo off
REM ===================================
REM FRONTEND CLIENT STARTUP SCRIPT
REM Windows Batch File
REM ===================================

setlocal enabledelayedexpansion

REM 현재 디렉토리
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

REM .env 파일 확인
if not exist "frontend.env" (
    if not exist "frontend\.env" (
        echo [ERROR] frontend.env file not found!
        echo 다음 경로에 frontend.env 또는 frontend\.env 파일이 있어야 합니다:
        echo   1. %SCRIPT_DIR%frontend.env
        echo   2. %SCRIPT_DIR%frontend\.env
        pause
        exit /b 1
    )
)

echo.
echo ===================================
echo   CLASSROOM DASHBOARD - FRONTEND
echo ===================================
echo.
echo Workspace: %SCRIPT_DIR%
echo.

REM JAR 파일 확인 (shadowJar로 생성된 fat JAR)
set JAR_FILE=frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar

if not exist "%JAR_FILE%" (
    echo [WARNING] Frontend JAR file not found: %JAR_FILE%
    echo Build를 수행합니다...
    echo.
    call gradlew.bat :frontend:shadowJar -q
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

REM 기본 서버 정보 읽기 (frontend.env 파일에서)
setlocal enabledelayedexpansion
for /f "delims==" %%A in (
    'findstr /i "API_SERVER_HOST API_SERVER_PORT" frontend.env 2^>nul || findstr /i "API_SERVER_HOST API_SERVER_PORT" frontend\.env 2^>nul'
) do (
    set "line=%%A"
    for /f "delims== tokens=1,2" %%B in ("!line!=") do (
        if "%%B"=="API_SERVER_HOST" set API_HOST=%%C
        if "%%B"=="API_SERVER_PORT" set API_PORT=%%C
    )
)
setlocal disableDelayedExpansion

REM 기본값 설정
if "!API_HOST!"=="" set API_HOST=localhost
if "!API_PORT!"=="" set API_PORT=8888

echo Connecting to: http://%API_HOST%:%API_PORT%
echo [INFO] Starting frontend client...
echo.

REM Frontend 실행
java -jar "%JAR_FILE%"

if errorlevel 1 (
    echo [ERROR] Frontend startup failed!
    pause
    exit /b 1
)

endlocal
