[CmdletBinding()]
param(
    [switch]$Worker
)

$ErrorActionPreference = 'Stop'

$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics\windows-repair-online'
$statePath = Join-Path $root 'repair.state'
$workerPath = Join-Path $root 'repair-online.worker.ps1'
$registryBackupPath = Join-Path $root 'registry-backup.json'
$serviceBackupPath = Join-Path $root 'service-backup.json'
$dismOutputPath = Join-Path $root 'dism-restore.txt'
$dismErrorPath = Join-Path $root 'dism-restore.err.txt'
$sfcOutputPath = Join-Path $root 'sfc-scannow.txt'
$sfcErrorPath = Join-Path $root 'sfc-scannow.err.txt'
$checkOutputPath = Join-Path $root 'dism-checkhealth.txt'

New-Item -ItemType Directory -Path $root -Force | Out-Null

function Write-State([string]$Status, [string]$Detail = '', [int]$ProcessId = $PID) {
    @(
        'status=' + $Status
        'updatedAt=' + (Get-Date).ToString('o')
        'pid=' + $ProcessId
        'detail=' + ($Detail -replace '[\r\n]+', ' ')
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
}

if (-not $Worker) {
    Copy-Item -LiteralPath $PSCommandPath -Destination $workerPath -Force
    Write-State 'LAUNCHING' 'Online repair source with temporary policy override' 0
    $powershell = Join-Path $env:windir 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $process = Start-Process -FilePath $powershell -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $workerPath + '"'), '-Worker'
    ) -WindowStyle Hidden -PassThru
    $currentState = Get-Content -LiteralPath $statePath -ErrorAction SilentlyContinue
    if ($currentState -contains 'status=LAUNCHING') {
        Write-State 'LAUNCHED' 'Online repair worker started' $process.Id
    }
    Write-Output ('WINDOWS_REPAIR_ONLINE launched=True pid={0}' -f $process.Id)
    exit 0
}

