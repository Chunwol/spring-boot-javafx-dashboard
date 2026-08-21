#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$StageDir,
    [Parameter(Mandatory = $true)][string]$InstallDir,
    [Parameter(Mandatory = $true)][string]$TargetJar,
    [Parameter(Mandatory = $true)][string]$ProgramDataDir,
    [Parameter(Mandatory = $true)][string]$TargetVersion,
    [Parameter(Mandatory = $true)][long]$TaskId,
    [Parameter(Mandatory = $true)][int]$AgentPid,
    [string]$UpdaterTaskName = ""
)

$ErrorActionPreference = 'Stop'
$mainTaskName = 'PCAgentWatchdog'
$logDir = Join-Path $ProgramDataDir 'logs'
$logFile = Join-Path $logDir 'bundle-updater.log'
$statusFile = Join-Path $ProgramDataDir 'bundle-update-status.json'
$bundleVersionFile = Join-Path $ProgramDataDir 'bundle-version.json'
$manifestFile = Join-Path $StageDir 'bundle-manifest.json'
$backupDir = Join-Path $InstallDir ('.bundle-backup-' + $TaskId)
$newJreDir = Join-Path $InstallDir ('.jre-next-' + $TaskId)
$newVncDir = Join-Path $InstallDir ('.vnc-next-' + $TaskId)
$backupReady = $false
$applied = $false

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Write-UpdateLog {
    param([string]$Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [BundleUpdater] $Message"
    try { [IO.File]::AppendAllText($logFile, $line + [Environment]::NewLine, [Text.Encoding]::UTF8) } catch {}
}

function Write-Status {
    param([string]$Status, [string]$Message)
    $data = [ordered]@{
        taskId = $TaskId
        targetVersion = $TargetVersion
        status = $Status
        message = $Message
        updatedAt = (Get-Date).ToString('o')
        rebootRequired = $false
    }
    $data | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statusFile -Encoding UTF8
}

function Assert-UnderDirectory {
    param([string]$Path, [string]$Root, [string]$Label)
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label is outside the allowed directory."
    }
}

