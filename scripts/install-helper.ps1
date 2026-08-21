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
    [string]$TaskName   = "PCAgentWatchdog",
    [switch]$ValidateFilesOnly
)

$ErrorActionPreference = 'Stop'
$watchdogScript = Join-Path $InstallDir 'agent-watchdog.ps1'
$agentJar = @(
    (Join-Path $InstallDir 'app\agent.jar'),
    (Join-Path $InstallDir 'agent.jar')
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$launcherPath = Join-Path $InstallDir 'PCAgent.exe'
if (!(Test-Path $launcherPath)) {
    $launcherPath = Join-Path $InstallDir 'agent.exe'
}
if (!(Test-Path -LiteralPath $watchdogScript)) {
    throw "Watchdog script not found: $watchdogScript"
}
if (!(Test-Path -LiteralPath $launcherPath) -and !$agentJar) {
    throw "PCAgent launcher or agent.jar not found in: $InstallDir"
}
if ($ValidateFilesOnly) {
    Write-Host "[OK] PCAgent install files verified: $InstallDir"
    exit 0
}

# Remove existing tasks (ignore errors if not found)
try { Stop-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction SilentlyContinue } catch {}
try { Stop-ScheduledTask -TaskPath '\' -TaskName 'PCAgentWatchdog' -ErrorAction SilentlyContinue } catch {}
try { Stop-ScheduledTask -TaskPath '\' -TaskName 'PCAgent' -ErrorAction SilentlyContinue } catch {}
try { Stop-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -ErrorAction SilentlyContinue } catch {}
Start-Sleep -Seconds 2  # 워치독 프로세스가 정상 종료될 시간 확보
try { Unregister-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}
try { Unregister-ScheduledTask -TaskPath '\' -TaskName 'PCAgentWatchdog' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
try { Unregister-ScheduledTask -TaskPath '\' -TaskName 'PCAgent' -Confirm:$false -ErrorAction SilentlyContinue } catch {}
try { Unregister-ScheduledTask -TaskPath '\PCAgent\' -TaskName 'Main' -Confirm:$false -ErrorAction SilentlyContinue } catch {}

# 잔존 워치독/에이전트 프로세스 강제 종료 — 파일 잠금으로 인한 xcopy 실패 방지
# 정확한 PCAgent 설치 경로만 매치 (다른 .ps1/.jar 무관 보호)
try {
    $currentPid = $PID
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'java.exe' OR Name = 'agent.exe' OR Name = 'PCAgent.exe'" `
        -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ProcessId -ne $currentPid -and
            (($_.CommandLine -and $_.CommandLine.IndexOf($InstallDir, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or
             ($_.ExecutablePath -and $_.ExecutablePath.IndexOf($InstallDir, [StringComparison]::OrdinalIgnoreCase) -ge 0))
        } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
} catch {}
Start-Sleep -Seconds 1  # OS가 파일 핸들 해제할 시간

$escapedWatchdogScript = $watchdogScript -replace "'", "''"
$escapedInstallDir = $InstallDir -replace "'", "''"
$watchdogCommand = "& '$escapedWatchdogScript' -InstallDir '$escapedInstallDir'"
if (Test-Path $launcherPath) {
    $escapedLauncherPath = $launcherPath -replace "'", "''"
    $watchdogCommand += " -LauncherPath '$escapedLauncherPath'"
}
$encodedWatchdogCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogCommand))
$actionArgs = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand $encodedWatchdogCommand"
$action = New-ScheduledTaskAction `
    -Execute 'powershell.exe' `
    -Argument $actionArgs `
    -WorkingDirectory $InstallDir

$trigger = New-ScheduledTaskTrigger -AtStartup

# -DisallowStartIfOnBatteries/-StopIfGoingOnBatteries 미지정 시 기본값이 True로 잡혀,
# 배터리/UPS 순간전환 시 Windows가 이 작업을 "정책 정지"시킨다(실패가 아니므로 RestartCount 미발동,
# 다음 재부팅 전까지 복구 안 됨). 명시적으로 꺼서 전원 상태와 무관하게 계속 돌게 한다.
$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -Hidden `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries

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

$registeredTask = Get-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction Stop
[xml]$taskXml = Export-ScheduledTask -TaskPath $TaskPath -TaskName $TaskName -ErrorAction Stop
$registeredAction = @($registeredTask.Actions)[0]
$registeredUser = [string]$registeredTask.Principal.UserId
$registeredArgs = [string]$registeredAction.Arguments

if ($registeredUser -notmatch '^(SYSTEM|S-1-5-18)$') {
    throw "Scheduled task principal is not SYSTEM: $registeredUser"
}
if ([string]$registeredAction.Execute -notmatch '(?i)powershell(\.exe)?$') {
    throw "Scheduled task action is not powershell.exe: $($registeredAction.Execute)"
}
if ($registeredArgs -notmatch '(?i)-WindowStyle\s+Hidden' -or $registeredArgs -notmatch '(?i)-EncodedCommand\s+(\S+)') {
    throw 'Scheduled task action is not a hidden encoded PowerShell command.'
}
if ($registeredArgs -match '(?i)\s-File\s') {
    throw 'Scheduled task action still uses -File.'
}

$encodedMatch = [regex]::Match($registeredArgs, '(?i)-EncodedCommand\s+(\S+)')
$decodedCommand = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encodedMatch.Groups[1].Value))
if (!$decodedCommand.Contains($watchdogScript) -or !$decodedCommand.Contains($InstallDir)) {
    throw 'Scheduled task encoded command does not point to this installation.'
}
if ([string]$taskXml.Task.Settings.ExecutionTimeLimit -ne 'PT0S') {
    throw "Scheduled task execution time limit is not unlimited: $($taskXml.Task.Settings.ExecutionTimeLimit)"
}
if ([string]$taskXml.Task.Settings.DisallowStartIfOnBatteries -ieq 'true' -or
    [string]$taskXml.Task.Settings.StopIfGoingOnBatteries -ieq 'true') {
    throw 'Scheduled task battery settings are not safe for continuous operation.'
}
if ([string]$taskXml.Task.Settings.StartWhenAvailable -ine 'true') {
    throw 'Scheduled task StartWhenAvailable is disabled.'
}

Write-Host "[OK] Scheduled task registered and verified: ${TaskPath}${TaskName}"
