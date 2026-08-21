$ErrorActionPreference = 'Continue'

$intelPattern = '(?i)(intel.*(graphics|igcc|arc)|graphics.*intel|igcc|intelgraphicssoftware)'
$startupEntries = [System.Collections.Generic.List[object]]::new()
$userRoot = [Microsoft.Win32.Registry]::Users
$machineRoot = [Microsoft.Win32.Registry]::LocalMachine

function Add-RegistryMatches(
    [Microsoft.Win32.RegistryKey]$Root,
    [string]$RootName,
    [string]$SubKey,
    [string]$Kind
) {
    $key = $null
    try {
        $key = $Root.OpenSubKey($SubKey, $false)
        if ($null -eq $key) { return }
        foreach ($name in $key.GetValueNames()) {
            $value = $key.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            $text = if ($value -is [byte[]]) { $name } else { "$name $value" }
            if ($text -match $intelPattern) {
                $startupEntries.Add([ordered]@{
                    kind = $Kind
                    root = $RootName
                    path = $SubKey
                    name = $name
                    value = if ($value -is [byte[]]) { '[binary]' } else { [string]$value }
                })
            }
        }
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
}

foreach ($path in @(
    'SOFTWARE\Microsoft\Windows\CurrentVersion\Run',
    'SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'
)) {
    Add-RegistryMatches $machineRoot 'HKLM' $path 'Run'
}

$profileSids = @($userRoot.GetSubKeyNames() | Where-Object {
    $_ -eq '.DEFAULT' -or $_ -match '^S-1-5-21-(?:\d+-){3}\d+$'
})
foreach ($sid in $profileSids) {
    foreach ($path in @(
        "$sid\Software\Microsoft\Windows\CurrentVersion\Run",
        "$sid\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run",
        "$sid\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run32",
        "$sid\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupTask",
        "$sid\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder"
    )) {
        Add-RegistryMatches $userRoot 'HKU' $path 'UserStartup'
    }
}

$startupTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue | ForEach-Object {
    $task = $_
    $actions = [string](($task.Actions | ForEach-Object Execute) -join ' ')
    if (("$($task.TaskPath)$($task.TaskName) $actions") -match $intelPattern) {
        [ordered]@{
            path = $task.TaskPath
            name = $task.TaskName
            state = [string]$task.State
            actions = $actions
        }
    }
})

$bluetoothDevices = @()
if (Get-Command Get-PnpDevice -ErrorAction SilentlyContinue) {
    $bluetoothDevices = @(Get-PnpDevice -Class Bluetooth -PresentOnly -ErrorAction SilentlyContinue |
        Select-Object Status, Class, FriendlyName, InstanceId)
}

$allowBlockingApps = (Get-ItemProperty `
    'HKLM:\SOFTWARE\Policies\Microsoft\Windows\System' `
    -Name AllowBlockingAppsAtShutdown -ErrorAction SilentlyContinue).AllowBlockingAppsAtShutdown
$clearPageFile = (Get-ItemProperty `
    'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management' `
    -Name ClearPageFileAtShutdown -ErrorAction SilentlyContinue).ClearPageFileAtShutdown
$profilePolicies = @($profileSids | ForEach-Object {
    $key = $null
    try {
        $key = $userRoot.OpenSubKey("$_\Control Panel\Desktop", $false)
        [ordered]@{
            sid = $_
            autoEndTasks = if ($key) { [string]$key.GetValue('AutoEndTasks', $null) } else { $null }
            hungAppTimeout = if ($key) { [string]$key.GetValue('HungAppTimeout', $null) } else { $null }
            waitToKillAppTimeout = if ($key) { [string]$key.GetValue('WaitToKillAppTimeout', $null) } else { $null }
        }
    } finally {
        if ($null -ne $key) { $key.Dispose() }
    }
})

[ordered]@{
    marker = 'STARTUP_SHUTDOWN_BLUETOOTH_AUDIT'
    computerName = $env:COMPUTERNAME
    intelStartupEntries = @($startupEntries)
    intelScheduledTasks = $startupTasks
    bluetoothDevices = $bluetoothDevices
    shutdownPolicy = [ordered]@{
        allowBlockingAppsAtShutdown = $allowBlockingApps
        clearPageFileAtShutdown = $clearPageFile
        profiles = $profilePolicies
    }
} | ConvertTo-Json -Depth 8 -Compress
