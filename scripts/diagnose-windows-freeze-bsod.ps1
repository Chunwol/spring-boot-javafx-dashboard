$ErrorActionPreference = 'Continue'

function Invoke-Safely {
    param([scriptblock]$Action)
    try { & $Action } catch { $null }
}

$since = (Get-Date).AddHours(-8)
$system = Invoke-Safely { Get-CimInstance Win32_ComputerSystem -ErrorAction Stop }
$os = Invoke-Safely { Get-CimInstance Win32_OperatingSystem -ErrorAction Stop }
$cpu = Invoke-Safely { Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1 }
$disk = Invoke-Safely { Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'" -ErrorAction Stop }
$pageFile = Invoke-Safely { Get-CimInstance Win32_PageFileUsage -ErrorAction Stop }

$systemEventIds = 7, 9, 11, 15, 41, 51, 55, 129, 153, 161, 6008, 1001, 17, 18, 19, 20, 46, 47
$systemEvents = @(Invoke-Safely {
    Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = $since } -ErrorAction Stop |
        Where-Object { $_.Id -in $systemEventIds } |
        Select-Object -First 80 |
        ForEach-Object {
            [ordered]@{
                time = $_.TimeCreated
                id = $_.Id
                provider = $_.ProviderName
                message = if ($_.Message) {
                    $_.Message.Substring(0, [Math]::Min(500, $_.Message.Length))
                } else { '' }
            }
        }
})

$applicationEvents = @(Invoke-Safely {
    Get-WinEvent -FilterHashtable @{ LogName = 'Application'; StartTime = $since } -ErrorAction Stop |
        Where-Object {
            $_.Id -in 1000, 1001, 1002 -or
            $_.ProviderName -match 'Application Hang|Windows Error Reporting'
        } |
        Select-Object -First 80 |
        ForEach-Object {
            [ordered]@{
                time = $_.TimeCreated
                id = $_.Id
                provider = $_.ProviderName
                message = if ($_.Message) {
                    $_.Message.Substring(0, [Math]::Min(500, $_.Message.Length))
                } else { '' }
            }
        }
})

$dumps = @(
    Get-ChildItem 'C:/Windows/Minidump/*.dmp', 'C:/Windows/MEMORY.DMP' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 10 |
        ForEach-Object {
            [ordered]@{
                path = $_.FullName
                size = $_.Length
                lastWriteTime = $_.LastWriteTime
            }
        }
)

$thermal = @(Invoke-Safely {
    Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop |
        ForEach-Object {
            [ordered]@{
                instance = $_.InstanceName
                celsius = [Math]::Round(($_.CurrentTemperature / 10) - 273.15, 1)
            }
        }
})

$topProcesses = @(Invoke-Safely {
    Get-Process -ErrorAction Stop |
        Sort-Object CPU -Descending |
        Select-Object -First 12 |
        ForEach-Object {
            [ordered]@{
                name = $_.ProcessName
                id = $_.Id
                cpuSeconds = [Math]::Round($_.CPU, 1)
                workingSetMb = [Math]::Round($_.WorkingSet64 / 1MB, 1)
            }
        }
})

[ordered]@{
    computerName = $env:COMPUTERNAME
    collectedAt = Get-Date
    manufacturer = $system.Manufacturer
    model = $system.Model
    lastBootUpTime = $os.LastBootUpTime
    uptimeMinutes = if ($os.LastBootUpTime) {
        [Math]::Round(((Get-Date) - $os.LastBootUpTime).TotalMinutes, 1)
    } else { $null }
    cpu = $cpu.Name
    loadPercentage = $cpu.LoadPercentage
    freeMemoryMb = [Math]::Round($os.FreePhysicalMemory / 1KB, 1)
    totalMemoryMb = [Math]::Round($system.TotalPhysicalMemory / 1MB, 1)
    systemDriveFreeGb = if ($disk) { [Math]::Round($disk.FreeSpace / 1GB, 1) } else { $null }
    pageFile = @($pageFile | Select-Object Name, AllocatedBaseSize, CurrentUsage, PeakUsage)
    thermalZones = $thermal
    dumps = $dumps
    systemEvents = $systemEvents
    applicationEvents = $applicationEvents
    topProcesses = $topProcesses
} | ConvertTo-Json -Depth 8 -Compress
