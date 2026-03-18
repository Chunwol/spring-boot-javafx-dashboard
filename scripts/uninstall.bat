@echo off
:: ============================================================
:: PC 관제 에이전트 제거 스크립트
:: 관리자 권한으로 실행하세요.
:: ============================================================
setlocal

set INSTALL_DIR=C:\Program Files\PCAgent
set SERVICE_NAME=PCAgent

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [오류] 관리자 권한이 필요합니다.
    pause
    exit /b 1
)

echo PC 관제 에이전트를 제거하시겠습니까? (Y/N)
set /p CONFIRM=
if /i "%CONFIRM%" neq "Y" (
    echo 제거가 취소되었습니다.
    pause
    exit /b 0
)

:: 서비스 중지 및 제거
sc query "%SERVICE_NAME%" >nul 2>&1
if %errorLevel% equ 0 (
    sc stop "%SERVICE_NAME%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%SERVICE_NAME%"
    echo [OK] 서비스 제거 완료
) else (
    echo [INFO] 서비스가 존재하지 않습니다.
)

:: 방화벽 규칙 제거
netsh advfirewall firewall delete rule name="PCAgent Outbound" >nul 2>&1
echo [OK] 방화벽 규칙 제거 완료

:: 설치 폴더 제거
if exist "%INSTALL_DIR%" (
    rmdir /S /Q "%INSTALL_DIR%"
    echo [OK] 설치 폴더 제거: %INSTALL_DIR%
)

echo 에이전트 제거 완료.
pause
endlocal
