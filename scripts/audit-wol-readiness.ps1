$ErrorActionPreference = 'Continue'

function Invoke-Safely {
    param([scriptblock]$Action)
    try { & $Action } catch { $null }
}

function Get-MatchingAdvancedProperties {
    param([string]$InterfaceDescription)

    $patterns = 'Wake|Magic|Shutdown|PME|Energy|Green|EEE|WOL'
    @(Invoke-Safely { Get-NetAdapterAdvancedProperty -InterfaceDescription $InterfaceDescription -AllProperties -ErrorAction Stop }) |
        Where-Object {
            $_.DisplayName -match $patterns -or
            $_.RegistryKeyword -match 'Wake|Magic|Shutdown|PME|EEE|Energy|Green'
        } |
        ForEach-Object {
            [ordered]@{
                displayName = $_.DisplayName
                displayValue = $_.DisplayValue
                registryKeyword = $_.RegistryKeyword
                registryValue = @($_.RegistryValue) -join ','
            }
        }
}

$system = Invoke-Safely { Get-CimInstance Win32_ComputerSystem -ErrorAction Stop }
$operatingSystem = Invoke-Safely { Get-CimInstance Win32_OperatingSystem -ErrorAction Stop }
$bios = Invoke-Safely { Get-CimInstance Win32_BIOS -ErrorAction Stop }
$physicalAdapters = @(Invoke-Safely { Get-NetAdapter -Physical -ErrorAction Stop })
$ipAdapters = @(Invoke-Safely {
    Get-NetIPConfiguration -ErrorAction Stop |
        Where-Object { $_.IPv4Address -and $_.NetAdapter }
})

$adapterRows = foreach ($adapter in $physicalAdapters) {
    $power = Invoke-Safely { Get-NetAdapterPowerManagement -InterfaceDescription $adapter.InterfaceDescription -ErrorAction Stop }
    $ipConfig = $ipAdapters | Where-Object { $_.InterfaceIndex -eq $adapter.ifIndex } | Select-Object -First 1
    $signedDriver = Invoke-Safely {
        Get-CimInstance Win32_PnPSignedDriver -Filter "DeviceID='$($adapter.PnPDeviceID.Replace('\', '\\'))'" -ErrorAction Stop |
            Select-Object -First 1
    }
    [ordered]@{
        name = $adapter.Name
        description = $adapter.InterfaceDescription
        status = [string]$adapter.Status
        macAddress = $adapter.MacAddress
        linkSpeed = [string]$adapter.LinkSpeed
        ipv4 = @($ipConfig.IPv4Address | ForEach-Object IPAddress)
        driverVersion = $adapter.DriverVersion
        driverDate = $adapter.DriverDate
        driverProvider = $signedDriver.DriverProviderName
        infName = $signedDriver.InfName
        pnpDeviceId = $adapter.PnPDeviceID
        powerManagement = if ($power) {
            [ordered]@{
                allowComputerToTurnOffDevice = [string]$power.AllowComputerToTurnOffDevice
                wakeOnMagicPacket = [string]$power.WakeOnMagicPacket
                wakeOnPattern = [string]$power.WakeOnPattern
                deviceSleepOnDisconnect = [string]$power.DeviceSleepOnDisconnect
            }
        } else { $null }
        advancedProperties = @(Get-MatchingAdvancedProperties -InterfaceDescription $adapter.InterfaceDescription)
    }
}

$wakeArmed = @(Invoke-Safely { powercfg.exe /devicequery wake_armed 2>$null }) |
    Where-Object { $_ -and $_ -match 'Ethernet|GbE|Realtek|I219' }
$wakeProgrammable = @(Invoke-Safely { powercfg.exe /devicequery wake_programmable 2>$null }) |
    Where-Object { $_ -and $_ -match 'Ethernet|GbE|Realtek|I219' }

$allLenovoSettings = @(Invoke-Safely {
    Get-CimInstance -Namespace root/wmi -ClassName Lenovo_BiosSetting -ErrorAction Stop |
        ForEach-Object CurrentSetting |
        Where-Object { $_ } |
        Sort-Object
})
$lenovoSettings = @($allLenovoSettings | Where-Object { $_ -match 'Wake|LAN|ErP|Deep|Sleep|Power' })
$lenovoFingerprint = if ($allLenovoSettings.Count -gt 0) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        ([BitConverter]::ToString($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes(($allLenovoSettings -join "`n"))))).Replace('-', '')
    } finally {
        $sha256.Dispose()
    }
} else { $null }

$recentPowerEvents = @(Invoke-Safely {
    Get-WinEvent -FilterHashtable @{ LogName = 'System'; Id = 41, 1074, 6006, 6008 } -MaxEvents 4 -ErrorAction Stop |
        ForEach-Object {
            [ordered]@{
                time = $_.TimeCreated
                id = $_.Id
                provider = $_.ProviderName
                message = if ($_.Message) { $_.Message.Substring(0, [Math]::Min(160, $_.Message.Length)) } else { '' }
            }
        }
})

$dellSettings = @(Invoke-Safely {
    Get-CimInstance -Namespace root/dcim/sysman/biosattributes -ClassName EnumerationAttribute -ErrorAction Stop |
        Where-Object { $_.AttributeName -match 'Wake|LAN|Deep|Sleep|Block' } |
        ForEach-Object { "{0}={1}" -f $_.AttributeName, $_.CurrentValue }
})

$hiberboot = Invoke-Safely {
    (Get-ItemProperty 'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager/Power' -Name HiberbootEnabled -ErrorAction Stop).HiberbootEnabled
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    manufacturer = $system.Manufacturer
    model = $system.Model
    wakeUpType = $system.WakeUpType
    biosVersion = $bios.SMBIOSBIOSVersion
    biosReleaseDate = $bios.ReleaseDate
    lastBootUpTime = $operatingSystem.LastBootUpTime
    fastStartupEnabled = $hiberboot
    wakeArmed = $wakeArmed
    wakeProgrammable = $wakeProgrammable
    lenovoBiosSettings = $lenovoSettings
    lenovoBiosFingerprint = $lenovoFingerprint
    dellBiosSettings = $dellSettings
    recentPowerEvents = $recentPowerEvents
    adapters = @($adapterRows)
} | ConvertTo-Json -Depth 8 -Compress
