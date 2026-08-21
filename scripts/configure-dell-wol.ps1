$ErrorActionPreference = 'Stop'

$system = Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
if ($system.Manufacturer -notmatch '^Dell' -or $system.Model -ne 'Precision 3640 Tower') {
    throw "Unsupported target: $($system.Manufacturer) $($system.Model)"
}

$namespace = 'root/dcim/sysman/biosattributes'
$bios = Get-WmiObject -Namespace $namespace -Class BIOSAttributeInterface -ErrorAction Stop

function Set-DellBiosAttribute {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value
    )

    $attribute = Get-CimInstance -Namespace $namespace -ClassName EnumerationAttribute -ErrorAction Stop |
        Where-Object AttributeName -EQ $Name |
        Select-Object -First 1
    if (-not $attribute) {
        throw "Dell BIOS attribute not found: $Name"
    }
    if ($attribute.PossibleValue -notcontains $Value) {
        throw "Dell BIOS attribute does not support $Name=$Value"
    }
    if ($attribute.CurrentValue -eq $Value) {
        return $false
    }

    $result = $bios.SetAttribute(0, 0, 0, $Name, $Value)
    if ($result.Status -ne 0) {
        throw "Dell BIOS attribute update failed: $Name=$Value (status=$($result.Status))"
    }
    return $true
}

$changed = @()
if (Set-DellBiosAttribute -Name 'DeepSleepCtrl' -Value 'Disabled') {
    $changed += 'DeepSleepCtrl'
}
if (Set-DellBiosAttribute -Name 'WakeOnLan' -Value 'LanOnly') {
    $changed += 'WakeOnLan'
}

Set-ItemProperty 'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager/Power' `
    -Name HiberbootEnabled -Type DWord -Value 0 -Force

$adapter = Get-NetAdapter -Physical -ErrorAction Stop |
    Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -match 'Intel.*I219-LM' } |
    Select-Object -First 1
if (-not $adapter) {
    throw 'Active Intel I219-LM adapter not found.'
}

$advanced = Get-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription `
    -AllProperties -ErrorAction Stop
foreach ($setting in @(
    @{ Key = 'EnablePME'; Value = 1 },
    @{ Key = '*WakeOnMagicPacket'; Value = 1 }
)) {
    $property = $advanced | Where-Object RegistryKeyword -EQ $setting.Key | Select-Object -First 1
    if ($property -and (@($property.RegistryValue) -join ',') -ne [string]$setting.Value) {
        Set-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription `
            -RegistryKeyword $setting.Key -RegistryValue $setting.Value -NoRestart -ErrorAction Stop
        $changed += $setting.Key
    }
}

Set-NetAdapterPowerManagement -InterfaceDescription $adapter.InterfaceDescription `
    -WakeOnMagicPacket Enabled -NoRestart -ErrorAction Stop
powercfg.exe /deviceenablewake $adapter.InterfaceDescription | Out-Null

$biosAfter = Get-CimInstance -Namespace $namespace -ClassName EnumerationAttribute -ErrorAction Stop |
    Where-Object AttributeName -In @('DeepSleepCtrl', 'WakeOnLan')
$powerAfter = Get-NetAdapterPowerManagement -InterfaceDescription $adapter.InterfaceDescription -ErrorAction Stop
$advancedAfter = Get-NetAdapterAdvancedProperty -InterfaceDescription $adapter.InterfaceDescription `
    -AllProperties -ErrorAction Stop

[ordered]@{
    computerName = $env:COMPUTERNAME
    changed = $changed
    bios = @($biosAfter | ForEach-Object {
        [ordered]@{ name = $_.AttributeName; value = $_.CurrentValue }
    })
    adapter = $adapter.InterfaceDescription
    wakeOnMagicPacket = [string]$powerAfter.WakeOnMagicPacket
    enablePme = [string](($advancedAfter | Where-Object RegistryKeyword -EQ 'EnablePME' |
        Select-Object -First 1).RegistryValue)
    advancedMagicPacket = [string](($advancedAfter | Where-Object RegistryKeyword -EQ '*WakeOnMagicPacket' |
        Select-Object -First 1).RegistryValue)
    wakeArmed = @(powercfg.exe /devicequery wake_armed 2>$null) -contains $adapter.InterfaceDescription
    fastStartupEnabled = (Get-ItemProperty `
        'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager/Power').HiberbootEnabled
    rebooted = $false
} | ConvertTo-Json -Depth 5 -Compress
