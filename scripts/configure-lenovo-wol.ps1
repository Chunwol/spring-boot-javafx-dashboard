$ErrorActionPreference = 'Stop'

function Set-LenovoBiosSetting {
    param([string]$Setting)

    $setter = Get-WmiObject -Namespace root/wmi -Class Lenovo_SetBiosSetting -ErrorAction Stop
    $result = $setter.SetBiosSetting($Setting)
    if ($result.return -notin 'Success', 'Item is show only, inaccessible') {
        throw "Lenovo BIOS setting failed: $Setting ($($result.return))"
    }
}

$system = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
if ($system.Manufacturer -notmatch 'LENOVO' -or $system.Model -ne '11LAA00DKR') {
    throw "Unsupported target: $($system.Manufacturer) $($system.Model)"
}

$adapter = Get-NetAdapter -Physical -ErrorAction Stop |
    Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -match 'Realtek.*Ethernet|Realtek.*GbE' } |
    Select-Object -First 1
if (-not $adapter) {
    throw 'Active Realtek wired adapter not found.'
}

$before = Get-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription -AllProperties -ErrorAction Stop
$desired = [ordered]@{
    'S5WakeOnLan' = 1
    '*WakeOnMagicPacket' = 1
    '*WakeOnPattern' = 1
    'WolShutdownLinkSpeed' = 2
    'PowerSavingMode' = 0
    '*EEE' = 0
    'EnableGreenEthernet' = 0
    'AdvancedEEE' = 0
}
$changed = @()
$warnings = @()

$biosBefore = @(Get-CimInstance -Namespace root/wmi -ClassName Lenovo_BiosSetting -ErrorAction Stop |
    ForEach-Object CurrentSetting |
    Where-Object { $_ -match '^WakeonLAN,|^EnhancedPowerSavingMode,' })
if (-not ($biosBefore -match '^WakeonLAN,Primary;')) {
    Set-LenovoBiosSetting 'WakeonLAN,Primary'
}
if (-not ($biosBefore -match '^EnhancedPowerSavingMode,Disabled;')) {
    Set-LenovoBiosSetting 'EnhancedPowerSavingMode,Disabled'
}
$save = Get-WmiObject -Namespace root/wmi -Class Lenovo_SaveBiosSettings -ErrorAction Stop
$saveResult = $save.SaveBiosSettings()
if ($saveResult.return -ne 'Success') {
    throw "Lenovo BIOS save failed: $($saveResult.return)"
}

Set-ItemProperty 'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager/Power' `
    -Name HiberbootEnabled -Type DWord -Value 0 -Force

foreach ($entry in $desired.GetEnumerator()) {
    $property = $before | Where-Object RegistryKeyword -EQ $entry.Key | Select-Object -First 1
    if (-not $property) { continue }
    $current = @($property.RegistryValue) -join ','
    if ($current -eq [string]$entry.Value) { continue }
    Set-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription `
        -RegistryKeyword $entry.Key -RegistryValue $entry.Value -NoRestart -ErrorAction Stop
    $changed += "$($entry.Key):$current->$($entry.Value)"
}

try {
    Set-NetAdapterPowerManagement -InterfaceDescription $adapter.InterfaceDescription `
        -WakeOnMagicPacket Enabled -WakeOnPattern Enabled -NoRestart -ErrorAction Stop
    powercfg.exe /deviceenablewake $adapter.InterfaceDescription | Out-Null
} catch {
    $warnings += "power-management:$($_.Exception.Message)"
}

if ($changed.Count -gt 0) {
    Restart-NetAdapter -InterfaceDescription $adapter.InterfaceDescription -Confirm:$false -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        $adapter = Get-NetAdapter -InterfaceDescription $adapter.InterfaceDescription -ErrorAction SilentlyContinue
    } while ($adapter.Status -ne 'Up' -and (Get-Date) -lt $deadline)
    if ($adapter.Status -ne 'Up') {
        throw 'Wired adapter did not return to Up state within 60 seconds.'
    }
}

$after = Get-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription -AllProperties -ErrorAction Stop
$afterValues = [ordered]@{}
foreach ($key in $desired.Keys) {
    $property = $after | Where-Object RegistryKeyword -EQ $key | Select-Object -First 1
    if ($property) { $afterValues[$key] = @($property.RegistryValue) -join ',' }
}
$biosAfter = @(Get-CimInstance -Namespace root/wmi -ClassName Lenovo_BiosSetting -ErrorAction Stop |
    ForEach-Object CurrentSetting |
    Where-Object { $_ -match '^WakeonLAN,|^EnhancedPowerSavingMode,' })

[ordered]@{
    computerName = $env:COMPUTERNAME
    adapter = $adapter.InterfaceDescription
    adapterStatus = [string]$adapter.Status
    changed = $changed
    warnings = $warnings
    advancedProperties = $afterValues
    wakeArmed = @(powercfg.exe /devicequery wake_armed 2>$null) -contains $adapter.InterfaceDescription
    fastStartupEnabled = (Get-ItemProperty 'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager/Power').HiberbootEnabled
    biosSettings = $biosAfter
    rebooted = $false
} | ConvertTo-Json -Depth 5 -Compress
