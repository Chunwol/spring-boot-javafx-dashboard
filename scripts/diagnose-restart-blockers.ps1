#Requires -Version 5.1

$ErrorActionPreference = 'SilentlyContinue'
$since = (Get-Date).AddDays(-2)

function Read-Events([string]$LogName, [int[]]$Ids, [int]$Limit = 10) {
    @(Get-WinEvent -FilterHashtable @{ LogName = $LogName; Id = $Ids; StartTime = $since } -ErrorAction SilentlyContinue |
        Select-Object -First $Limit |
        ForEach-Object {
            [ordered]@{
                at = $_.TimeCreated.ToString('o')
                id = $_.Id
                message = (($_.Message -replace '\s+', ' ').Trim()).Substring(
                    0, [Math]::Min(180, (($_.Message -replace '\s+', ' ').Trim()).Length))
            }
        })
}

$pendingRename = (Get-ItemProperty `
    'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' `
    -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations
$rebootPending = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending'
$updateRebootRequired = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired'

$watchedServices = @(Get-CimInstance Win32_Service | Where-Object {
    $_.Name -match 'SCMATE|Shield|wuauserv|bits|UsoSvc|TrustedInstaller|msiserver' -or
    $_.DisplayName -match 'SCMATE|Shield'
} | Select-Object -First 20 Name, State, StartMode, ProcessId)

$watchedProcesses = @(Get-Process | Where-Object {
    $_.ProcessName -match 'SCMATE|Shield|TiWorker|MoUsoCoreWorker|TrustedInstaller|msiexec|OfficeClickToRun|EXCEL|WINWORD|devenv'
} | Sort-Object WorkingSet64 -Descending | Select-Object -First 20 `
    ProcessName, Id, @{n='workingSetMb';e={[Math]::Round($_.WorkingSet64 / 1MB, 1)}}, CPU)

$os = Get-CimInstance Win32_OperatingSystem
$result = [ordered]@{
    computerName = $env:COMPUTERNAME
    capturedAt = (Get-Date).ToString('o')
    lastBootAt = if ($os) { $os.LastBootUpTime.ToString('o') } else { $null }
    pendingRestart = [ordered]@{
        componentServicing = $rebootPending
        windowsUpdate = $updateRebootRequired
        pendingFileRenames = @($pendingRename).Count
    }
    services = $watchedServices
    processes = $watchedProcesses
    shutdownEvents = Read-Events 'System' @(1074, 6006, 6008, 41) 8
    performanceEvents = Read-Events 'Microsoft-Windows-Diagnostics-Performance/Operational' @(100, 101, 102, 103, 106, 107, 108, 109, 110, 200, 201, 202, 203) 8
    updateEvents = Read-Events 'Microsoft-Windows-WindowsUpdateClient/Operational' @(19, 20, 21, 25, 26, 31, 34, 35, 40, 41, 43, 44) 6
}

$result | ConvertTo-Json -Depth 6 -Compress
