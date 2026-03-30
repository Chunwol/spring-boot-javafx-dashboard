@echo off
REM .env를 읽어 에이전트 실행 환경을 구성합니다.

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

if defined AGENT_SERVER_URL set "AGENT_SERVER_URL=%AGENT_SERVER_URL%"
if defined AGENT_INSTALL_KEY set "AGENT_INSTALL_KEY=%AGENT_INSTALL_KEY%"
if defined AGENT_TOKEN set "AGENT_TOKEN=%AGENT_TOKEN%"
if defined AGENT_HEARTBEAT_INTERVAL_SEC set "AGENT_HEARTBEAT_INTERVAL_SEC=%AGENT_HEARTBEAT_INTERVAL_SEC%"
if defined AGENT_JITTER_SEC set "AGENT_JITTER_SEC=%AGENT_JITTER_SEC%"

set "AGENT_JAR=agent\build\libs\agent-1.0.0.jar"

if not exist "%AGENT_JAR%" (
    echo Agent JAR가 없습니다: %AGENT_JAR%
    echo 먼저 gradlew.bat :agent:build -x test 를 실행하세요.
    exit /b 1
)

java -jar "%AGENT_JAR%"
exit /b 0