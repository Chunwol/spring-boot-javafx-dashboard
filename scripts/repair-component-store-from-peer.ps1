[CmdletBinding()]
param(
    [switch]$Worker,
    [string]$Source = '\\216_T\PCAgentRepair$'
)

$ErrorActionPreference = 'Stop'

$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics\peer-source-repair'
$statePath = Join-Path $root 'repair.state'
$workerPath = Join-Path $root 'repair.worker.ps1'
$dismOutputPath = Join-Path $root 'dism-restore.txt'
$dismErrorPath = Join-Path $root 'dism-restore.err.txt'
$sfcOutputPath = Join-Path $root 'sfc-scannow.txt'
$sfcErrorPath = Join-Path $root 'sfc-scannow.err.txt'
$checkOutputPath = Join-Path $root 'dism-checkhealth.txt'
$credentialTarget = '216_T'
$credentialUserName = 'PCAgentRepairUser'
$credentialPasswordName = 'PCAgentRepairPassword'
$hostsPath = Join-Path $env:windir 'System32\drivers\etc\hosts'
$hostsMarker = '# PCAgentRepair peer source'

New-Item -ItemType Directory -Path $root -Force | Out-Null

function Write-State([string]$Status, [string]$Detail = '', [int]$ProcessId = $PID) {
    @(
        'status=' + $Status
        'updatedAt=' + (Get-Date).ToString('o')
        'pid=' + $ProcessId
        'detail=' + ($Detail -replace '[\r\n]+', ' ')
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
}

if (-not $Worker) {
    Copy-Item -LiteralPath $PSCommandPath -Destination $workerPath -Force
    Write-State 'LAUNCHING' ('Peer repair source: ' + $Source) 0
    $powershell = Join-Path $env:windir 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $process = Start-Process -FilePath $powershell -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $workerPath + '"'),
        '-Worker', '-Source', ('"' + $Source + '"')
    ) -WindowStyle Hidden -PassThru
    $currentState = Get-Content -LiteralPath $statePath -ErrorAction SilentlyContinue
    if ($currentState -contains 'status=LAUNCHING') {
        Write-State 'LAUNCHED' 'Peer-source repair worker started' $process.Id
    }
    Write-Output ('PEER_SOURCE_REPAIR launched=True pid={0}' -f $process.Id)
    exit 0
}

$finalStatus = 'FAILED'
$finalDetail = 'Peer-source repair did not start'

try {
    Write-State 'CONNECTING_SOURCE' $Source
    $sourceUser = [Environment]::GetEnvironmentVariable($credentialUserName, 'Machine')
    $sourcePassword = [Environment]::GetEnvironmentVariable($credentialPasswordName, 'Machine')
    if ([string]::IsNullOrWhiteSpace($sourceUser) -or [string]::IsNullOrWhiteSpace($sourcePassword)) {
        throw 'Peer repair credentials are unavailable'
    }
    Remove-SmbMapping -RemotePath $Source -Force -UpdateProfile -ErrorAction SilentlyContinue
    New-SmbMapping -RemotePath $Source -UserName $sourceUser -Password $sourcePassword `
        -Persistent $false -ErrorAction Stop | Out-Null

    $requiredFiles = @(
        'servicing\Packages\Microsoft-OneCore-Graphics-Tools-Package~31bf3856ad364e35~wow64~~10.0.26100.8737.mum',
        'servicing\Packages\Microsoft-Windows-Ge-Client-Server-26200-Version-Enablement-Payload-Package~31bf3856ad364e35~amd64~~10.0.26100.6717.mum'
    )
    foreach ($relativePath in $requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $Source $relativePath) -PathType Leaf)) {
            throw "Required repair package is missing from peer: $relativePath"
        }
    }

    Write-State 'DISM_PEER_SOURCE' ('RestoreHealth source=' + $Source)
    $dism = Start-Process -FilePath "$env:windir\System32\dism.exe" -ArgumentList @(
        '/Online', '/Cleanup-Image', '/RestoreHealth', ('/Source:' + $Source), '/LimitAccess', '/English'
    ) -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $dismOutputPath -RedirectStandardError $dismErrorPath
    if ($dism.ExitCode -ne 0) {
        throw ('DISM failed with exit code {0}' -f $dism.ExitCode)
    }

    Write-State 'SFC_SCAN' 'System file verification and repair'
    $sfc = Start-Process -FilePath "$env:windir\System32\sfc.exe" -ArgumentList '/scannow' `
        -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $sfcOutputPath -RedirectStandardError $sfcErrorPath
    if ($sfc.ExitCode -ne 0) {
        throw ('SFC failed with exit code {0}' -f $sfc.ExitCode)
    }

    Write-State 'CHECK_HEALTH' 'Verifying component store health'
    $check = & "$env:windir\System32\dism.exe" /Online /Cleanup-Image /CheckHealth /English 2>&1
    $checkExit = $LASTEXITCODE
    $check | Set-Content -LiteralPath $checkOutputPath -Encoding utf8
    if ($checkExit -ne 0 -or [string]($check -join "`n") -notmatch 'No component store corruption detected') {
        throw ('DISM CheckHealth did not confirm a healthy store; exit={0}' -f $checkExit)
    }

    $finalStatus = 'DONE'
    $finalDetail = 'Peer-source DISM, SFC, and CheckHealth completed'
} catch {
    $finalStatus = 'FAILED'
    $finalDetail = $_.Exception.Message
} finally {
    Write-State 'DISCONNECTING_SOURCE' $finalDetail
    Remove-SmbMapping -RemotePath $Source -Force -UpdateProfile -ErrorAction SilentlyContinue
    & "$env:windir\System32\cmd.exe" /d /c ('cmdkey /delete:' + $credentialTarget + ' >nul 2>&1')
    $hostsLines = @(Get-Content -LiteralPath $hostsPath -ErrorAction SilentlyContinue |
        Where-Object { $_ -notmatch [regex]::Escape($hostsMarker) })
    $hostsLines | Set-Content -LiteralPath $hostsPath -Encoding ascii
    [Environment]::SetEnvironmentVariable($credentialUserName, $null, 'Machine')
    [Environment]::SetEnvironmentVariable($credentialPasswordName, $null, 'Machine')
    $sourcePassword = $null
    Write-State $finalStatus $finalDetail
}
