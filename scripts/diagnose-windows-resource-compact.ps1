$ErrorActionPreference = 'Continue'

$os = Get-CimInstance Win32_OperatingSystem -ErrorAction SilentlyContinue
$system = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
$cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'" -ErrorAction SilentlyContinue
$pageFile = @(Get-CimInstance Win32_PageFileUsage -ErrorAction SilentlyContinue)

$dumps = @(
    Get-ChildItem 'C:/Windows/Minidump/*.dmp', 'C:/Windows/MEMORY.DMP' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 5 |
        ForEach-Object {
            [ordered]@{
                path = $_.FullName
                sizeMb = [Math]::Round($_.Length / 1MB, 1)
                lastWriteTime = $_.LastWriteTime
            }
        }
)

$thermal = @(try {
    Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction Stop |
        ForEach-Object {
            [Math]::Round(($_.CurrentTemperature / 10) - 273.15, 1)
        }
} catch {})

$critical = @(try {
    Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = (Get-Date).AddDays(-2) } -ErrorAction Stop |
        Where-Object {
            $_.ProviderName -match 'WHEA|BugCheck|WER-SystemErrorReporting|Kernel-Power|Disk|storahci|stornvme|volmgr|Ntfs' -and
            $_.Id -in 7, 9, 11, 15, 17, 18, 19, 20, 41, 46, 47, 51, 55, 129, 153, 161, 6008, 1001
        } |
        Select-Object -First 12 |
        ForEach-Object {
            $data = [ordered]@{}
            try {
                $xml = [xml]$_.ToXml()
                foreach ($node in @($xml.Event.EventData.Data)) {
                    if ($node.Name) { $data[[string]$node.Name] = [string]$node.'#text' }
                }
            } catch {}
            [ordered]@{
                time = $_.TimeCreated
                id = $_.Id
                provider = $_.ProviderName
                data = $data
            }
        }
} catch {})

$topMemory = @(Get-Process -ErrorAction SilentlyContinue |
    Sort-Object WorkingSet64 -Descending |
    Select-Object -First 8 |
    ForEach-Object {
        [ordered]@{
            name = $_.ProcessName
            workingSetMb = [Math]::Round($_.WorkingSet64 / 1MB, 1)
            cpuSeconds = [Math]::Round($_.CPU, 1)
        }
    })

$debuggers = @(
    @(
        'C:/Program Files (x86)/Windows Kits/10/Debuggers/x64/dumpchk.exe',
        'C:/Program Files (x86)/Windows Kits/10/Debuggers/x64/kd.exe',
        'C:/Program Files/WindowsApps/Microsoft.WinDbg*'
    ) | Where-Object { Test-Path $_ }
)

[ordered]@{
    computerName = $env:COMPUTERNAME
    collectedAt = Get-Date
    lastBootUpTime = $os.LastBootUpTime
    uptimeMinutes = if ($os.LastBootUpTime) {
        [Math]::Round(((Get-Date) - $os.LastBootUpTime).TotalMinutes, 1)
    } else { $null }
    loadPercentage = $cpu.LoadPercentage
    freeMemoryMb = [Math]::Round($os.FreePhysicalMemory / 1KB, 1)
    totalMemoryMb = [Math]::Round($system.TotalPhysicalMemory / 1MB, 1)
    systemDriveFreeGb = if ($disk) { [Math]::Round($disk.FreeSpace / 1GB, 1) } else { $null }
    pageFile = @($pageFile | ForEach-Object {
        [ordered]@{
            allocatedMb = $_.AllocatedBaseSize
            currentUsageMb = $_.CurrentUsage
            peakUsageMb = $_.PeakUsage
        }
    })
    thermalCelsius = $thermal
    dumps = $dumps
    criticalEvents = $critical
    topMemoryProcesses = $topMemory
    debuggerPaths = $debuggers
} | ConvertTo-Json -Depth 7 -Compress
