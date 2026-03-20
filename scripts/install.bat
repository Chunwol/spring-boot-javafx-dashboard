@echo off
:: ============================================================
:: PC 관제 에이전트 설치 스크립트 (좀비 모드)
:: - Windows 작업 스케줄러(Task Scheduler) + PowerShell 워치독 사용
:: - 부팅 시 SYSTEM 계정으로 자동 실행
:: - 충돌/종료 시 5초 후 자동 재시작
:: 관리자 권한으로 실행하세요.
:: ============================================================
setlocal enabledelayedexpansion

set INSTALL_DIR=C:\Program Files\PCAgent
set OLD_SERVICE=PCAgent
set TASK_PATH=\PCAgent\
set TASK_NAME=Main
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
echo  PC 관제 에이전트 설치 시작 (좀비 모드)
echo  - 부팅 시 자동 실행 / 충돌 시 자동 재시작
echo ================================================================

:: ── 설치 폴더 생성 ────────────────────────────────────────────
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
echo [OK] 설치 경로: %INSTALL_DIR%

:: ── 에이전트 파일 복사 ─────────────────────────────────────────
if exist "%~dp0%JAR_NAME%" (
    xcopy /Y "%~dp0%JAR_NAME%" "%INSTALL_DIR%\" >nul
    echo [OK] %JAR_NAME% 복사 완료
) else if exist "%~dp0%AGENT_EXE%" (
    xcopy /Y "%~dp0%AGENT_EXE%" "%INSTALL_DIR%\" >nul
    echo [OK] %AGENT_EXE% 복사 완료
) else (
    echo [오류] agent.jar 또는 agent.exe 파일을 찾을 수 없습니다.
    pause
    exit /b 1
)

:: ── 워치독 스크립트 복사 ──────────────────────────────────────
if exist "%~dp0agent-watchdog.ps1" (
    xcopy /Y "%~dp0agent-watchdog.ps1" "%INSTALL_DIR%\" >nul
    echo [OK] agent-watchdog.ps1 복사 완료
) else (
    echo [오류] agent-watchdog.ps1 파일을 찾을 수 없습니다.
    pause
    exit /b 1
)

:: ── config.json 복사 (기존 토큰 보존) ────────────────────────
if exist "%~dp0config.json" (
    if exist "%INSTALL_DIR%\config.json" (
        echo [INFO] 기존 config.json 유지 (토큰 보존)
    ) else (
        xcopy /Y "%~dp0config.json" "%INSTALL_DIR%\" >nul
        echo [OK] config.json 복사 완료
    )
) else (
    if not exist "%INSTALL_DIR%\config.json" (
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
)

:: ── 구 버전 Windows 서비스 제거 (이전 설치 방식 정리) ──────────
sc query "%OLD_SERVICE%" >nul 2>&1
if %errorLevel% equ 0 (
    echo [INFO] 구 버전 Windows 서비스 제거 중...
    sc stop "%OLD_SERVICE%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%OLD_SERVICE%" >nul 2>&1
    echo [OK] 구 버전 서비스 제거 완료
)

:: ── 작업 스케줄러 등록 (PowerShell 임시 스크립트 방식) ─────────
echo [INFO] 작업 스케줄러 등록 중...

set TMPPS=%TEMP%\pcagent-install-%RANDOM%.ps1
(
echo $ErrorActionPreference = 'Stop'
echo $installDir = '%INSTALL_DIR%'
echo $watchdogScript = Join-Path $installDir 'agent-watchdog.ps1'
echo.
echo # 기존 작업 제거
echo try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {}
echo try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
echo try { Unregister-ScheduledTask -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
echo.
echo $action = New-ScheduledTaskAction `
echo     -Execute 'powershell.exe' `
echo     -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $watchdogScript + '" -InstallDir "' + $installDir + '"') `
echo     -WorkingDirectory $installDir
echo.
echo $trigger = New-ScheduledTaskTrigger -AtStartup
echo.
echo $settings = New-ScheduledTaskSettingsSet `
echo     -ExecutionTimeLimit ([TimeSpan]::Zero) `
echo     -MultipleInstances IgnoreNew `
echo     -Hidden `
echo     -RestartCount 10 `
echo     -RestartInterval (New-TimeSpan -Minutes 1)
echo.
echo $principal = New-ScheduledTaskPrincipal `
echo     -UserId 'SYSTEM' `
echo     -LogonType ServiceAccount `
echo     -RunLevel Highest
echo.
echo $null = Register-ScheduledTask `
echo     -TaskPath '\PCAgent\' `
echo     -TaskName 'Main' `
echo     -Action $action `
echo     -Trigger $trigger `
echo     -Settings $settings `
echo     -Principal $principal `
echo     -Force
echo.
echo Write-Host '[OK] 작업 스케줄러 등록 완료: \PCAgent\Main'
) > "%TMPPS%"

powershell -NoProfile -ExecutionPolicy Bypass -File "%TMPPS%"
set PS_EXIT=%errorLevel%
del "%TMPPS%" >nul 2>&1

if %PS_EXIT% neq 0 (
    echo [오류] 작업 스케줄러 등록 실패
    pause
    exit /b 1
)

:: ── 방화벽 규칙 추가 ──────────────────────────────────────────
netsh advfirewall firewall show rule name="PCAgent Outbound" >nul 2>&1
if %errorLevel% neq 0 (
    netsh advfirewall firewall add rule name="PCAgent Outbound" dir=out action=allow protocol=TCP remoteport=8080 >nul
    echo [OK] 방화벽 아웃바운드 규칙 추가 (TCP 8080)
) else (
    echo [INFO] 방화벽 규칙 이미 존재
)

:: ── 에이전트 즉시 시작 ────────────────────────────────────────
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Start-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main'; Write-Host '[OK] 에이전트 시작됨' } catch { Write-Host '[경고] 즉시 시작 실패 (재부팅 후 자동 시작됩니다): ' + $_.Exception.Message }"

echo.
echo ================================================================
echo  설치 완료!
echo  설치 경로:   %INSTALL_DIR%
echo  자동 시작:   부팅 시 SYSTEM 계정으로 자동 실행
echo  자동 재시작: 충돌/종료 시 5초 후 자동 재시작 (좀비 모드)
echo  로그 파일:   %INSTALL_DIR%\watchdog.log
echo.
echo  [ 중요 ] %INSTALL_DIR%\config.json 에서
echo  serverUrl 과 installKey 를 수정하세요.
echo.
echo  작업 관리자 확인:
echo    작업 스케줄러 라이브러리 → PCAgent → Main
echo ================================================================
pause
endlocal