function Assert-StagedBundle {
    foreach ($required in @(
        'bundle-manifest.json',
        'agent.jar',
        'agent-watchdog.ps1',
        'install-helper.ps1',
        'agent-bundle-updater.ps1',
        'jre\bin\java.exe',
        'jre\lib\modules',
        'vnc\winvnc.exe',
        'vnc\createpassword.exe'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $StageDir $required))) {
            throw "Required bundle file is missing: $required"
        }
    }

    $manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
    if ([int]$manifest.schemaVersion -ne 1) { throw 'Unsupported bundle manifest schema.' }
    if ([string]$manifest.bundleVersion -ne $TargetVersion) { throw 'Bundle version does not match targetVersion.' }
    $files = @($manifest.files)
    if ($files.Count -lt 8 -or $files.Count -gt 2000) { throw 'Bundle manifest file count is invalid.' }

    foreach ($entry in $files) {
        $relative = ([string]$entry.path).Replace('/', '\')
        if ([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or $relative.Contains('..')) {
            throw "Unsafe bundle path: $relative"
        }
        $path = Join-Path $StageDir $relative
        Assert-UnderDirectory -Path $path -Root $StageDir -Label 'Bundle file'
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Manifest file is missing: $relative" }
        if ([long](Get-Item -LiteralPath $path).Length -ne [long]$entry.size) { throw "Bundle file size mismatch: $relative" }
        $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        if ($actual -ne ([string]$entry.sha256).ToUpperInvariant()) { throw "Bundle file hash mismatch: $relative" }
    }

    $javaProbe = Start-Process -FilePath (Join-Path $StageDir 'jre\bin\java.exe') -ArgumentList '-version' `
        -Wait -PassThru -WindowStyle Hidden
    if ($javaProbe.ExitCode -ne 0) { throw 'Bundled Java runtime probe failed.' }
    return $manifest
}

function Stop-MainTaskAndProcesses {
    try { Stop-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction SilentlyContinue } catch {}

    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline -and (Get-Process -Id $AgentPid -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id $AgentPid -ErrorAction SilentlyContinue) {
        Stop-Process -Id $AgentPid -Force -ErrorAction SilentlyContinue
    }

    try {
        $currentPid = $PID
        Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe' OR Name = 'PCAgent.exe' OR Name = 'agent.exe'" `
            -ErrorAction SilentlyContinue |
            Where-Object {
                $_.ProcessId -ne $currentPid -and
                (($_.CommandLine -and $_.CommandLine.IndexOf($InstallDir, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or
                 ($_.ExecutablePath -and $_.ExecutablePath.IndexOf($InstallDir, [StringComparison]::OrdinalIgnoreCase) -ge 0))
            } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch {}
    try { & taskkill.exe /F /IM winvnc.exe 2>$null | Out-Null } catch {}
    Start-Sleep -Seconds 2
}

function Register-MainTask {
    try { Stop-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction SilentlyContinue } catch {}
    try { Unregister-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}

    $watchdogScript = Join-Path $InstallDir 'agent-watchdog.ps1'
    if (-not (Test-Path -LiteralPath $watchdogScript)) { throw 'Installed watchdog script is missing.' }
    $escapedWatchdog = $watchdogScript -replace "'", "''"
    $escapedInstallDir = $InstallDir -replace "'", "''"
    $watchdogCommand = "& '$escapedWatchdog' -InstallDir '$escapedInstallDir'"
    $launcher = Join-Path $InstallDir 'PCAgent.exe'
    if (Test-Path -LiteralPath $launcher) {
        $escapedLauncher = $launcher -replace "'", "''"
        $watchdogCommand += " -LauncherPath '$escapedLauncher'"
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogCommand))
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand $encoded" `
        -WorkingDirectory $InstallDir
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
        -Hidden -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal -Force | Out-Null

    $registered = Get-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction Stop
    [xml]$xml = Export-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction Stop
    if ([string]$registered.Principal.UserId -notmatch '^(SYSTEM|S-1-5-18)$') { throw 'Watchdog task principal is not SYSTEM.' }
    if ([string]$xml.Task.Settings.ExecutionTimeLimit -ne 'PT0S') { throw 'Watchdog task time limit is not unlimited.' }
    if ([string]$xml.Task.Settings.DisallowStartIfOnBatteries -ieq 'true' -or
        [string]$xml.Task.Settings.StopIfGoingOnBatteries -ieq 'true') {
        throw 'Watchdog task battery settings are unsafe.'
    }
}

function Backup-CurrentBundle {
    if (Test-Path -LiteralPath $backupDir) { Remove-Item -LiteralPath $backupDir -Recurse -Force }
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $backupDir 'files') -Force | Out-Null

    if (Test-Path -LiteralPath $TargetJar) { Copy-Item -LiteralPath $TargetJar -Destination (Join-Path $backupDir 'files\agent.jar') -Force }
    foreach ($name in @('agent-watchdog.ps1', 'install-helper.ps1', 'agent-bundle-updater.ps1')) {
        $source = Join-Path $InstallDir $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $backupDir ('files\' + $name)) -Force }
    }
    if (Test-Path -LiteralPath $bundleVersionFile) {
        Copy-Item -LiteralPath $bundleVersionFile -Destination (Join-Path $backupDir 'files\bundle-version.json') -Force
    }
    $script:backupReady = $true
}

function Prepare-NewDirectories {
    foreach ($path in @($newJreDir, $newVncDir)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
    }
    Copy-Item -LiteralPath (Join-Path $StageDir 'jre') -Destination $newJreDir -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $StageDir 'vnc') -Destination $newVncDir -Recurse -Force
}

function Apply-NewBundle {
    $installedJre = Join-Path $InstallDir 'jre'
    $installedVnc = Join-Path $InstallDir 'vnc'
    if (Test-Path -LiteralPath $installedJre) { Move-Item -LiteralPath $installedJre -Destination (Join-Path $backupDir 'jre') -Force }
    if (Test-Path -LiteralPath $installedVnc) { Move-Item -LiteralPath $installedVnc -Destination (Join-Path $backupDir 'vnc') -Force }
    Move-Item -LiteralPath $newJreDir -Destination $installedJre -Force
    Move-Item -LiteralPath $newVncDir -Destination $installedVnc -Force

    Copy-Item -LiteralPath (Join-Path $StageDir 'agent.jar') -Destination $TargetJar -Force
    foreach ($name in @('agent-watchdog.ps1', 'install-helper.ps1', 'agent-bundle-updater.ps1')) {
        Copy-Item -LiteralPath (Join-Path $StageDir $name) -Destination (Join-Path $InstallDir $name) -Force
    }

    $manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
    [ordered]@{
        bundleVersion = $TargetVersion
        appliedAt = (Get-Date).ToString('o')
        taskId = $TaskId
        components = $manifest.components
        manifestSha256 = (Get-FileHash -LiteralPath $manifestFile -Algorithm SHA256).Hash
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $bundleVersionFile -Encoding UTF8
    Copy-Item -LiteralPath $manifestFile -Destination (Join-Path $ProgramDataDir 'current-bundle-manifest.json') -Force
    $script:applied = $true
}

function Wait-ForHealthyAgent {
    $heartbeat = Join-Path $ProgramDataDir 'heartbeat-ok'
    try { Remove-Item -LiteralPath $heartbeat -Force -ErrorAction SilentlyContinue } catch {}
    $startedMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction Stop
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        $heartbeatMs = [long]0
        if (Test-Path -LiteralPath $heartbeat) {
            try { [void][long]::TryParse((Get-Content -LiteralPath $heartbeat -Raw).Trim(), [ref]$heartbeatMs) } catch {}
        }
        $agentProcess = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -and $_.CommandLine.IndexOf($TargetJar, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                $_.ExecutablePath -and $_.ExecutablePath.IndexOf((Join-Path $InstallDir 'jre'), [StringComparison]::OrdinalIgnoreCase) -eq 0
            } | Select-Object -First 1
        if ($heartbeatMs -ge $startedMs -and $agentProcess) { return }
        Start-Sleep -Seconds 3
    }
    throw 'Updated agent did not produce a successful heartbeat within 3 minutes.'
}

function Restore-PreviousBundle {
    Write-UpdateLog 'Restoring previous bundle.'
    Stop-MainTaskAndProcesses
    foreach ($path in @((Join-Path $InstallDir 'jre'), (Join-Path $InstallDir 'vnc'), $newJreDir, $newVncDir)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue }
    }
    if (Test-Path -LiteralPath (Join-Path $backupDir 'jre')) { Move-Item -LiteralPath (Join-Path $backupDir 'jre') -Destination (Join-Path $InstallDir 'jre') -Force }
    if (Test-Path -LiteralPath (Join-Path $backupDir 'vnc')) { Move-Item -LiteralPath (Join-Path $backupDir 'vnc') -Destination (Join-Path $InstallDir 'vnc') -Force }
    $oldJar = Join-Path $backupDir 'files\agent.jar'
    if (Test-Path -LiteralPath $oldJar) { Copy-Item -LiteralPath $oldJar -Destination $TargetJar -Force }
    foreach ($name in @('agent-watchdog.ps1', 'install-helper.ps1', 'agent-bundle-updater.ps1')) {
        $old = Join-Path $backupDir ('files\' + $name)
        $installed = Join-Path $InstallDir $name
        if (Test-Path -LiteralPath $old) { Copy-Item -LiteralPath $old -Destination $installed -Force }
        elseif (Test-Path -LiteralPath $installed) { Remove-Item -LiteralPath $installed -Force }
    }
    $oldBundleVersion = Join-Path $backupDir 'files\bundle-version.json'
    if (Test-Path -LiteralPath $oldBundleVersion) { Copy-Item -LiteralPath $oldBundleVersion -Destination $bundleVersionFile -Force }
    elseif (Test-Path -LiteralPath $bundleVersionFile) { Remove-Item -LiteralPath $bundleVersionFile -Force }
    Register-MainTask
    Start-ScheduledTask -TaskPath '\' -TaskName $mainTaskName -ErrorAction SilentlyContinue
}

try {
    Assert-UnderDirectory -Path $TargetJar -Root $InstallDir -Label 'Target JAR'
    $manifest = Assert-StagedBundle
    Write-UpdateLog "Validated full bundle v$TargetVersion for task $TaskId."
    Prepare-NewDirectories
    Backup-CurrentBundle
    Stop-MainTaskAndProcesses
    Apply-NewBundle
    Register-MainTask
    Wait-ForHealthyAgent
    Write-Status -Status 'COMPLETED' -Message 'Full bundle updated and heartbeat verified.'
    Write-UpdateLog "Full bundle update completed: v$TargetVersion."

    Get-ChildItem -LiteralPath $InstallDir -Directory -Filter '.bundle-backup-*' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -ne $backupDir } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    exit 0
} catch {
    $message = $_.Exception.Message
    Write-UpdateLog "Update failed: $message"
    if ($backupReady) {
        try { Restore-PreviousBundle; Write-UpdateLog 'Rollback completed.' }
        catch { Write-UpdateLog "Rollback failed: $($_.Exception.Message)" }
    }
    Write-Status -Status 'FAILED' -Message $message
    exit 1
} finally {
    if ($UpdaterTaskName) {
        try { Unregister-ScheduledTask -TaskPath '\' -TaskName $UpdaterTaskName -Confirm:$false -ErrorAction SilentlyContinue } catch {}
    }
}
