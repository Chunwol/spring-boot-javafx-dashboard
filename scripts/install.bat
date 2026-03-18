@echo off
:: ============================================================
:: PC 관제 에이전트 설치 스크립트
:: 관리자 권한으로 실행하세요.
:: ============================================================
setlocal enabledelayedexpansion

set INSTALL_DIR=C:\Program Files\PCAgent
set SERVICE_NAME=PCAgent
set JAR_NAME=agent.jar
set AGENT_EXE=agent.exe

:: ── 관리자 권한 확인 ──────────────────────────────────────────
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [오류] 이 스크립트는 관리자 권한으로 실행해야 합니다.
    echo 마우스 우클릭 → "관리자 권한으로 실행" 을 선택하세요.
    pause
    exit /b 1
)

echo ================================================================
echo  PC 관제 에이전트 설치 시작
echo ================================================================

:: ── 설치 폴더 생성 ────────────────────────────────────────────
if not exist "%INSTALL_DIR%" (
    mkdir "%INSTALL_DIR%"
    echo [OK] 설치 폴더 생성: %INSTALL_DIR%
) else (
    echo [INFO] 설치 폴더 이미 존재: %INSTALL_DIR%
)

:: ── 파일 복사 ──────────────────────────────────────────────────
:: agent.jar 또는 agent.exe 복사
if exist "%~dp0%JAR_NAME%" (
    xcopy /Y "%~dp0%JAR_NAME%" "%INSTALL_DIR%\" >nul
    echo [OK] %JAR_NAME% 복사 완료
    set AGENT_FILE=%INSTALL_DIR%\%JAR_NAME%
    set BIN_PATH=java -jar "%INSTALL_DIR%\%JAR_NAME%"
) else if exist "%~dp0%AGENT_EXE%" (
    xcopy /Y "%~dp0%AGENT_EXE%" "%INSTALL_DIR%\" >nul
    echo [OK] %AGENT_EXE% 복사 완료
    set AGENT_FILE=%INSTALL_DIR%\%AGENT_EXE%
    set BIN_PATH="%INSTALL_DIR%\%AGENT_EXE%"
) else (
    echo [오류] agent.jar 또는 agent.exe 파일을 찾을 수 없습니다.
    pause
    exit /b 1
)

:: config.json 복사
if exist "%~dp0config.json" (
    :: 기존 토큰이 있으면 config.json 덮어쓰기 스킵
    if exist "%INSTALL_DIR%\config.json" (
        echo [INFO] 기존 config.json 유지 (토큰 보존)
    ) else (
        xcopy /Y "%~dp0config.json" "%INSTALL_DIR%\" >nul
        echo [OK] config.json 복사 완료
    )
) else (
    echo [경고] config.json 파일이 없습니다. 기본 설정으로 생성합니다.
    (
        echo {
        echo   "serverUrl": "http://192.168.0.1:8080",
        echo   "installKey": "CHANGE_ME_INSTALL_KEY",
        echo   "agentToken": "",
        echo   "heartbeatIntervalSec": 120,
        echo   "jitterSec": 30,
        echo   "githubOwner": "",
        echo   "githubRepo": ""
        echo }
    ) > "%INSTALL_DIR%\config.json"
    echo [OK] 기본 config.json 생성 완료
)

:: ── 기존 서비스 제거 ──────────────────────────────────────────
sc query "%SERVICE_NAME%" >nul 2>&1
if %errorLevel% equ 0 (
    echo [INFO] 기존 서비스 중지 및 제거 중...
    sc stop "%SERVICE_NAME%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%SERVICE_NAME%" >nul 2>&1
    echo [OK] 기존 서비스 제거 완료
)

:: ── 서비스 등록 ────────────────────────────────────────────────
sc create "%SERVICE_NAME%" binPath= "%BIN_PATH%" DisplayName= "PC Management Agent" start= auto >nul
if %errorLevel% neq 0 (
    echo [오류] 서비스 등록 실패
    pause
    exit /b 1
)
echo [OK] 서비스 등록 완료

:: 서비스 설명 추가
sc description "%SERVICE_NAME%" "강의실 PC 관제 에이전트 - 하트비트 전송 및 원격 명령 실행" >nul

:: ── 방화벽 규칙 추가 ──────────────────────────────────────────
netsh advfirewall firewall show rule name="PCAgent Outbound" >nul 2>&1
if %errorLevel% neq 0 (
    netsh advfirewall firewall add rule name="PCAgent Outbound" dir=out action=allow protocol=TCP remoteport=8080 >nul
    echo [OK] 방화벽 아웃바운드 규칙 추가 (TCP 8080)
) else (
    echo [INFO] 방화벽 규칙 이미 존재
)

:: ── 서비스 시작 ────────────────────────────────────────────────
sc start "%SERVICE_NAME%" >nul
if %errorLevel% neq 0 (
    echo [경고] 서비스 시작 실패. 수동으로 시작하세요: sc start %SERVICE_NAME%
) else (
    echo [OK] 서비스 시작됨
)

echo.
echo ================================================================
echo  설치 완료!
echo  설치 경로: %INSTALL_DIR%
echo  서비스명:  %SERVICE_NAME%
echo.
echo  설정 파일(%INSTALL_DIR%\config.json)에서
echo  serverUrl 과 installKey 를 확인하세요.
echo ================================================================
pause
endlocal
