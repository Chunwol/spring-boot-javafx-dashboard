$ErrorActionPreference = 'Stop'

$os = Get-CimInstance Win32_OperatingSystem
$events = @(Get-WinEvent -FilterHashtable @{
    LogName = 'System'
    Id = 41, 1001, 6008
    StartTime = (Get-Date).AddHours(-8)
} -ErrorAction SilentlyContinue |
    Sort-Object TimeCreated -Descending |
    Select-Object -First 12 |
    ForEach-Object {
        $message = [string]$_.Message
        [ordered]@{
            time = $_.TimeCreated
            id = $_.Id
            provider = $_.ProviderName
            message = $message.Substring(0, [Math]::Min(360, $message.Length))
        }
    })
$dumps = @(Get-ChildItem 'C:/Windows/Minidump/*.dmp' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 8 Name, Length, LastWriteTime)

[ordered]@{
    computerName = $env:COMPUTERNAME
    lastBootUpTime = $os.LastBootUpTime
    uptimeMinutes = [Math]::Round(((Get-Date) - $os.LastBootUpTime).TotalMinutes, 1)
    events = $events
    dumps = $dumps
} | ConvertTo-Json -Depth 5 -Compress
