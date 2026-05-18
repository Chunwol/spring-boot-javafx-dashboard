param(
    [ValidateSet('enable','disable','status')]
    [string]$Action = 'status',
    [switch]$RunNow
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir '..'))
$runnerPath = Join-Path $projectRoot 'scripts\run-dashboard-stack.bat'

$taskPath = '\Qsync\Dashboard\'
$taskName = 'QsyncDashboardAutoStart'
$taskFullName = "$taskPath$taskName"
$currentUser = "$($env:USERDOMAIN)\$($env:USERNAME)"

function Write-Step([string]$Message) {
    Write-Host "[AUTO-START] $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-WarnLine([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Get-TaskOrNull {
    try {
        return Get-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction Stop
    } catch {
        return $null
    }
}

if (-not (Test-Path $runnerPath)) {
    throw "Runner script not found: $runnerPath"
}

switch ($Action) {
    'status' {
        $task = Get-TaskOrNull
        if ($null -eq $task) {
            Write-Host "NOT_REGISTERED"
            break
        }

        $info = Get-ScheduledTaskInfo -TaskPath $taskPath -TaskName $taskName -ErrorAction SilentlyContinue
        Write-Host "REGISTERED"
        Write-Host ("Task: {0}" -f $taskFullName)
        Write-Host ("User: {0}" -f $task.Principal.UserId)
        Write-Host ("State: {0}" -f $task.State)
        if ($null -ne $info) {
            Write-Host ("LastRunTime: {0}" -f $info.LastRunTime)
            Write-Host ("LastTaskResult: {0}" -f $info.LastTaskResult)
            Write-Host ("NextRunTime: {0}" -f $info.NextRunTime)
        }
        break
    }

    'disable' {
        Write-Step "Removing startup task (if exists)..."
        try { Stop-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction SilentlyContinue | Out-Null } catch {}
        try {
            Unregister-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Confirm:$false -ErrorAction Stop
            Write-Ok "Startup task removed: $taskFullName"
        } catch {
            Write-WarnLine "No task to remove or remove failed: $($_.Exception.Message)"
        }
        break
    }

    'enable' {
        Write-Step "Registering startup task..."

        try { Stop-ScheduledTask -TaskPath $taskPath -TaskName $taskName -ErrorAction SilentlyContinue | Out-Null } catch {}
        try { Unregister-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue | Out-Null } catch {}

        $quotedRunner = '"' + $runnerPath + '" --autostart'
        $actionDef = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ("/c " + $quotedRunner) -WorkingDirectory $projectRoot
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
        $settings = New-ScheduledTaskSettingsSet `
            -ExecutionTimeLimit ([TimeSpan]::Zero) `
            -MultipleInstances IgnoreNew `
            -StartWhenAvailable `
            -RestartCount 3 `
            -RestartInterval (New-TimeSpan -Minutes 1)

        $registered = $false
        try {
            $principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Highest
            Register-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Action $actionDef -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
            $registered = $true
        } catch {
            Write-WarnLine "Highest 권한 등록 실패. 일반 권한으로 재시도합니다. $($_.Exception.Message)"
        }

        if (-not $registered) {
            $principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
            Register-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Action $actionDef -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
        }

        Write-Ok "Startup task registered: $taskFullName"
        Write-Ok "Trigger: AtLogOn ($currentUser)"

        if ($RunNow) {
            Write-Step "Starting task immediately..."
            try {
                Start-ScheduledTask -TaskPath $taskPath -TaskName $taskName
                Write-Ok "Task started"
            } catch {
                Write-WarnLine "Task start failed: $($_.Exception.Message)"
            }
        }

        break
    }
}
