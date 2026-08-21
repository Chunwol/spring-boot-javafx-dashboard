param(
    [ValidateRange(5, 60)]
    [int] $SampleSeconds = 30
)

$ErrorActionPreference = 'Continue'
$port = 8081
$seen = @{}
$deadline = (Get-Date).AddSeconds($SampleSeconds)

do {
    Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.RemoteAddress -and $_.RemoteAddress -notin '0.0.0.0', '::' } |
        ForEach-Object {
            $key = [string]$_.RemoteAddress
            if (-not $seen.ContainsKey($key)) {
                $seen[$key] = [ordered]@{
                    remoteAddress = $key
                    states = @{}
                }
            }
            $state = [string]$_.State
            $seen[$key].states[$state] = 1 + [int]$seen[$key].states[$state]
        }
    Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline)

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
$clients = @($seen.Values |
    Sort-Object remoteAddress |
    ForEach-Object {
        [ordered]@{
            remoteAddress = $_.remoteAddress
            states = @($_.states.GetEnumerator() | Sort-Object Name |
                ForEach-Object { [ordered]@{ state = $_.Name; samples = $_.Value } })
        }
    })

[ordered]@{
    computerName = $env:COMPUTERNAME
    localPort = $port
    listening = [bool]$listener
    sampleSeconds = $SampleSeconds
    uniqueClientCount = $clients.Count
    clients = $clients
} | ConvertTo-Json -Depth 5 -Compress