$registryItems = @(
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'DisableWindowsUpdateAccess' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'DoNotConnectToWindowsUpdateInternetLocations' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'SetDisableUXWUAccess' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'WUServer' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'WUStatusServer' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'; Name = 'UpdateServiceUrlAlternate' },
    [pscustomobject]@{ Path = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU'; Name = 'UseWUServer' }
)
$serviceNames = @('BITS', 'wuauserv')

function Open-RegistryKey([string]$Path, [bool]$Writable) {
    $subKey = $Path -replace '^HKLM:\\', ''
    if ($Writable) {
        return [Microsoft.Win32.Registry]::LocalMachine.CreateSubKey($subKey, $true)
    }
    return [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($subKey, $false)
}

function Get-RegistrySnapshot {
    $snapshot = [System.Collections.Generic.List[object]]::new()
    foreach ($item in $registryItems) {
        $key = $null
        try {
            $key = Open-RegistryKey $item.Path $false
            $names = if ($null -eq $key) { @() } else { @($key.GetValueNames()) }
            $exists = $names -contains $item.Name
            $value = if ($exists) {
                $key.GetValue($item.Name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
            } else { $null }
            $kind = if ($exists) { [string]$key.GetValueKind($item.Name) } else { '' }
            $snapshot.Add([pscustomobject]@{
                Path = $item.Path
                Name = $item.Name
                Exists = $exists
                Kind = $kind
                Value = $value
            })
        } finally {
            if ($null -ne $key) { $key.Dispose() }
        }
    }
    return @($snapshot)
}

function Set-RegistryDword([string]$Path, [string]$Name, [int]$Value) {
    $key = Open-RegistryKey $Path $true
    try {
        $key.SetValue($Name, $Value, [Microsoft.Win32.RegistryValueKind]::DWord)
    } finally {
        $key.Dispose()
    }
}

function Remove-RegistryValue([string]$Path, [string]$Name) {
    $key = Open-RegistryKey $Path $true
    try {
        $key.DeleteValue($Name, $false)
    } finally {
        $key.Dispose()
    }
}

function Restore-RegistrySnapshot([object[]]$Snapshot) {
    foreach ($item in $Snapshot) {
        $key = Open-RegistryKey ([string]$item.Path) $true
        try {
            if ($item.Exists) {
                $kind = [Microsoft.Win32.RegistryValueKind]::$($item.Kind)
                $key.SetValue([string]$item.Name, $item.Value, $kind)
            } else {
                $key.DeleteValue([string]$item.Name, $false)
            }
        } finally {
            $key.Dispose()
        }
    }
}

function Get-ServiceSnapshot {
    return @($serviceNames | ForEach-Object {
        $service = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f $_)
        [pscustomobject]@{
            Name = [string]$service.Name
            StartMode = [string]$service.StartMode
            State = [string]$service.State
        }
    })
}

function Set-ServiceStartMode([string]$Name, [string]$Mode) {
    $scMode = switch ($Mode) {
        'Auto' { 'auto' }
        'Manual' { 'demand' }
        'Disabled' { 'disabled' }
        default { throw "Unsupported service start mode: $Mode" }
    }
    & "$env:windir\System32\sc.exe" config $Name start= $scMode | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to set $Name start mode to $Mode" }
}

function Restore-ServiceSnapshot([object[]]$Snapshot) {
    foreach ($item in $Snapshot) {
        Set-ServiceStartMode ([string]$item.Name) ([string]$item.StartMode)
        if ([string]$item.State -eq 'Running') {
            Start-Service -Name ([string]$item.Name) -ErrorAction SilentlyContinue
        } else {
            Stop-Service -Name ([string]$item.Name) -Force -ErrorAction SilentlyContinue
        }
    }
}

$registrySnapshot = $null
$serviceSnapshot = $null
$finalStatus = 'FAILED'
$finalDetail = 'Repair did not start'

try {
    Write-State 'BACKUP' 'Saving update policy and service state'
    $registrySnapshot = @(Get-RegistrySnapshot)
    $serviceSnapshot = @(Get-ServiceSnapshot)
    $registrySnapshot | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $registryBackupPath -Encoding utf8
    $serviceSnapshot | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $serviceBackupPath -Encoding utf8

    Set-RegistryDword 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'DisableWindowsUpdateAccess' 0
    Set-RegistryDword 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'DoNotConnectToWindowsUpdateInternetLocations' 0
    Set-RegistryDword 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'SetDisableUXWUAccess' 0
    Set-RegistryDword 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU' 'UseWUServer' 0
    Remove-RegistryValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'WUServer'
    Remove-RegistryValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'WUStatusServer'
    Remove-RegistryValue 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate' 'UpdateServiceUrlAlternate'

    foreach ($name in $serviceNames) {
        Set-ServiceStartMode $name 'Manual'
        Start-Service -Name $name -ErrorAction Stop
    }

    Write-State 'DISM_ONLINE' 'RestoreHealth using Windows Update repair source'
    $dism = Start-Process -FilePath "$env:windir\System32\dism.exe" -ArgumentList @(
        '/Online', '/Cleanup-Image', '/RestoreHealth', '/English'
    ) -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $dismOutputPath -RedirectStandardError $dismErrorPath
    if ($dism.ExitCode -ne 0) {
        throw ('DISM failed with exit code {0}' -f $dism.ExitCode)
    }

    Write-State 'SFC_SCAN' 'System file verification and repair'
    $sfc = Start-Process -FilePath "$env:windir\System32\sfc.exe" -ArgumentList '/scannow' `
        -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $sfcOutputPath -RedirectStandardError $sfcErrorPath
    if ($sfc.ExitCode -ne 0) {
        throw ('SFC failed with exit code {0}' -f $sfc.ExitCode)
    }

    $check = & "$env:windir\System32\dism.exe" /Online /Cleanup-Image /CheckHealth /English 2>&1
    $checkExit = $LASTEXITCODE
    $check | Set-Content -LiteralPath $checkOutputPath -Encoding utf8
    if ($checkExit -ne 0) {
        throw ('DISM CheckHealth failed with exit code {0}' -f $checkExit)
    }

    $finalStatus = 'DONE'
    $finalDetail = 'DISM, SFC, and CheckHealth completed'
} catch {
    $finalStatus = 'FAILED'
    $finalDetail = $_.Exception.Message
} finally {
    Write-State 'RESTORING_POLICY' $finalDetail
    try {
        if ($null -ne $registrySnapshot) { Restore-RegistrySnapshot $registrySnapshot }
        if ($null -ne $serviceSnapshot) { Restore-ServiceSnapshot $serviceSnapshot }
    } catch {
        $finalStatus = 'FAILED'
        $finalDetail = $finalDetail + '; policy restore failed: ' + $_.Exception.Message
    }
    Write-State $finalStatus $finalDetail
}

