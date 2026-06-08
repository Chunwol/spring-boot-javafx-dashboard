@echo off
chcp 65001 >nul 2>&1
:: ============================================================
:: PCAgent Install Script
:: Run as Administrator (right-click -> Run as administrator)
:: ============================================================
setlocal enabledelayedexpansion

set INSTALL_DIR=C:\Program Files\PCAgent
set JAR_NAME=agent.jar
set AGENT_EXE=agent.exe
set APPLY_POWER_POLICY=1
set APPLY_FIREWALL=1

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--skip-power-policy" set APPLY_POWER_POLICY=0
if /I "%~1"=="--skip-firewall" set APPLY_FIREWALL=0
shift
goto parse_args
:args_done

:: ── Admin check ───────────────────────────────────────────────
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Run as Administrator: right-click the .bat and select "Run as administrator"
    pause
    exit /b 1
)

echo ================================================================
echo  PCAgent Install
echo  Install dir : %INSTALL_DIR%
echo ================================================================
echo.

:: ── PS execution policy ────────────────────────────────────────
powershell -NoProfile -ExecutionPolicy Bypass -Command "Set-ExecutionPolicy -Scope LocalMachine -ExecutionPolicy RemoteSigned -Force -ErrorAction SilentlyContinue" >nul 2>&1
echo [OK] PS execution policy: RemoteSigned

:: ── Create install directory ──────────────────────────────────
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
echo [OK] Install dir: %INSTALL_DIR%

:: ── Copy agent file ───────────────────────────────────────────
if exist "%~dp0%JAR_NAME%" (
    xcopy /Y "%~dp0%JAR_NAME%" "%INSTALL_DIR%\" >nul
    echo [OK] %JAR_NAME% copied
) else if exist "%~dp0%AGENT_EXE%" (
    xcopy /Y "%~dp0%AGENT_EXE%" "%INSTALL_DIR%\" >nul
    echo [OK] %AGENT_EXE% copied
) else (
    echo [ERROR] agent.jar or agent.exe not found next to install.bat
    pause
    exit /b 1
)

:: ── Copy watchdog ─────────────────────────────────────────────
if exist "%~dp0agent-watchdog.ps1" (
    xcopy /Y "%~dp0agent-watchdog.ps1" "%INSTALL_DIR%\" >nul
    echo [OK] agent-watchdog.ps1 copied
) else (
    echo [ERROR] agent-watchdog.ps1 not found
    pause
    exit /b 1
)

:: ── Copy bundled JRE (Java 없는 PC에서도 동작) ────────────────
:: agent.exe(native) 모드에서는 JRE 불필요, agent.jar 모드에서만 필요
if exist "%~dp0%JAR_NAME%" (
    if exist "%~dp0jre" (
        if exist "%INSTALL_DIR%\jre" rmdir /S /Q "%INSTALL_DIR%\jre" >nul 2>&1
        xcopy /E /I /Y /Q "%~dp0jre" "%INSTALL_DIR%\jre" >nul
        if errorlevel 1 (
            echo [ERROR] Bundled JRE copy failed
            pause
            exit /b 1
        )
        echo [OK] Bundled JRE copied ^(no system Java required^)
    ) else (
        echo [WARN] Bundled JRE missing - watchdog will fall back to system Java
        echo        ^(System Java 17+ must be installed or agent will not start^)
    )
)

:: ── Copy config.json ──────────────────────────────────────────
:: On reinstall: keep existing config.json to preserve agentToken.
:: On first install: copy the bundled config.json.
if exist "%INSTALL_DIR%\config.json" (
    echo [INFO] config.json already exists - keeping existing ^(agentToken preserved^)
) else if exist "%~dp0config.json" (
    xcopy /Y "%~dp0config.json" "%INSTALL_DIR%\" >nul
    echo [OK] config.json copied
) else (
    echo [ERROR] config.json not found
    pause
    exit /b 1
)

