$ErrorActionPreference = 'Continue'

function Get-EventRows {
    param(
        [string]$LogName,
        [scriptblock]$Predicate,
        [int]$Limit = 25
    )

    try {
        @(Get-WinEvent -FilterHashtable @{
            LogName = $LogName
            StartTime = (Get-Date).AddDays(-2)
        } -ErrorAction Stop |
            Where-Object $Predicate |
            Select-Object -First $Limit |
            ForEach-Object {
                $message = if ($_.Message) {
                    $_.Message.Substring(0, [Math]::Min(700, $_.Message.Length))
                } else { '' }
                [ordered]@{
                    time = $_.TimeCreated
                    id = $_.Id
                    provider = $_.ProviderName
                    message = $message
                    xml = if ($_.Id -in 41, 1001) {
                        $_.ToXml().Substring(0, [Math]::Min(2500, $_.ToXml().Length))
                    } else { $null }
                }
            })
    } catch { @() }
}

$system = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
$os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
$cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'" -ErrorAction SilentlyContinue
$pageFile = @(Get-CimInstance Win32_PageFileUsage -ErrorAction SilentlyContinue)

$criticalSystem = Get-EventRows -LogName System -Predicate {
    $_.ProviderName -match 'WHEA|BugCheck|WER-SystemErrorReporting|Kernel-Power|Disk|storahci|stornvme|volmgr|Ntfs' -and
    $_.Id -in 7, 9, 11, 15, 17, 18, 19, 20, 41, 46, 47, 51, 55, 129, 153, 161, 6008, 1001
}
$hangEvents = Get-EventRows -LogName Application -Predicate {
    $_.ProviderName -match '^Application Hang$|Windows Error Reporting' -and $_.Id -in 1001, 1002
} -Limit 15

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

$thermal = @(try {
    Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop |
        ForEach-Object {
            [ordered]@{
                instance = $_.InstanceName
                celsius = [Math]::Round(($_.CurrentTemperature / 10) - 273.15, 1)
            }
        }
} catch {})

$topMemory = @(Get-Process -ErrorAction SilentlyContinue |
    Sort-Object WorkingSet64 -Descending |
    Select-Object -First 10 |
    ForEach-Object {
        [ordered]@{
            name = $_.ProcessName
            id = $_.Id
            workingSetMb = [Math]::Round($_.WorkingSet64 / 1MB, 1)
            cpuSeconds = [Math]::Round($_.CPU, 1)
        }
    })

[ordered]@{
    computerName = $env:COMPUTERNAME
    collectedAt = Get-Date
    model = $system.Model
    lastBootUpTime = $os.LastBootUpTime
    uptimeMinutes = if ($os.LastBootUpTime) {
        [Math]::Round(((Get-Date) - $os.LastBootUpTime).TotalMinutes, 1)
    } else { $null }
    loadPercentage = $cpu.LoadPercentage
    freeMemoryMb = [Math]::Round($os.FreePhysicalMemory / 1KB, 1)
    totalMemoryMb = [Math]::Round($system.TotalPhysicalMemory / 1MB, 1)
    systemDriveFreeGb = if ($disk) { [Math]::Round($disk.FreeSpace / 1GB, 1) } else { $null }
    pageFile = @($pageFile | Select-Object Name, AllocatedBaseSize, CurrentUsage, PeakUsage)
    thermalZones = $thermal
    dumps = $dumps
    criticalSystemEvents = $criticalSystem
    hangEvents = $hangEvents
    topMemoryProcesses = $topMemory
} | ConvertTo-Json -Depth 8 -Compress
