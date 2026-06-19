@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "INSTALL_DIR=C:\Program Files\PCAgent"
set "JAR_NAME=agent.jar"
set "AGENT_EXE=agent.exe"
set "APPLY_POWER_POLICY=1"
set "APPLY_FIREWALL=1"
set "USES_JAR=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--skip-power-policy" set "APPLY_POWER_POLICY=0"
if /I "%~1"=="--skip-firewall" set "APPLY_FIREWALL=0"
shift
goto parse_args

:args_done
net session >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Run this script as Administrator.
    echo         Right-click install-agent.bat and choose "Run as administrator".
    pause
    exit /b 1
)

echo ================================================================
echo  PCAgent Install
echo  Install dir : %INSTALL_DIR%
echo ================================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "Set-ExecutionPolicy -Scope LocalMachine -ExecutionPolicy RemoteSigned -Force -ErrorAction SilentlyContinue" >nul 2>&1
echo [OK] PowerShell execution policy checked

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to create install directory: %INSTALL_DIR%
    pause
    exit /b 1
)
echo [OK] Install dir ready

if exist "%SCRIPT_DIR%\%JAR_NAME%" (
    copy /Y "%SCRIPT_DIR%\%JAR_NAME%" "%INSTALL_DIR%\%JAR_NAME%" >nul
    if errorlevel 1 (
        echo [ERROR] Failed to copy %JAR_NAME%
        pause
        exit /b 1
    )
    set "USES_JAR=1"
    echo [OK] %JAR_NAME% copied
) else (
    if exist "%SCRIPT_DIR%\%AGENT_EXE%" (
        copy /Y "%SCRIPT_DIR%\%AGENT_EXE%" "%INSTALL_DIR%\%AGENT_EXE%" >nul
        if errorlevel 1 (
            echo [ERROR] Failed to copy %AGENT_EXE%
            pause
            exit /b 1
        )
        echo [OK] %AGENT_EXE% copied
    ) else (
        echo [ERROR] agent.jar or agent.exe not found next to install-agent.bat
        pause
        exit /b 1
    )
)

if exist "%SCRIPT_DIR%\agent-watchdog.ps1" (
    copy /Y "%SCRIPT_DIR%\agent-watchdog.ps1" "%INSTALL_DIR%\agent-watchdog.ps1" >nul
    if errorlevel 1 (
        echo [ERROR] Failed to copy agent-watchdog.ps1
        pause
        exit /b 1
    )
    echo [OK] agent-watchdog.ps1 copied
) else (
    echo [ERROR] agent-watchdog.ps1 not found
    pause
    exit /b 1
)

rem 원격제어(UltraVNC) 번들 — 있으면 INSTALL_DIR\vnc 로 복사 (없어도 설치는 계속)
rem 에이전트 resolveVncServerPath()가 INSTALL_DIR\vnc\winvnc.exe 를 찾는다.
if exist "%SCRIPT_DIR%\vnc" (
    if exist "%INSTALL_DIR%\vnc" rmdir /S /Q "%INSTALL_DIR%\vnc" >nul 2>&1
    xcopy /E /I /Y /Q "%SCRIPT_DIR%\vnc" "%INSTALL_DIR%\vnc" >nul
    if errorlevel 1 (
        echo [WARN] VNC bundle copy failed ^(remote control may be unavailable^)
    ) else (
        echo [OK] VNC bundle copied ^(remote control^)
    )
) else (
    echo [INFO] No VNC bundle found - remote control requires separate UltraVNC install
)

if "%USES_JAR%"=="1" (
    if exist "%SCRIPT_DIR%\jre" (
        if exist "%INSTALL_DIR%\jre" rmdir /S /Q "%INSTALL_DIR%\jre" >nul 2>&1
        xcopy /E /I /Y /Q "%SCRIPT_DIR%\jre" "%INSTALL_DIR%\jre" >nul
        if errorlevel 1 (
            echo [ERROR] Bundled JRE copy failed
            pause
            exit /b 1
        )
        echo [OK] Bundled JRE copied
    ) else (
        echo [WARN] Bundled JRE not found
        echo [WARN] System Java 17+ is required if agent.jar is used
    )
)

if exist "%INSTALL_DIR%\config.json" (
    echo [INFO] Existing config.json kept ^(agentToken preserved^)
) else (
    if exist "%SCRIPT_DIR%\config.json" (
        copy /Y "%SCRIPT_DIR%\config.json" "%INSTALL_DIR%\config.json" >nul
        if errorlevel 1 (
            echo [ERROR] Failed to copy config.json
            pause
            exit /b 1
        )
        echo [OK] config.json copied
    ) else (
        echo [ERROR] config.json not found
        pause
        exit /b 1
    )
)

sc query "PCAgent" >nul 2>&1
if not errorlevel 1 (
    sc stop "PCAgent" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "PCAgent" >nul 2>&1
    echo [OK] Old Windows service removed
)

if not exist "%SCRIPT_DIR%\install-helper.ps1" (
    echo [ERROR] install-helper.ps1 not found next to install-agent.bat
    pause
    exit /b 1
)

echo [INFO] Registering scheduled task...
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\install-helper.ps1" -InstallDir "%INSTALL_DIR%"
if errorlevel 1 (
    echo [ERROR] Scheduled task registration failed
    pause
    exit /b 1
)
echo [OK] Scheduled task registered

if "%APPLY_FIREWALL%"=="1" (
    netsh advfirewall firewall show rule name="PCAgent Outbound" >nul 2>&1
    if errorlevel 1 (
        netsh advfirewall firewall add rule name="PCAgent Outbound" dir=out action=allow protocol=TCP remoteport=8888,443,80 >nul
        if errorlevel 1 (
            echo [WARN] Failed to add outbound firewall rule
        ) else (
            echo [OK] Outbound firewall rule added ^(TCP 8888,443,80^)
        )
    ) else (
        echo [INFO] Outbound firewall rule already exists
    )

    netsh advfirewall firewall show rule name="PCAgent Update Cache" >nul 2>&1
    if errorlevel 1 (
        netsh advfirewall firewall add rule name="PCAgent Update Cache" dir=in action=allow protocol=TCP localport=18777 >nul
        if errorlevel 1 (
            echo [WARN] Failed to add inbound firewall rule
        ) else (
            echo [OK] Inbound firewall rule added ^(TCP 18777^)
        )
    ) else (
        echo [INFO] Inbound firewall rule already exists
    )
) else (
    echo [INFO] Firewall configuration skipped
)

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
    echo [OK] Power policy applied
) else (
    echo [INFO] Power policy skipped
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Start-ScheduledTask -TaskName 'PCAgent'; Write-Host '[OK] Agent started' } catch { try { Start-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main'; Write-Host '[OK] Agent started (legacy task)' } catch { Write-Host '[INFO] Immediate start failed - it will auto-start on next boot' } }"

echo.
echo ================================================================
echo  Installation complete
echo.
echo  Install dir  : %INSTALL_DIR%
echo  Auto-start   : At boot ^(SYSTEM account^)
echo  Auto-restart : Watchdog restarts on crash
if "%APPLY_POWER_POLICY%"=="1" (
    echo  Power policy : Applied
) else (
    echo  Power policy : Skipped
)
if "%APPLY_FIREWALL%"=="1" (
    echo  Firewall     : Outbound 8888/443/80 + Inbound 18777
) else (
    echo  Firewall     : Skipped
)
echo  Log file     : %INSTALL_DIR%\watchdog.log
echo  Agent log    : C:\ProgramData\PCAgent\logs\agent.log
echo ================================================================
pause
endlocal
