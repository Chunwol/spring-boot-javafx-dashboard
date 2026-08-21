param(
    [ValidateRange(3, 15)]
    [int] $CaptureSeconds = 5
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$outputRoot = 'C:/ProgramData/PCAgent/diagnostics'
$etlPath = Join-Path $outputRoot 'scmate-8081.etl'
$pcapPath = Join-Path $outputRoot 'scmate-8081.pcapng'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
Remove-Item -LiteralPath $etlPath, $pcapPath -Force -ErrorAction SilentlyContinue

try {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & pktmon.exe stop 2>$null | Out-Null
    & pktmon.exe filter remove 2>$null | Out-Null
    $ErrorActionPreference = $oldPreference
    & pktmon.exe filter add SCMATE -p 8081 | Out-Null
    & pktmon.exe start --capture --pkt-size 0 --file-name $etlPath | Out-Null
    Start-Sleep -Seconds $CaptureSeconds
    & pktmon.exe stop | Out-Null
    & pktmon.exe pcapng $etlPath -o $pcapPath | Out-Null

    $file = Get-Item -LiteralPath $pcapPath
    [ordered]@{
        computerName = $env:COMPUTERNAME
        captureSeconds = $CaptureSeconds
        outputPath = $file.FullName
        bytes = $file.Length
    } | ConvertTo-Json -Compress
} finally {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & pktmon.exe stop 2>$null | Out-Null
    & pktmon.exe filter remove 2>$null | Out-Null
    $ErrorActionPreference = $oldPreference
    Remove-Item -LiteralPath $etlPath -Force -ErrorAction SilentlyContinue
}
