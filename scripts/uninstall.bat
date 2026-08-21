@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "INSTALL_DIR=C:\Program Files\PCAgent"
set "APPDATA_DIR=%APPDATA%\PCAgent"
set "OLD_SERVICE=PCAgent"
set "TASK_PATH=\"
set "TASK_NAME=PCAgentWatchdog"

net session >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Run this script as Administrator.
    echo         Right-click uninstall-agent.bat and choose "Run as administrator".
    pause
    exit /b 1
)

echo ================================================================
echo  PCAgent Uninstall
echo  Install dir : %INSTALL_DIR%
echo  AppData dir : %APPDATA_DIR%
echo ================================================================
echo.
echo This will remove the scheduled task, firewall rules, files, and restore
echo the power policy changed by the installer.
set /p CONFIRM=Continue? Type Y and press Enter: 
if /I not "%CONFIRM%"=="Y" (
    echo Uninstall cancelled.
    pause
    exit /b 0
)

echo.
echo [1/7] Removing scheduled task...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "try { Stop-ScheduledTask -TaskName 'PCAgentWatchdog' -ErrorAction SilentlyContinue } catch {};" ^
    "try { Stop-ScheduledTask -TaskName 'PCAgent' -ErrorAction SilentlyContinue } catch {};" ^
    "try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {};" ^
    "Start-Sleep -Seconds 2;" ^
    "try { Unregister-ScheduledTask -TaskName 'PCAgentWatchdog' -Confirm:$false -ErrorAction SilentlyContinue } catch {};" ^
    "try { Unregister-ScheduledTask -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {};" ^
    "try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {};" ^
    "Write-Host '[OK] Scheduled task remove requested'"

echo [2/7] Removing legacy Windows service if present...
sc query "%OLD_SERVICE%" >nul 2>&1
if not errorlevel 1 (
    sc stop "%OLD_SERVICE%" >nul 2>&1
    timeout /T 3 /NOBREAK >nul
    sc delete "%OLD_SERVICE%" >nul 2>&1
    echo [OK] Legacy service removed
) else (
    echo [INFO] Legacy service not found
)

echo [3/7] Stopping watchdog and agent processes...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$pcagentPath = '*Program Files\PCAgent*';" ^
    "Get-CimInstance Win32_Process -Filter \"Name = 'powershell.exe' OR Name = 'java.exe' OR Name = 'agent.exe'\" -ErrorAction SilentlyContinue |" ^
    "Where-Object { $_.CommandLine -like $pcagentPath -or $_.ExecutablePath -like $pcagentPath } |" ^
    "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue };" ^
    "Write-Host '[OK] Process stop requested'"
timeout /T 3 /NOBREAK >nul

echo [4/7] Removing firewall rules...
netsh advfirewall firewall delete rule name="PCAgent Outbound" >nul 2>&1
netsh advfirewall firewall delete rule name="PCAgent Update Cache" >nul 2>&1
echo [OK] Firewall rules removed if present

echo [5/7] Removing install directory...
if exist "%INSTALL_DIR%" (
    set "DELETED=0"
    for /L %%I in (1,1,5) do (
        if "!DELETED!"=="0" (
            rmdir /S /Q "%INSTALL_DIR%" >nul 2>&1
            if not exist "%INSTALL_DIR%" set "DELETED=1"
            if "!DELETED!"=="0" timeout /T 2 /NOBREAK >nul
        )
    )
    if "!DELETED!"=="1" (
        echo [OK] Install directory removed
    ) else (
        echo [WARN] Install directory is still locked: %INSTALL_DIR%
    )
) else (
    echo [INFO] Install directory not found
)

echo [6/7] Removing AppData directory...
if exist "%APPDATA_DIR%" (
    rmdir /S /Q "%APPDATA_DIR%" >nul 2>&1
    if exist "%APPDATA_DIR%" (
        echo [WARN] AppData directory is still present: %APPDATA_DIR%
    ) else (
        echo [OK] AppData directory removed
    )
) else (
    echo [INFO] AppData directory not found
)

echo [7/7] Restoring power policy...
powercfg -change -standby-timeout-ac 30 >nul 2>&1
powercfg -change -standby-timeout-dc 20 >nul 2>&1
powercfg -change -hibernate-timeout-ac 0 >nul 2>&1
powercfg -change -hibernate-timeout-dc 0 >nul 2>&1
powercfg -hibernate on >nul 2>&1
powercfg -setacvalueindex scheme_current sub_buttons pbuttonaction 1 >nul 2>&1
powercfg -setdcvalueindex scheme_current sub_buttons pbuttonaction 1 >nul 2>&1
powercfg -setacvalueindex scheme_current sub_buttons sbuttonaction 1 >nul 2>&1
powercfg -setdcvalueindex scheme_current sub_buttons sbuttonaction 1 >nul 2>&1
powercfg -setactive scheme_current >nul 2>&1
echo [OK] Power policy restored

echo.
echo ================================================================
echo  PCAgent uninstall complete
echo ================================================================
pause
endlocal
