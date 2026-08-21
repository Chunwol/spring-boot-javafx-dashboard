$ErrorActionPreference = 'Continue'

$processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'SCMate|Firebird|fbserver|fbguard|WASServer' -or
        $_.ExecutablePath -match 'SCMATE_RX2Server|Firebird'
    })
$byId = @{}
foreach ($process in $processes) { $byId[[int]$process.ProcessId] = $process }

$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $byId.ContainsKey([int]$_.OwningProcess) } |
    ForEach-Object {
        $process = $byId[[int]$_.OwningProcess]
        [ordered]@{
            processName = $process.Name
            processId = $process.ProcessId
            executablePath = $process.ExecutablePath
            localAddress = $_.LocalAddress
            localPort = $_.LocalPort
        }
    } |
    Sort-Object processName, localPort)

[ordered]@{
    computerName = $env:COMPUTERNAME
    listeners = $listeners
} | ConvertTo-Json -Depth 4 -Compress
