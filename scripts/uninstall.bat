@echo off
:: ============================================================
:: PC 관제 에이전트 제거 스크립트
:: 관리자 권한으로 실행하세요.
:: ============================================================
setlocal enabledelayedexpansion

set INSTALL_DIR=C:\Program Files\PCAgent
set OLD_SERVICE=PCAgent

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

:: ── 작업 스케줄러 작업 중지 및 제거 ──────────────────────────
echo [INFO] 작업 스케줄러 작업 제거 중...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {}; " ^
    "try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {}; " ^
    "try { Unregister-ScheduledTask -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {}; " ^
    "Write-Host '[OK] 작업 스케줄러 작업 제거 완료'"

:: ── 구 버전 Windows 서비스 제거 ───────────────────────────────
sc query "%OLD_SERVICE%" >nul 2>&1
if %errorLevel% equ 0 (
    sc stop "%OLD_SERVICE%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%OLD_SERVICE%"
    echo [OK] Windows 서비스 제거 완료
) else (
    echo [INFO] Windows 서비스가 존재하지 않습니다.
)

:: ── 실행 중인 에이전트 프로세스 종료 ─────────────────────────
echo [INFO] 에이전트 프로세스 종료 중...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Get-WmiObject Win32_Process | Where-Object { $_.CommandLine -like '*PCAgent*agent*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; " ^
    "$limit = (Get-Date).AddSeconds(20); " ^
    "while ((Get-Date) -lt $limit) { " ^
    "  $alive = Get-WmiObject Win32_Process | Where-Object { $_.CommandLine -like '*PCAgent*agent*' }; " ^
    "  if (-not $alive) { break }; Start-Sleep -Seconds 1 " ^
    "}; " ^
    "$remain = Get-WmiObject Win32_Process | Where-Object { $_.CommandLine -like '*PCAgent*agent*' }; " ^
    "if ($remain) { Write-Host '[경고] 일부 에이전트 프로세스가 아직 실행 중입니다.' } else { Write-Host '[OK] 에이전트 프로세스 종료 완료' }"

:: ── 방화벽 규칙 제거 ──────────────────────────────────────────
netsh advfirewall firewall delete rule name="PCAgent Outbound" >nul 2>&1
echo [OK] 방화벽 규칙 제거 완료

:: ── 설치 폴더 제거 ────────────────────────────────────────────
timeout /T 2 /NOBREAK >nul
if exist "%INSTALL_DIR%" (
    set DELETED=0
    for /L %%I in (1,1,5) do (
        rmdir /S /Q "%INSTALL_DIR%" >nul 2>&1
        if not exist "%INSTALL_DIR%" (
            set DELETED=1
        )
        if "!DELETED!"=="1" (
            rem already deleted
        ) else (
            timeout /T 2 /NOBREAK >nul
        )
    )
    if "%DELETED%"=="1" (
        echo [OK] 설치 폴더 제거: %INSTALL_DIR%
    ) else (
        echo [경고] 설치 폴더 일부를 제거하지 못했습니다. 잠금 프로세스를 확인하세요: %INSTALL_DIR%
    )
)

echo.
echo 에이전트 제거가 완료되었습니다.
pause
endlocal
