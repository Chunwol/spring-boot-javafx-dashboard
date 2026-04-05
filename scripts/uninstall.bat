@echo off
:: ============================================================
:: PC 관제 에이전트 제거 스크립트 (완전 원복)
:: - 작업 스케줄러 작업 제거
:: - 구 버전 Windows 서비스 제거
:: - 워치독(PowerShell) + 에이전트(java/exe) 프로세스 종료
:: - 방화벽 규칙 제거
:: - 설치 폴더 제거 (C:\Program Files\PCAgent)
:: - AppData 폴더 제거 (%APPDATA%\PCAgent  — agentToken 저장 위치)
:: - 전원 정책 원복 (절전/최대절전 타이머 복원, hibernate 재활성화,
;;                   전원/절전 버튼 동작 기본값 복원)
:: 관리자 권한으로 실행하세요.
:: ============================================================
setlocal enabledelayedexpansion

set INSTALL_DIR=C:\Program Files\PCAgent
set OLD_SERVICE=PCAgent
set TASK_PATH=\PCAgent\
set TASK_NAME=Main

:: ── 관리자 권한 확인 ──────────────────────────────────────────
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [오류] 이 스크립트는 관리자 권한으로 실행해야 합니다.
    echo 마우스 우클릭 → "관리자 권한으로 실행" 을 선택하세요.
    pause
    exit /b 1
)

echo ================================================================
echo  PC 관제 에이전트 완전 제거 (원복)
echo  설치 폴더 : %INSTALL_DIR%
echo  AppData   : %APPDATA%\PCAgent
echo  전원 정책 : 설치 시 변경 내용 원복
echo ================================================================
echo.
echo 계속 진행하면 위 항목이 모두 삭제/복원됩니다.
echo PC 관제 에이전트를 완전히 제거하시겠습니까? (Y/N)
set /p CONFIRM=
if /i "%CONFIRM%" neq "Y" (
    echo 제거가 취소되었습니다.
    pause
    exit /b 0
)
echo.

:: ══════════════════════════════════════════════════════════════
:: 1. 작업 스케줄러 작업 중지 및 제거
:: ══════════════════════════════════════════════════════════════
echo [단계 1/7] 작업 스케줄러 작업 제거 중...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {};" ^
    "Start-Sleep -Seconds 2;" ^
    "try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {};" ^
    "try { Unregister-ScheduledTask -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {};" ^
    "Write-Host '[OK] 작업 스케줄러 작업 제거 완료'"

:: ══════════════════════════════════════════════════════════════
:: 2. 구 버전 Windows 서비스 제거
:: ══════════════════════════════════════════════════════════════
echo [단계 2/7] Windows 서비스 확인 중...
sc query "%OLD_SERVICE%" >nul 2>&1
if %errorLevel% equ 0 (
    sc stop "%OLD_SERVICE%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%OLD_SERVICE%" >nul 2>&1
    echo [OK] Windows 서비스 제거 완료
) else (
    echo [INFO] Windows 서비스 없음 (건너뜀)
)

:: ══════════════════════════════════════════════════════════════
:: 3. 프로세스 종료 (워치독 → 에이전트 순서)
:: ══════════════════════════════════════════════════════════════
echo [단계 3/7] 실행 중인 프로세스 종료 중...

:: 3-a. 워치독(PowerShell) 프로세스 종료
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'powershell.exe' -and $_.CommandLine -like '*agent-watchdog*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue };" ^
    "Write-Host '[OK] 워치독 프로세스 종료 요청 완료'"

timeout /T 3 /NOBREAK >nul

:: 3-b. 에이전트(java / agent.exe) 프로세스 종료
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'java.exe' -or $_.Name -eq 'agent.exe') -and $_.CommandLine -like '*PCAgent*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue };" ^
    "Write-Host '[OK] 에이전트 프로세스 종료 요청 완료'"

:: 3-c. 최대 15초 대기 후 잔존 프로세스 확인
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$deadline = (Get-Date).AddSeconds(15);" ^
    "while ((Get-Date) -lt $deadline) {" ^
    "  $alive = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*PCAgent*' };" ^
    "  if (-not $alive) { break }; Start-Sleep -Seconds 1" ^
    "};" ^
    "$remain = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*PCAgent*' };" ^
    "if ($remain) { Write-Host '[경고] 잔존 프로세스 있음 — 수동 종료 필요:'; $remain | Select-Object ProcessId, Name, CommandLine | Format-List } else { Write-Host '[OK] 모든 에이전트 프로세스 종료됨' }"

:: ══════════════════════════════════════════════════════════════
:: 4. 방화벽 규칙 제거
:: ══════════════════════════════════════════════════════════════
echo [단계 4/7] 방화벽 규칙 제거 중...
netsh advfirewall firewall delete rule name="PCAgent Outbound" >nul 2>&1
echo [OK] 방화벽 규칙 제거 완료 (없으면 무시)

