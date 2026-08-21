$ErrorActionPreference = 'Continue'

$intelPattern = '(?i)(intel.*(graphics|igcc|arc)|graphics.*intel|igcc|intelgraphicssoftware)'
$errors = [System.Collections.Generic.List[string]]::new()
$intelRemoved = 0
$intelStartupTasksDisabled = 0
$profilesConfigured = 0
$machineRoot = [Microsoft.Win32.Registry]::LocalMachine
$userRoot = [Microsoft.Win32.Registry]::Users

function Set-DwordValue(
    [Microsoft.Win32.RegistryKey]$Root,
    [string]$Path,
    [string]$Name,
    [int]$Value
) {
    $key = $null
    try {
        $key = $Root.CreateSubKey($Path, $true)
        if ($null -eq $key) { throw 'registry key unavailable' }
        $key.SetValue($Name, $Value, [Microsoft.Win32.RegistryValueKind]::DWord)
    } catch {
        $errors.Add("registry:${Path}:$Name")
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}

function Set-StringValue(
    [Microsoft.Win32.RegistryKey]$Root,
    [string]$Path,
    [string]$Name,
    [string]$Value
) {
    $key = $null
    try {
        $key = $Root.CreateSubKey($Path, $true)
        if ($null -eq $key) { throw 'registry key unavailable' }
        $key.SetValue($Name, $Value, [Microsoft.Win32.RegistryValueKind]::String)
    } catch {
        $errors.Add("registry:${Path}:$Name")
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}

function Remove-IntelRunValues(
    [Microsoft.Win32.RegistryKey]$Root,
    [string]$Path
) {
    $key = $null
    try {
        $key = $Root.OpenSubKey($Path, $true)
        if ($null -eq $key) { return }
        foreach ($name in @($key.GetValueNames())) {
            $value = $key.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            if (("$name $value") -match $intelPattern) {
                $key.DeleteValue($name, $false)
                $script:intelRemoved++
            }
        }
    } catch {
        $errors.Add("intel-run:$Path")
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}

function Disable-IntelApprovedValues(
    [Microsoft.Win32.RegistryKey]$Root,
    [string]$Path
) {
    $key = $null
    try {
        $key = $Root.OpenSubKey($Path, $true)
        if ($null -eq $key) { return }
        foreach ($name in @($key.GetValueNames())) {
            if ($name -match $intelPattern) {
                $disabled = [byte[]](3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                $key.SetValue($name, $disabled, [Microsoft.Win32.RegistryValueKind]::Binary)
            }
        }
    } catch {
        $errors.Add("intel-approved:$Path")
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}

function Configure-UserHive([string]$HiveName) {
    Set-StringValue $userRoot "$HiveName\Control Panel\Desktop" 'AutoEndTasks' '1'
    Set-StringValue $userRoot "$HiveName\Control Panel\Desktop" 'HungAppTimeout' '3000'
    Set-StringValue $userRoot "$HiveName\Control Panel\Desktop" 'WaitToKillAppTimeout' '3000'
    Remove-IntelRunValues $userRoot "$HiveName\Software\Microsoft\Windows\CurrentVersion\Run"
    foreach ($approved in @('Run', 'Run32', 'StartupTask', 'StartupFolder')) {
        Disable-IntelApprovedValues $userRoot `
            "$HiveName\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\$approved"
    }
    $script:profilesConfigured++
}

function Test-UserHive([string]$HiveName) {
    $desktop = $null
    try {
        $desktop = $userRoot.OpenSubKey("$HiveName\Control Panel\Desktop", $false)
        if ($null -eq $desktop) { return $false }
        if ([string]$desktop.GetValue('AutoEndTasks', '') -ne '1') { return $false }
        if ([string]$desktop.GetValue('HungAppTimeout', '') -ne '3000') { return $false }
        if ([string]$desktop.GetValue('WaitToKillAppTimeout', '') -ne '3000') { return $false }
    } finally {
        if ($null -ne $desktop) { $desktop.Dispose() }
    }

    $run = $null
    try {
        $run = $userRoot.OpenSubKey("$HiveName\Software\Microsoft\Windows\CurrentVersion\Run", $false)
        if ($null -ne $run) {
            foreach ($name in $run.GetValueNames()) {
                $value = $run.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                if (("$name $value") -match $intelPattern) { return $false }
            }
        }
    } finally {
        if ($null -ne $run) { $run.Dispose() }
    }
    return $true
}

Set-DwordValue $machineRoot `
    'SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management' `
    'ClearPageFileAtShutdown' 0
Set-DwordValue $machineRoot `
    'SOFTWARE\Policies\Microsoft\Windows\System' `
    'AllowBlockingAppsAtShutdown' 0

foreach ($path in @(
    'SOFTWARE\Microsoft\Windows\CurrentVersion\Run',
    'SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'
)) {
    Remove-IntelRunValues $machineRoot $path
}

$verifiedProfiles = 0
$loadedSids = @($userRoot.GetSubKeyNames() | Where-Object {
    $_ -eq '.DEFAULT' -or $_ -match '^S-1-5-21-(?:\d+-){3}\d+$'
})
foreach ($sid in $loadedSids) {
    Configure-UserHive $sid
    if (Test-UserHive $sid) { $verifiedProfiles++ } else { $errors.Add("profile-verify:$sid") }
}

$profileList = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList'
$profileRows = @(Get-ChildItem -LiteralPath $profileList -ErrorAction SilentlyContinue | ForEach-Object {
    $values = Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue
    if ($_.PSChildName -match '^S-1-5-21-(?:\d+-){3}\d+$' -and $values.ProfileImagePath) {
        [ordered]@{ sid = $_.PSChildName; path = [Environment]::ExpandEnvironmentVariables($values.ProfileImagePath) }
    }
})
$unloadedIndex = 0
foreach ($profile in $profileRows) {
    if ($loadedSids -contains $profile.sid) { continue }
    $hivePath = Join-Path $profile.path 'NTUSER.DAT'
    if (-not (Test-Path -LiteralPath $hivePath -PathType Leaf)) { continue }
    $unloadedIndex++
    $hiveName = "PCAgentPolicy_${PID}_$unloadedIndex"
    & reg.exe load "HKU\$hiveName" $hivePath *> $null
    if ($LASTEXITCODE -ne 0) {
        $errors.Add("profile-load:$($profile.sid)")
        continue
    }
    try {
        Configure-UserHive $hiveName
        if (Test-UserHive $hiveName) { $verifiedProfiles++ } else { $errors.Add("profile-verify:$($profile.sid)") }
    } finally {
        [gc]::Collect()
        [gc]::WaitForPendingFinalizers()
        & reg.exe unload "HKU\$hiveName" *> $null
        if ($LASTEXITCODE -ne 0) { $errors.Add("profile-unload:$($profile.sid)") }
    }
}

$defaultHivePath = 'C:\Users\Default\NTUSER.DAT'
if (Test-Path -LiteralPath $defaultHivePath -PathType Leaf) {
    $hiveName = "PCAgentDefault_$PID"
    & reg.exe load "HKU\$hiveName" $defaultHivePath *> $null
    if ($LASTEXITCODE -eq 0) {
        try {
            Configure-UserHive $hiveName
            if (Test-UserHive $hiveName) { $verifiedProfiles++ } else { $errors.Add('profile-verify:Default') }
        } finally {
            [gc]::Collect()
            [gc]::WaitForPendingFinalizers()
            & reg.exe unload "HKU\$hiveName" *> $null
            if ($LASTEXITCODE -ne 0) { $errors.Add('profile-unload:Default') }
        }
    } else {
        $errors.Add('profile-load:Default')
    }
}

$intelTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
    $actions = [string](($_.Actions | ForEach-Object Execute) -join ' ')
    ("$($_.TaskPath)$($_.TaskName) $actions") -match $intelPattern
})
foreach ($task in $intelTasks) {
    try {
        Disable-ScheduledTask -InputObject $task -ErrorAction Stop | Out-Null
        $intelStartupTasksDisabled++
    } catch {
        $errors.Add("intel-task:$($task.TaskPath)$($task.TaskName)")
    }
}
Get-Process -Name 'IntelGraphicsSoftware', 'IGCC', 'IGCCTray', 'ArcControl' `
    -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$bluetoothFound = 0
$bluetoothDisabled = 0
$bluetoothRemainingEnabled = 0
$radioIds = @()
if (Get-Command Get-PnpDevice -ErrorAction SilentlyContinue) {
    $radios = @(Get-PnpDevice -Class Bluetooth -PresentOnly -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -match '^(USB|PCI)\\' })
    $radioIds = @($radios | ForEach-Object InstanceId)
    $bluetoothFound = $radioIds.Count
    foreach ($radio in $radios) {
        try {
            Disable-PnpDevice -InstanceId $radio.InstanceId -Confirm:$false -ErrorAction Stop
            $bluetoothDisabled++
        } catch {
            & pnputil.exe /disable-device $radio.InstanceId *> $null
            if ($LASTEXITCODE -eq 0) {
                $bluetoothDisabled++
            } else {
                $errors.Add("bluetooth:$($radio.InstanceId)")
            }
        }
    }
    if ($bluetoothFound -gt 0) {
        Stop-Service -Name bthserv -Force -ErrorAction SilentlyContinue
        Set-Service -Name bthserv -StartupType Disabled -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        $bluetoothRemainingEnabled = @(Get-PnpDevice -Class Bluetooth -PresentOnly -ErrorAction SilentlyContinue |
            Where-Object { $_.InstanceId -in $radioIds -and $_.Status -eq 'OK' }).Count
    }
} else {
    $errors.Add('bluetooth:Get-PnpDevice-unavailable')
}

$machineRunRemaining = 0
foreach ($path in @(
    'SOFTWARE\Microsoft\Windows\CurrentVersion\Run',
    'SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'
)) {
    $key = $null
    try {
        $key = $machineRoot.OpenSubKey($path, $false)
        if ($null -eq $key) { continue }
        foreach ($name in $key.GetValueNames()) {
            $value = $key.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            if (("$name $value") -match $intelPattern) { $machineRunRemaining++ }
        }
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}
$remainingIntelTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue | Where-Object {
    $actions = [string](($_.Actions | ForEach-Object Execute) -join ' ')
    ("$($_.TaskPath)$($_.TaskName) $actions") -match $intelPattern -and $_.State -ne 'Disabled'
}).Count
$runningIntel = @(Get-Process -Name 'IntelGraphicsSoftware', 'IGCC', 'IGCCTray', 'ArcControl' `
    -ErrorAction SilentlyContinue).Count

$clearPageFile = (Get-ItemProperty `
    'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management' `
    -Name ClearPageFileAtShutdown -ErrorAction SilentlyContinue).ClearPageFileAtShutdown
$allowBlockingApps = (Get-ItemProperty `
    'HKLM:\SOFTWARE\Policies\Microsoft\Windows\System' `
    -Name AllowBlockingAppsAtShutdown -ErrorAction SilentlyContinue).AllowBlockingAppsAtShutdown
$shutdownVerified = [int]$clearPageFile -eq 0 -and [int]$allowBlockingApps -eq 0 -and
    $verifiedProfiles -eq $profilesConfigured
$intelStartupVerified = $machineRunRemaining -eq 0 -and $remainingIntelTasks -eq 0 -and $runningIntel -eq 0
$bluetoothVerified = $bluetoothRemainingEnabled -eq 0
$verified = $shutdownVerified -and $intelStartupVerified -and $bluetoothVerified -and $errors.Count -eq 0

Write-Output ('CLASSROOM_POLICY verified={0} shutdownVerified={1} intelStartupVerified={2} bluetoothVerified={3} intelRemoved={4} intelTasksDisabled={5} bluetoothFound={6} bluetoothDisabled={7} profiles={8} errors={9}' -f `
    $verified, $shutdownVerified, $intelStartupVerified, $bluetoothVerified, $intelRemoved,
    $intelStartupTasksDisabled, $bluetoothFound, $bluetoothDisabled, $profilesConfigured, $errors.Count)
if ($errors.Count -gt 0) {
    Write-Output ('CLASSROOM_POLICY_ERRORS ' + ($errors -join ','))
}
if (-not $verified) { exit 1 }
exit 0
