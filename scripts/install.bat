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
set APPLY_POWER_POLICY=1

if /I "%~1"=="--skip-power-policy" (
    set APPLY_POWER_POLICY=0
)

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

:: ── config.json 병합 업데이트 (agentToken 보존)
:: 기본 생성 시 환경변수 AGENT_SERVER_URL 우선 사용, 없으면 http 기본값을 사용
if not exist "%~dp0config.json" (
    if not exist "%INSTALL_DIR%\config.json" (
        rem Allow AGENT_SERVER_URL to override default server URL
        set "DEFAULT_SERVER_URL=http://localhost:8888"
        if defined AGENT_SERVER_URL set "DEFAULT_SERVER_URL=%AGENT_SERVER_URL%"
        (
            echo {
            echo   "serverUrl": "!DEFAULT_SERVER_URL!",
            echo   "installKey": "CHANGE_ME_INSTALL_KEY",
            echo   "agentToken": "",
            echo   "heartbeatIntervalSec": 120,
            echo   "jitterSec": 30
            echo }
        ) > "%INSTALL_DIR%\config.json"
        echo [OK] 기본 config.json 생성 완료
    )
) else (
    set TMPCFGPS=%TEMP%\pcagent-config-merge-%RANDOM%.ps1
    (
    echo $ErrorActionPreference = 'Stop'
    echo $srcPath = '%~dp0config.json'
    echo $dstPath = '%INSTALL_DIR%\config.json'
    echo $src = Get-Content $srcPath -Raw ^| ConvertFrom-Json
    echo if ^(Test-Path $dstPath^) {
    echo     try { $dst = Get-Content $dstPath -Raw ^| ConvertFrom-Json } catch { $dst = [pscustomobject]@{} }
    echo } else {
    echo     $dst = [pscustomobject]@{}
    echo }
    echo $token = if ^($dst.PSObject.Properties.Name -contains 'agentToken'^) { [string]$dst.agentToken } else { '' }
    echo $defaultServerUrl = if ^($env:AGENT_SERVER_URL -and $env:AGENT_SERVER_URL.Trim() -ne ''^) { $env:AGENT_SERVER_URL } else { 'http://localhost:8888' }
    echo $merged = [ordered]@{}
    echo $merged.serverUrl = if ^($src.PSObject.Properties.Name -contains 'serverUrl'^) { [string]$src.serverUrl } else { $defaultServerUrl }
    echo $merged.installKey = if ^($src.PSObject.Properties.Name -contains 'installKey'^) { [string]$src.installKey } else { '' }
    echo $merged.agentToken = $token
    echo $merged.heartbeatIntervalSec = if ^($src.PSObject.Properties.Name -contains 'heartbeatIntervalSec'^) { [int]$src.heartbeatIntervalSec } else { 120 }
    echo $merged.jitterSec = if ^($src.PSObject.Properties.Name -contains 'jitterSec'^) { [int]$src.jitterSec } else { 30 }
    echo $merged ^| ConvertTo-Json -Depth 8 ^| Set-Content $dstPath -Encoding UTF8
    echo Write-Host '[OK] config.json 병합 완료 (agentToken 보존)'
    ) > "%TMPCFGPS%"
    powershell -NoProfile -ExecutionPolicy Bypass -File "%TMPCFGPS%"
    set CFG_EXIT=%errorLevel%
    del "%TMPCFGPS%" >nul 2>&1
    if %CFG_EXIT% neq 0 (
        echo [오류] config.json 병합 실패
        pause
        exit /b 1
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

:: ── 서비스 설치: NSSM 사용 가능하면 NSSM으로 서비스 등록, 없으면 작업 스케줄러
echo [INFO] 서비스/작업 스케줄러 설치 선택 중...

:: 명령행 플래그로 NSSM 사용 강제: install.bat --use-nssm
set USE_NSSM=0
if /I "%~1"=="--use-nssm" set USE_NSSM=1

:: NSSM 검색: 스크립트 폴더 또는 PATH
set "NSSM_PATH="
if exist "%~dp0nssm.exe" set "NSSM_PATH=%~dp0nssm.exe"
if not defined NSSM_PATH (
    for /f "usebackq tokens=*" %%A in (`where nssm 2^>nul`) do set "NSSM_PATH=%%~A"
)
if defined NSSM_PATH set USE_NSSM=1

if "%USE_NSSM%"=="1" (
    echo [INFO] NSSM 사용: %NSSM_PATH%
    :: Java 실행 파일 탐색
    set "JAVA_EXE="
    if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    if "%JAVA_EXE%"=="" (
        for /f "usebackq tokens=*" %%J in (`where java 2^>nul`) do set "JAVA_EXE=%%~J"
    )
    if "%JAVA_EXE%"=="" (
        echo [WARN] java.exe를 찾을 수 없습니다. NSSM 서비스 등록은 실패할 수 있습니다.
    )

    echo [INFO] NSSM으로 서비스 생성 시도...
    "%NSSM_PATH%" install PCAgent "%JAVA_EXE%" "-Dpcagent.config.dir=%INSTALL_DIR% -jar %INSTALL_DIR%\%JAR_NAME%" >nul 2>&1 || echo [WARN] NSSM 설치 실패
    "%NSSM_PATH%" set PCAgent DisplayName "PC Agent" >nul 2>&1
    "%NSSM_PATH%" set PCAgent Start SERVICE_AUTO_START >nul 2>&1
    "%NSSM_PATH%" start PCAgent >nul 2>&1 || echo [WARN] NSSM 서비스 시작 실패
    echo [OK] NSSM 서비스 등록/시작 시도 완료
) else (
    :: 기존: PowerShell Scheduled Task 방식
    echo [INFO] 작업 스케줄러 등록 중...

    set TMPPS=%TEMP%\pcagent-install-%RANDOM%.ps1
    (
    echo $ErrorActionPreference = 'Stop'
    echo $installDir = '%INSTALL_DIR%'
    echo $watchdogScript = Join-Path $installDir 'agent-watchdog.ps1'
    echo.
    echo # 기존 작업 제거
    echo try { Stop-ScheduledTask -TaskPath '%TASK_PATH%' -TaskName '%TASK_NAME%' -ErrorAction SilentlyContinue } catch {}
    echo try { Unregister-ScheduledTask -TaskPath '%TASK_PATH%' -TaskName '%TASK_NAME%' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
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
    echo     -TaskPath '%TASK_PATH%' `
    echo     -TaskName '%TASK_NAME%' `
    echo     -Action $action `
    echo     -Trigger $trigger `
    echo     -Settings $settings `
    echo     -Principal $principal `
    echo     -Force
    echo.
    echo Write-Host '[OK] 작업 스케줄러 등록 완료: %TASK_PATH%%TASK_NAME%'
    ) > "%TMPPS%"

    powershell -NoProfile -ExecutionPolicy Bypass -File "%TMPPS%"
    set PS_EXIT=%errorLevel%
    del "%TMPPS%" >nul 2>&1

    if %PS_EXIT% neq 0 (
        echo [오류] 작업 스케줄러 등록 실패
        pause
        exit /b 1
    )
)

:: ── 방화벽 규칙 추가 ──────────────────────────────────────────
netsh advfirewall firewall show rule name="PCAgent Outbound" >nul 2>&1
if %errorLevel% neq 0 (
    netsh advfirewall firewall add rule name="PCAgent Outbound" dir=out action=allow protocol=TCP remoteport=8080 >nul
    echo [OK] 방화벽 아웃바운드 규칙 추가 (TCP 8080)
) else (
    echo [INFO] 방화벽 규칙 이미 존재
)

:: ── 전원 정책 적용 (옵션) ─────────────────────────────────────
if "%APPLY_POWER_POLICY%"=="1" (
    echo [INFO] 전원 정책 적용 중... (절전 비활성화 / 버튼=종료)

    :: 절전/최대절전 타이머 비활성화
    powercfg -change -standby-timeout-ac 0 >nul 2>&1
    powercfg -change -standby-timeout-dc 0 >nul 2>&1
    powercfg -change -hibernate-timeout-ac 0 >nul 2>&1
    powercfg -change -hibernate-timeout-dc 0 >nul 2>&1

    :: 최대절전 기능 끔 (hiberfil.sys 제거)
    powercfg -hibernate off >nul 2>&1

    :: 전원 버튼 / 절전 버튼 동작을 '종료(3)'로 설정 (AC/DC 모두)
    powercfg -setacvalueindex scheme_current sub_buttons pbuttonaction 3 >nul 2>&1
    powercfg -setdcvalueindex scheme_current sub_buttons pbuttonaction 3 >nul 2>&1
    powercfg -setacvalueindex scheme_current sub_buttons sbuttonaction 3 >nul 2>&1
    powercfg -setdcvalueindex scheme_current sub_buttons sbuttonaction 3 >nul 2>&1

    :: 변경사항 활성화
    powercfg -setactive scheme_current >nul 2>&1
    echo [OK] 전원 정책 적용 완료
) else (
    echo [INFO] 전원 정책 적용 건너뜀 (--skip-power-policy)
)

:: ── 에이전트 즉시 시작 ────────────────────────────────────────
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Start-ScheduledTask -TaskPath '%TASK_PATH%' -TaskName '%TASK_NAME%'; Write-Host '[OK] 에이전트 시작됨' } catch { Write-Host '[경고] 즉시 시작 실패 (재부팅 후 자동 시작됩니다): ' + $_.Exception.Message }"

echo.
echo ================================================================
echo  설치 완료!
echo  설치 경로:   %INSTALL_DIR%
echo  자동 시작:   부팅 시 SYSTEM 계정으로 자동 실행
echo  자동 재시작: 충돌/종료 시 5초 후 자동 재시작 (좀비 모드)
if "%APPLY_POWER_POLICY%"=="1" (
echo  전원 정책:   절전 비활성화 / 전원·절전 버튼=종료
) else (
echo  전원 정책:   변경 안 함 (--skip-power-policy)
)
echo  로그 파일:   %INSTALL_DIR%\watchdog.log
echo.
echo  [ 중요 ] %INSTALL_DIR%\config.json 에서
echo  serverUrl 과 installKey 를 수정하세요.
echo.
echo  작업 관리자 확인:
echo    작업 스케줄러 라이브러리 → %TASK_PATH% %TASK_NAME%
echo ================================================================
pause
endlocal
