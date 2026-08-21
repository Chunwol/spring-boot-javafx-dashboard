@echo off
setlocal EnableExtensions EnableDelayedExpansion

if not defined PCAGENT_INSTALL_SOURCE_DIR set "PCAGENT_INSTALL_SOURCE_DIR=%~dp0"
set "SCRIPT_DIR=%PCAGENT_INSTALL_SOURCE_DIR%"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "INSTALL_DIR=C:\Program Files\PCAgent"
set "JAR_NAME=agent.jar"
set "AGENT_EXE=agent.exe"
if defined PCAGENT_INSTALL_APPLY_POWER_POLICY (
    set "APPLY_POWER_POLICY=%PCAGENT_INSTALL_APPLY_POWER_POLICY%"
) else (
    set "APPLY_POWER_POLICY=1"
)
if defined PCAGENT_INSTALL_APPLY_FIREWALL (
    set "APPLY_FIREWALL=%PCAGENT_INSTALL_APPLY_FIREWALL%"
) else (
    set "APPLY_FIREWALL=1"
)
set "USES_JAR=0"
set "AGENT_HEALTHY=0"
set "VERIFY_PACKAGE=0"
set "VERIFY_UPGRADE_COPY=0"
set "VERIFY_JRE_INSTALL=0"
set "PCAGENT_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PCAGENT_POWERSHELL%" set "PCAGENT_POWERSHELL=powershell.exe"
set "PCAGENT_INSTALL_CLEANUP_SCRIPT=%TEMP%\PCAgentManualCleanup-%RANDOM%-%RANDOM%.ps1"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--skip-power-policy" set "APPLY_POWER_POLICY=0"
if /I "%~1"=="--skip-firewall" set "APPLY_FIREWALL=0"
if /I "%~1"=="--verify-package" set "VERIFY_PACKAGE=1"
if /I "%~1"=="--verify-upgrade-copy" set "VERIFY_UPGRADE_COPY=1"
if /I "%~1"=="--verify-jre-install" set "VERIFY_JRE_INSTALL=1"
shift
goto parse_args

:args_done
if "%VERIFY_PACKAGE%"=="1" goto verify_package
if "%VERIFY_UPGRADE_COPY%"=="1" goto verify_upgrade_copy
if "%VERIFY_JRE_INSTALL%"=="1" goto verify_jre_install
set "PCAGENT_INSTALL_APPLY_POWER_POLICY=%APPLY_POWER_POLICY%"
set "PCAGENT_INSTALL_APPLY_FIREWALL=%APPLY_FIREWALL%"
set "PCAGENT_INSTALL_BOOTSTRAP_LOG=%TEMP%\PCAgent-manual-install-bootstrap.log"

fltmc >nul 2>&1
if errorlevel 1 (
    echo [INFO] Requesting administrator privileges...
    set "PCAGENT_INSTALL_ELEVATED_COPY=%TEMP%\PCAgentManualInstall-%RANDOM%-%RANDOM%.cmd"
    copy /Y "%~f0" "!PCAGENT_INSTALL_ELEVATED_COPY!" >nul 2>&1
    if errorlevel 1 set "PCAGENT_INSTALL_ELEVATED_COPY=%~f0"
    >>"%PCAGENT_INSTALL_BOOTSTRAP_LOG%" echo %DATE% %TIME% Elevation requested from "%~f0"
    "%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $target=$env:PCAGENT_INSTALL_ELEVATED_COPY; if(-not (Test-Path -LiteralPath $target)){ throw ('Installer CMD not found: ' + $target) }; Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c',$target -Verb RunAs"
    if errorlevel 1 (
        echo [ERROR] Failed to start the elevated installer.
        echo         Log: "%PCAGENT_INSTALL_BOOTSTRAP_LOG%"
        timeout /T 30 >nul
        exit /b 1
    )
    exit /b 0
)

if not exist "%ProgramData%\PCAgent\logs" mkdir "%ProgramData%\PCAgent\logs" >nul 2>&1
set "PCAGENT_INSTALL_LOG=%ProgramData%\PCAgent\logs\manual-install.log"
>>"%PCAGENT_INSTALL_LOG%" echo.
>>"%PCAGENT_INSTALL_LOG%" echo %DATE% %TIME% Manual install started. Source="%SCRIPT_DIR%"

