$ErrorActionPreference = 'Stop'

$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics'
$outputPath = Join-Path $root 'chkdsk-c-scan.txt'
$statePath = Join-Path $root 'chkdsk-c-scan.state'
New-Item -ItemType Directory -Path $root -Force | Out-Null

@(
    'status=RUNNING'
    'startedAt=' + (Get-Date).ToString('o')
    'pid=' + $PID
) | Set-Content -LiteralPath $statePath -Encoding ascii

try {
    $output = & chkdsk.exe C: /scan /forceofflinefix /perf 2>&1
    $exitCode = $LASTEXITCODE
    $output | Set-Content -LiteralPath $outputPath -Encoding utf8
    @(
        'status=DONE'
        'startedAt=' + (Get-Content -LiteralPath $statePath |
            Where-Object { $_ -like 'startedAt=*' } | Select-Object -First 1).Substring(10)
        'completedAt=' + (Get-Date).ToString('o')
        'exitCode=' + $exitCode
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
} catch {
    @(
        'status=FAILED'
        'completedAt=' + (Get-Date).ToString('o')
        'error=' + ($_.Exception.Message -replace '[\r\n]+', ' ')
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
    throw
}
