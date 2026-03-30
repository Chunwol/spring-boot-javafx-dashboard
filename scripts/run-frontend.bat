@echo off
REM .env를 읽어 프론트 EXE 실행 환경을 구성합니다.

setlocal EnableDelayedExpansion

cd /d "%~dp0.."

if exist ".env" (
    for /f "usebackq tokens=1* delims==" %%A in (".env") do (
        set "KEY=%%A"
        set "VAL=%%B"
        if not "!KEY!"=="" (
            if /i not "!KEY:~0,1!"=="#" (
                set "!KEY!=!VAL!"
            )
        )
    )
)

if defined FRONTEND_API_BASE_URL set "FRONTEND_API_BASE_URL=%FRONTEND_API_BASE_URL%"
if defined FRONTEND_WEBSOCKET_STOMP_URL set "FRONTEND_WEBSOCKET_STOMP_URL=%FRONTEND_WEBSOCKET_STOMP_URL%"
if defined FRONTEND_DEFAULT_LOGIN_HOST set "FRONTEND_DEFAULT_LOGIN_HOST=%FRONTEND_DEFAULT_LOGIN_HOST%"
if defined FRONTEND_DEFAULT_LOGIN_PORT set "FRONTEND_DEFAULT_LOGIN_PORT=%FRONTEND_DEFAULT_LOGIN_PORT%"

set "FRONTEND_EXE=frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe"

if not exist "%FRONTEND_EXE%" (
    echo Frontend EXE가 없습니다: %FRONTEND_EXE%
    echo 먼저 scripts\build-all.bat 또는 gradlew.bat :frontend:packageExe 를 실행하세요.
    exit /b 1
)

start "ClassroomDashboard" "%FRONTEND_EXE%"
exit /b 0