:: ══════════════════════════════════════════════════════════════
:: 5. 설치 폴더 제거 (C:\Program Files\PCAgent)
:: ══════════════════════════════════════════════════════════════
echo [단계 5/7] 설치 폴더 제거 중: %INSTALL_DIR%
timeout /T 2 /NOBREAK >nul
if exist "%INSTALL_DIR%" (
    set DELETED=0
    for /L %%I in (1,1,5) do (
        if "!DELETED!"=="0" (
            rmdir /S /Q "%INSTALL_DIR%" >nul 2>&1
            if not exist "%INSTALL_DIR%" set DELETED=1
            if "!DELETED!"=="0" timeout /T 2 /NOBREAK >nul
        )
    )
    if "!DELETED!"=="1" (
        echo [OK] 설치 폴더 제거 완료: %INSTALL_DIR%
    ) else (
        echo [경고] 설치 폴더 일부 잠금 — 수동 삭제 필요: %INSTALL_DIR%
    )
) else (
    echo [INFO] 설치 폴더 없음 (건너뜀)
)

:: ══════════════════════════════════════════════════════════════
:: 6. AppData 폴더 제거 (%APPDATA%\PCAgent — agentToken 저장)
:: ══════════════════════════════════════════════════════════════
echo [단계 6/7] AppData 폴더 제거 중: %APPDATA%\PCAgent
if exist "%APPDATA%\PCAgent" (
    rmdir /S /Q "%APPDATA%\PCAgent" >nul 2>&1
    if not exist "%APPDATA%\PCAgent" (
        echo [OK] AppData 폴더 제거 완료: %APPDATA%\PCAgent
    ) else (
        echo [경고] AppData 폴더 제거 실패 — 수동 삭제 필요: %APPDATA%\PCAgent
    )
) else (
    echo [INFO] AppData 폴더 없음 (건너뜀)
)

:: ══════════════════════════════════════════════════════════════
:: 7. 전원 정책 원복
::    설치 시 변경 내용:
;;      - 절전 타이머 AC/DC → 0 (never)   → 원복: AC 30분, DC 20분
;;      - 최대절전(hibernate) → off         → 원복: on
;;      - 전원 버튼 동작 → 3(종료)          → 원복: 1(절전)
;;      - 절전 버튼 동작 → 3(종료)          → 원복: 1(절전)
:: ══════════════════════════════════════════════════════════════
echo [단계 7/7] 전원 정책 원복 중...

:: 절전 타이머 복원 (AC: 30분, DC: 20분)
powercfg -change -standby-timeout-ac 30 >nul 2>&1
powercfg -change -standby-timeout-dc 20 >nul 2>&1
powercfg -change -hibernate-timeout-ac 0 >nul 2>&1
powercfg -change -hibernate-timeout-dc 0 >nul 2>&1
echo [OK] 절전 타이머 복원 (AC=30분, DC=20분)

:: 최대절전 파일 재활성화
powercfg -hibernate on >nul 2>&1
echo [OK] 최대절전(hibernate) 재활성화

:: 전원 버튼 동작 복원: 1=절전(Sleep) — AC/DC 모두
powercfg -setacvalueindex scheme_current sub_buttons pbuttonaction 1 >nul 2>&1
powercfg -setdcvalueindex scheme_current sub_buttons pbuttonaction 1 >nul 2>&1
:: 절전 버튼 동작 복원: 1=절전(Sleep) — AC/DC 모두
powercfg -setacvalueindex scheme_current sub_buttons sbuttonaction 1 >nul 2>&1
powercfg -setdcvalueindex scheme_current sub_buttons sbuttonaction 1 >nul 2>&1
powercfg -setactive scheme_current >nul 2>&1
echo [OK] 전원/절전 버튼 동작 복원 (Sleep)

:: ══════════════════════════════════════════════════════════════
:: 완료
:: ══════════════════════════════════════════════════════════════
echo.
echo ================================================================
echo  에이전트 완전 제거(원복) 완료
echo.
echo  제거된 항목:
echo    - 작업 스케줄러: \PCAgent\Main
echo    - 설치 폴더    : %INSTALL_DIR%
echo    - AppData 폴더 : %APPDATA%\PCAgent
echo    - 방화벽 규칙  : PCAgent Outbound
echo    - 전원 정책    : 절전/최대절전/버튼 동작 원복
echo.
echo  ※ 전원 정책을 설치 전 상태로 정확히 되돌리려면
echo    제어판 → 전원 옵션 → 현재 전원 관리 옵션 편집에서
echo    직접 확인하세요.
echo ================================================================
pause
endlocal