:: ── Remove old Windows service (legacy) ───────────────────────
sc query "PCAgent" >nul 2>&1
if %errorLevel% equ 0 (
    sc stop "PCAgent" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "PCAgent" >nul 2>&1
    echo [OK] Old Windows service removed
)

:: ── Register scheduled task ───────────────────────────────────
echo [INFO] Registering scheduled task...
if not exist "%~dp0install-helper.ps1" (
    echo [ERROR] install-helper.ps1 not found next to install.bat
    pause
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-helper.ps1" -InstallDir "%INSTALL_DIR%"
if %errorLevel% neq 0 (
    echo [ERROR] Scheduled task registration failed
    pause
    exit /b 1
)

:: ── Firewall rules ────────────────────────────────────────────
if "%APPLY_FIREWALL%"=="1" (
    netsh advfirewall firewall show rule name="PCAgent Outbound" >nul 2>&1
    if %errorLevel% neq 0 (
        netsh advfirewall firewall add rule name="PCAgent Outbound" dir=out action=allow protocol=TCP remoteport=8888,443,80 >nul
        echo [OK] Firewall outbound rule added ^(TCP 8888,443,80^)
    ) else (
        echo [INFO] Firewall outbound rule already exists
    )

    netsh advfirewall firewall show rule name="PCAgent Update Cache" >nul 2>&1
    if %errorLevel% neq 0 (
        netsh advfirewall firewall add rule name="PCAgent Update Cache" dir=in action=allow protocol=TCP localport=18777 >nul
        echo [OK] Firewall inbound rule added ^(TCP 18777^)
    ) else (
        echo [INFO] Firewall inbound rule already exists
    )
) else (
    echo [INFO] Firewall configuration skipped ^(--skip-firewall^)
)

:: ── Power policy ──────────────────────────────────────────────
if "%APPLY_POWER_POLICY%"=="1" (
    echo [INFO] Applying power policy...
    powercfg -change -standby-timeout-ac 0 >nul 2>&1
    powercfg -change -standby-timeout-dc 0 >nul 2>&1
    powercfg -change -hibernate-timeout-ac 0 >nul 2>&1
    powercfg -change -hibernate-timeout-dc 0 >nul 2>&1
    powercfg -hibernate off >nul 2>&1
    powercfg -setacvalueindex scheme_current sub_buttons pbuttonaction 3 >nul 2>&1
    powercfg -setdcvalueindex scheme_current sub_buttons pbuttonaction 3 >nul 2>&1
    powercfg -setacvalueindex scheme_current sub_buttons sbuttonaction 3 >nul 2>&1
    powercfg -setdcvalueindex scheme_current sub_buttons sbuttonaction 3 >nul 2>&1
    powercfg -setactive scheme_current >nul 2>&1
    echo [OK] Power policy applied ^(sleep/hibernate disabled, power button=shutdown^)
)

:: ── Start agent now ───────────────────────────────────────────
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Start-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main'; Write-Host '[OK] Agent started' } catch { Write-Host '[INFO] Immediate start failed - will auto-start on next reboot' }"

echo.
echo ================================================================
echo  Installation complete!
echo.
echo  Install dir  : %INSTALL_DIR%
echo  Auto-start   : At boot ^(SYSTEM account^)
echo  Auto-restart : Watchdog restarts on crash ^(5s delay^)
if "%APPLY_POWER_POLICY%"=="1" (
    echo  Power policy : Sleep/hibernate disabled
) else (
    echo  Power policy : Skipped ^(--skip-power-policy^)
)
if "%APPLY_FIREWALL%"=="1" (
    echo  Firewall     : Outbound 8888/443/80 + Inbound 18777
) else (
    echo  Firewall     : Skipped ^(--skip-firewall^)
)
echo  Log file     : %INSTALL_DIR%\watchdog.log
echo  Agent log    : C:\ProgramData\PCAgent\logs\agent.log
echo ================================================================
pause
endlocal
