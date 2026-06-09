#Requires -Version 5.1
<#
.SYNOPSIS
    PCAgent install helper - registers the scheduled task.
    Called by install.bat with administrator privileges.
.PARAMETER InstallDir
    Agent installation directory. Default: C:\Program Files\PCAgent
#>
param(
    [string]$InstallDir = "C:\Program Files\PCAgent",
    [string]$TaskPath   = "\",
    [string]$TaskName   = "PCAgent"
)

$ErrorActionPreference = 'Stop'
$watchdogScript = Join-Path $InstallDir 'agent-watchdog.ps1'
$launcherPath = Join-Path $InstallDir 'PCAgent.exe'
if (!(Test-Path $launcherPath)) {
    $launcherPath = Join-Path $InstallDir 'agent.exe'
}

# Remove existing tasks (ignore errors if not found)
try { Stop-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction SilentlyContinue } catch {}
try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {}
Start-Sleep -Seconds 2  # 워치독 프로세스가 정상 종료될 시간 확보
try { Unregister-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}
try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
try { Unregister-ScheduledTask -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {}

# 잔존 워치독/에이전트 프로세스 강제 종료 — 파일 잠금으로 인한 xcopy 실패 방지
# 정확한 PCAgent 설치 경로만 매치 (다른 .ps1/.jar 무관 보호)
$pcagentPath = '*Program Files\PCAgent*'
try {
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'java.exe' OR Name = 'agent.exe'" `
        -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like $pcagentPath -or $_.ExecutablePath -like $pcagentPath } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
} catch {}
Start-Sleep -Seconds 1  # OS가 파일 핸들 해제할 시간

$actionArgs = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $watchdogScript + '" -InstallDir "' + $InstallDir + '"'
if (Test-Path $launcherPath) {
    $actionArgs += ' -LauncherPath "' + $launcherPath + '"'
}
$action = New-ScheduledTaskAction `
    -Execute 'powershell.exe' `
    -Argument $actionArgs `
    -WorkingDirectory $InstallDir

$trigger = New-ScheduledTaskTrigger -AtStartup

$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -Hidden `
    -RestartCount 10 `
    -RestartInterval (New-TimeSpan -Minutes 1)

$principal = New-ScheduledTaskPrincipal `
    -UserId 'SYSTEM' `
    -LogonType ServiceAccount `
    -RunLevel Highest

$null = Register-ScheduledTask `
    -TaskPath $TaskPath `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal `
    -Force

Write-Host "[OK] Scheduled task registered: ${TaskPath}${TaskName}"