echo ================================================================
echo  PCAgent Install
echo  Install dir : %INSTALL_DIR%
echo ================================================================
echo.

echo [OK] Administrator privileges confirmed

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if errorlevel 1 (
    echo [ERROR] Failed to create install directory: %INSTALL_DIR%
    pause
    exit /b 1
)
echo [OK] Install dir ready

echo [INFO] Stopping an existing PCAgent instance...
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='SilentlyContinue'; foreach($task in @(@{Path='\';Name='PCAgentWatchdog'},@{Path='\';Name='PCAgent'},@{Path='\PCAgent\';Name='Main'})){ Stop-ScheduledTask -TaskPath $task.Path -TaskName $task.Name }; $installDir=$env:INSTALL_DIR; $currentPid=$PID; Get-CimInstance Win32_Process | Where-Object { @('powershell.exe','java.exe','agent.exe','PCAgent.exe') -contains $_.Name -and $_.ProcessId -ne $currentPid -and (($_.CommandLine -and $_.CommandLine.IndexOf($installDir,[StringComparison]::OrdinalIgnoreCase) -ge 0) -or ($_.ExecutablePath -and $_.ExecutablePath.IndexOf($installDir,[StringComparison]::OrdinalIgnoreCase) -ge 0)) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; Start-Sleep -Seconds 2"
echo [OK] Existing PCAgent instance stopped

if exist "%SCRIPT_DIR%\%JAR_NAME%" (
    call :copy_agent_jar
    if errorlevel 1 (
        echo [ERROR] Failed to copy %JAR_NAME%
        pause
        exit /b 1
    )
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

for %%F in (install-helper.ps1 agent-bundle-updater.ps1 bundle-manifest.json) do (
    if not exist "%SCRIPT_DIR%\%%F" (
        echo [ERROR] %%F not found
        pause
        exit /b 1
    )
    copy /Y "%SCRIPT_DIR%\%%F" "%INSTALL_DIR%\%%F" >nul
    if errorlevel 1 (
        echo [ERROR] Failed to copy %%F
        pause
        exit /b 1
    )
)
echo [OK] Update support files copied

rem Copy the bundled UltraVNC files when present.
rem The agent resolves the server at INSTALL_DIR\vnc\winvnc.exe.
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
        call :install_bundled_jre
        if errorlevel 1 (
            pause
            exit /b 1
        )
    ) else (
        echo [ERROR] Bundled JRE not found. PCAgent does not use system Java.
        pause
        exit /b 1
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

set "PCAGENT_BUNDLE_MANIFEST=%INSTALL_DIR%\bundle-manifest.json"
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $manifest=Get-Content -LiteralPath $env:PCAGENT_BUNDLE_MANIFEST -Raw | ConvertFrom-Json; $dir=Join-Path $env:ProgramData 'PCAgent'; New-Item -ItemType Directory -Path $dir -Force | Out-Null; [ordered]@{bundleVersion=[string]$manifest.bundleVersion;appliedAt=(Get-Date).ToString('o');taskId=0;components=$manifest.components;manifestSha256=(Get-FileHash -LiteralPath $env:PCAGENT_BUNDLE_MANIFEST -Algorithm SHA256).Hash}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $dir 'bundle-version.json') -Encoding UTF8"
if errorlevel 1 (
    echo [ERROR] Failed to record installed bundle version
    pause
    exit /b 1
)
echo [OK] Bundle version recorded

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
set "PCAGENT_INSTALL_HELPER=%SCRIPT_DIR%\install-helper.ps1"
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "try { & $env:PCAGENT_INSTALL_HELPER -InstallDir $env:INSTALL_DIR *>&1 | Tee-Object -FilePath $env:PCAGENT_INSTALL_LOG -Append; exit 0 } catch { $_ | Out-String | Tee-Object -FilePath $env:PCAGENT_INSTALL_LOG -Append; exit 1 }"
if errorlevel 1 (
    echo [ERROR] Scheduled task registration failed
    echo         Log: %PCAGENT_INSTALL_LOG%
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

echo [INFO] Starting PCAgent and waiting up to 5 minutes for runtime health...
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $started=Get-Date; Start-ScheduledTask -TaskPath '\' -TaskName 'PCAgentWatchdog'; $deadline=(Get-Date).AddMinutes(5); $processSeen=$false; do { $agentProcess=Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*agent.jar*' } | Select-Object -First 1; if($agentProcess -and (-not $processSeen)){ Write-Host ('[INFO] Agent process detected: java.exe PID=' + $agentProcess.ProcessId); $processSeen=$true }; $heartbeat=Join-Path $env:ProgramData 'PCAgent\heartbeat-ok'; if($agentProcess -and (Test-Path -LiteralPath $heartbeat)){ $item=Get-Item -LiteralPath $heartbeat; if($item.LastWriteTime -ge $started){ Write-Host '[OK] Agent process and heartbeat confirmed'; exit 0 } }; Start-Sleep -Seconds 3 } while((Get-Date) -lt $deadline); if($processSeen){ Write-Host '[WARN] Agent process started, but no new heartbeat was confirmed within 5 minutes.'; exit 3 }; Write-Host '[WARN] Agent process was not detected within 5 minutes.'; exit 2"
if errorlevel 3 (
    echo [WARN] Agent process started, but server heartbeat is not confirmed.
    echo        Installer files will be kept for troubleshooting.
) else if errorlevel 2 (
    echo [WARN] Agent process was not detected within 5 minutes.
    echo        Installer files will be kept for retry.
) else if errorlevel 1 (
    echo [WARN] The task was registered, but immediate start failed.
    echo        It will retry at the next boot.
) else (
    set "AGENT_HEALTHY=1"
)

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
echo  Watchdog log : C:\ProgramData\PCAgent\logs\watchdog.log
echo  Agent log    : C:\ProgramData\PCAgent\logs\agent.log
echo  Install log  : %PCAGENT_INSTALL_LOG%
if "%USES_JAR%"=="1" echo  Agent JAR    : %AGENT_JAR_TARGET%
if "%AGENT_HEALTHY%"=="1" (
    echo  Source files : Remove automatically after this window closes
) else (
    echo  Source files : Kept for retry and troubleshooting
)
echo ================================================================
>>"%PCAGENT_INSTALL_LOG%" echo %DATE% %TIME% Manual install finished.

if "%AGENT_HEALTHY%"=="1" (
    copy /Y "%SCRIPT_DIR%\manual-install-cleanup.ps1" "%PCAGENT_INSTALL_CLEANUP_SCRIPT%" >nul 2>&1
    if errorlevel 1 (
        echo [WARN] Cleanup helper could not be prepared. Source files were kept.
    ) else (
        echo.
        echo [INFO] This window will close in 10 seconds.
        echo [INFO] Local installer files will then be removed automatically.
        timeout /T 10 /NOBREAK >nul
        start "" /b "%PCAGENT_POWERSHELL%" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%PCAGENT_INSTALL_CLEANUP_SCRIPT%" -SourceDir "%SCRIPT_DIR%" -InstallDir "%INSTALL_DIR%" -LogFile "%PCAGENT_INSTALL_LOG%" -BootstrapCopy "%PCAGENT_INSTALL_ELEVATED_COPY%"
        endlocal
        exit /b 0
    )
)

echo.
echo [INFO] This window will close in 10 seconds.
timeout /T 10 /NOBREAK >nul
if "%AGENT_HEALTHY%"=="1" (
    endlocal
    exit /b 0
)
endlocal
exit /b 2

:verify_package
echo ================================================================
echo  PCAgent Manual Package Check
echo  Package dir : %SCRIPT_DIR%
echo ================================================================
set "VERIFY_FAILED=0"
for %%F in (agent.jar config.json agent-watchdog.ps1 install-helper.ps1 agent-bundle-updater.ps1 bundle-manifest.json manual-install-cleanup.ps1 .pcagent-manual-package jre\bin\java.exe jre\lib\modules vnc\winvnc.exe vnc\createpassword.exe) do (
    if not exist "%SCRIPT_DIR%\%%F" (
        echo [ERROR] Missing: %%F
        set "VERIFY_FAILED=1"
    )
)
if "%VERIFY_FAILED%"=="1" (
    echo [ERROR] Package file check failed.
    endlocal
    exit /b 1
)

"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; foreach($name in @('install-helper.ps1','manual-install-cleanup.ps1','agent-watchdog.ps1','agent-bundle-updater.ps1')){ $path=Join-Path $env:SCRIPT_DIR $name; [void][ScriptBlock]::Create([IO.File]::ReadAllText($path)) }; $manifest=Get-Content -LiteralPath (Join-Path $env:SCRIPT_DIR 'bundle-manifest.json') -Raw | ConvertFrom-Json; if([int]$manifest.schemaVersion -ne 1 -or [string]::IsNullOrWhiteSpace([string]$manifest.bundleVersion)){throw 'Invalid bundle manifest'}"
if errorlevel 1 (
    echo [ERROR] PowerShell script syntax check failed.
    endlocal
    exit /b 1
)

call :validate_java_runtime "%SCRIPT_DIR%\jre"
if errorlevel 1 (
    echo [ERROR] Bundled Java runtime validation failed.
    endlocal
    exit /b 1
)

echo [OK] Package files and PowerShell scripts are valid.
endlocal
exit /b 0

:verify_upgrade_copy
set "UPGRADE_TEST_DIR=%TEMP%\PCAgentUpgradeLayout-%RANDOM%-%RANDOM%"
mkdir "%UPGRADE_TEST_DIR%\app" >nul 2>&1
set "INSTALL_DIR=%UPGRADE_TEST_DIR%"
call :copy_agent_jar
if errorlevel 1 goto verify_upgrade_copy_failed
if not exist "%UPGRADE_TEST_DIR%\app\agent.jar" goto verify_upgrade_copy_failed
if exist "%UPGRADE_TEST_DIR%\agent.jar" goto verify_upgrade_copy_failed
copy /Y "%SCRIPT_DIR%\agent-watchdog.ps1" "%UPGRADE_TEST_DIR%\agent-watchdog.ps1" >nul
if errorlevel 1 goto verify_upgrade_copy_failed
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\install-helper.ps1" -InstallDir "%UPGRADE_TEST_DIR%" -ValidateFilesOnly
if errorlevel 1 goto verify_upgrade_copy_failed
rmdir /S /Q "%UPGRADE_TEST_DIR%" >nul 2>&1
echo [OK] Existing MSI layout upgrade targets app\agent.jar and passes helper validation.
endlocal
exit /b 0

:verify_upgrade_copy_failed
if exist "%UPGRADE_TEST_DIR%" rmdir /S /Q "%UPGRADE_TEST_DIR%" >nul 2>&1
echo [ERROR] Existing MSI layout upgrade verification failed.
endlocal
exit /b 1

:verify_jre_install
set "JRE_TEST_DIR=%TEMP%\PCAgentJreInstall-%RANDOM%-%RANDOM%"
set "INSTALL_DIR=%JRE_TEST_DIR%"
mkdir "%JRE_TEST_DIR%\jre" >nul 2>&1
echo previous>"%JRE_TEST_DIR%\jre\previous.marker"
call :install_bundled_jre
if errorlevel 1 goto verify_jre_install_failed
if exist "%JRE_TEST_DIR%\jre\previous.marker" goto verify_jre_install_failed
if exist "%JRE_TEST_DIR%\jre.installing" goto verify_jre_install_failed
if exist "%JRE_TEST_DIR%\jre.previous" goto verify_jre_install_failed
call :validate_java_runtime "%JRE_TEST_DIR%\jre"
if errorlevel 1 goto verify_jre_install_failed
rmdir /S /Q "%JRE_TEST_DIR%" >nul 2>&1
echo [OK] Bundled JRE staged, validated, and replaced safely.
endlocal
exit /b 0

:verify_jre_install_failed
if exist "%JRE_TEST_DIR%" rmdir /S /Q "%JRE_TEST_DIR%" >nul 2>&1
echo [ERROR] Bundled JRE safe-install verification failed.
endlocal
exit /b 1

:copy_agent_jar
set "PCAGENT_SOURCE_JAR=%SCRIPT_DIR%\%JAR_NAME%"
set "AGENT_JAR_TARGET=%INSTALL_DIR%\%JAR_NAME%"
if exist "%INSTALL_DIR%\app\" set "AGENT_JAR_TARGET=%INSTALL_DIR%\app\%JAR_NAME%"
copy /Y "%PCAGENT_SOURCE_JAR%" "%AGENT_JAR_TARGET%" >nul
if errorlevel 1 exit /b 1
set "PCAGENT_TARGET_JAR=%AGENT_JAR_TARGET%"
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$sha=[Security.Cryptography.SHA256]::Create(); try { $sourceStream=[IO.File]::OpenRead($env:PCAGENT_SOURCE_JAR); try { $source=[BitConverter]::ToString($sha.ComputeHash($sourceStream)) } finally { $sourceStream.Dispose() }; $targetStream=[IO.File]::OpenRead($env:PCAGENT_TARGET_JAR); try { $target=[BitConverter]::ToString($sha.ComputeHash($targetStream)) } finally { $targetStream.Dispose() }; if($source -ne $target){ throw 'Copied agent.jar SHA-256 mismatch.' } } finally { $sha.Dispose() }"
if errorlevel 1 exit /b 1
set "USES_JAR=1"
echo [OK] %JAR_NAME% copied and verified: %AGENT_JAR_TARGET%
exit /b 0

:validate_java_runtime
set "PCAGENT_RUNTIME_ROOT=%~1"
if not exist "%PCAGENT_RUNTIME_ROOT%\bin\java.exe" exit /b 1
if not exist "%PCAGENT_RUNTIME_ROOT%\lib\modules" exit /b 1
"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\agent-watchdog.ps1" -ValidateJavaExe "%PCAGENT_RUNTIME_ROOT%\bin\java.exe"
if errorlevel 1 exit /b 1
exit /b 0

:install_bundled_jre
set "JRE_STAGING=%INSTALL_DIR%\jre.installing"
set "JRE_BACKUP=%INSTALL_DIR%\jre.previous"
set "JRE_HAD_PREVIOUS=0"

call :validate_java_runtime "%SCRIPT_DIR%\jre"
if errorlevel 1 (
    echo [ERROR] Source bundled JRE is incomplete or damaged.
    exit /b 1
)

if exist "%JRE_STAGING%" rmdir /S /Q "%JRE_STAGING%" >nul 2>&1
robocopy "%SCRIPT_DIR%\jre" "%JRE_STAGING%" /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo [ERROR] Bundled JRE staging copy failed.
    if exist "%JRE_STAGING%" rmdir /S /Q "%JRE_STAGING%" >nul 2>&1
    exit /b 1
)

call :validate_java_runtime "%JRE_STAGING%"
if errorlevel 1 (
    echo [ERROR] Copied bundled JRE failed validation. Existing JRE was kept.
    rmdir /S /Q "%JRE_STAGING%" >nul 2>&1
    exit /b 1
)

if exist "%JRE_BACKUP%" rmdir /S /Q "%JRE_BACKUP%" >nul 2>&1
if exist "%INSTALL_DIR%\jre" (
    move /Y "%INSTALL_DIR%\jre" "%JRE_BACKUP%" >nul
    if errorlevel 1 (
        echo [ERROR] Existing bundled JRE could not be preserved.
        rmdir /S /Q "%JRE_STAGING%" >nul 2>&1
        exit /b 1
    )
    set "JRE_HAD_PREVIOUS=1"
)

move /Y "%JRE_STAGING%" "%INSTALL_DIR%\jre" >nul
if errorlevel 1 (
    echo [ERROR] Validated bundled JRE could not be activated.
    if "!JRE_HAD_PREVIOUS!"=="1" move /Y "%JRE_BACKUP%" "%INSTALL_DIR%\jre" >nul
    exit /b 1
)

call :validate_java_runtime "%INSTALL_DIR%\jre"
if errorlevel 1 (
    echo [ERROR] Activated bundled JRE failed final validation. Restoring previous JRE.
    rmdir /S /Q "%INSTALL_DIR%\jre" >nul 2>&1
    if "!JRE_HAD_PREVIOUS!"=="1" move /Y "%JRE_BACKUP%" "%INSTALL_DIR%\jre" >nul
    exit /b 1
)

if exist "%JRE_BACKUP%" rmdir /S /Q "%JRE_BACKUP%" >nul 2>&1
echo [OK] Bundled Java 25 copied, validated, and activated.
exit /b 0
