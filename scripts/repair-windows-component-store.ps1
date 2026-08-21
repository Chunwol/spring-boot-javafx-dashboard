$ErrorActionPreference = 'Stop'

$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics\windows-repair'
$statePath = Join-Path $root 'repair.state'
$dismOutput = Join-Path $root 'dism-restore.txt'
$sfcOutput = Join-Path $root 'sfc-scannow.txt'
New-Item -ItemType Directory -Path $root -Force | Out-Null

function Write-State([string]$Status, [string]$Detail = '') {
    @(
        'status=' + $Status
        'updatedAt=' + (Get-Date).ToString('o')
        'pid=' + $PID
        'detail=' + ($Detail -replace '[\r\n]+', ' ')
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
}

try {
    Write-State 'DISM_RESTORE' 'RestoreHealth LimitAccess'
    $dism = & dism.exe /Online /Cleanup-Image /RestoreHealth /LimitAccess /English 2>&1
    $dismExit = $LASTEXITCODE
    $dism | Set-Content -LiteralPath $dismOutput -Encoding utf8
    if ($dismExit -ne 0) {
        Write-State 'FAILED' ("dismExit=$dismExit")
        exit $dismExit
    }

    Write-State 'SFC_SCAN' 'scannow'
    $sfc = & sfc.exe /scannow 2>&1
    $sfcExit = $LASTEXITCODE
    $sfc | Set-Content -LiteralPath $sfcOutput -Encoding utf8
    if ($sfcExit -ne 0) {
        Write-State 'FAILED' ("dismExit=$dismExit;sfcExit=$sfcExit")
        exit $sfcExit
    }

    Write-State 'DONE' ("dismExit=$dismExit;sfcExit=$sfcExit")
} catch {
    Write-State 'FAILED' $_.Exception.Message
    throw
}

