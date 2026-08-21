$ErrorActionPreference = 'Continue'

$registryPath = 'HKLM:/SOFTWARE/WOW6432Node/SCMATE'
$settings = if (Test-Path $registryPath) { Get-ItemProperty $registryPath } else { $null }
$serverHost = [string]$settings.SCMateServerHOST
$serverPort = 0
$null = [int]::TryParse([string]$settings.SCMateServerPORT, [ref]$serverPort)

$processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'SCMate|ShdTray|ShdServ|Shield' -or
        $_.ExecutablePath -match 'SCMATE_RX2Client'
    } |
    Select-Object Name, ProcessId, SessionId, ExecutablePath)

$connections = @()
if ($serverHost -and $serverPort -gt 0) {
    $connections = @(Get-NetTCPConnection -ErrorAction SilentlyContinue |
        Where-Object { $_.RemoteAddress -eq $serverHost -and $_.RemotePort -eq $serverPort } |
        Select-Object State, LocalAddress, LocalPort, RemoteAddress, RemotePort, OwningProcess)
}

$connectionStates = @($connections |
    Group-Object State |
    ForEach-Object { [ordered]@{ state = [string]$_.Name; count = $_.Count } })
$activeConnections = @($connections |
    Where-Object State -In 'Established', 'SynSent', 'SynReceived' |
    Select-Object -First 10)

$probe = $null
if ($serverHost -and $serverPort -gt 0) {
    $probe = Test-NetConnection -ComputerName $serverHost -Port $serverPort -InformationLevel Quiet -WarningAction SilentlyContinue
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    serverHost = $serverHost
    serverPort = $serverPort
    serverPortReachable = $probe
    processes = $processes
    connectionStates = $connectionStates
    activeConnections = $activeConnections
} | ConvertTo-Json -Depth 5 -Compress
