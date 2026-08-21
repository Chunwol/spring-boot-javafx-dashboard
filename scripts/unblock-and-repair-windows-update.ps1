[CmdletBinding()]
param(
    [switch]$Worker
)

$ErrorActionPreference = 'Stop'

$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics\windows-update-repair'
$statePath = Join-Path $root 'repair.state'
$workerPath = Join-Path $root 'repair.worker.ps1'
$dismOutputPath = Join-Path $root 'dism-restore.txt'
$dismErrorPath = Join-Path $root 'dism-restore.err.txt'
$sfcOutputPath = Join-Path $root 'sfc-scannow.txt'
$sfcErrorPath = Join-Path $root 'sfc-scannow.err.txt'
$checkOutputPath = Join-Path $root 'dism-checkhealth.txt'

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
    Write-State 'LAUNCHING' 'Remove Windows Update blocks and repair component store' 0
    $powershell = Join-Path $env:windir 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $process = Start-Process -FilePath $powershell -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $workerPath + '"'), '-Worker'
    ) -WindowStyle Hidden -PassThru
    $currentState = Get-Content -LiteralPath $statePath -ErrorAction SilentlyContinue
    if ($currentState -contains 'status=LAUNCHING') {
        Write-State 'LAUNCHED' 'Windows Update repair worker started' $process.Id
    }
    Write-Output ('WINDOWS_UPDATE_REPAIR launched=True pid={0}' -f $process.Id)
    exit 0
}

function Set-ServiceStart([string]$Name, [int]$Start, [int]$DelayedAutoStart = -1) {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name"
    if (-not (Test-Path -LiteralPath $path)) { return }
    $scMode = switch ($Start) {
        2 { 'auto' }
        3 { 'demand' }
        4 { 'disabled' }
        default { throw "Unsupported service start value: $Start" }
    }
    & "$env:windir\System32\sc.exe" config $Name start= $scMode | Out-Null
    if ($LASTEXITCODE -ne 0) {
        New-ItemProperty -LiteralPath $path -Name Start -PropertyType DWord -Value $Start -Force | Out-Null
    }
    if ($DelayedAutoStart -ge 0) {
        New-ItemProperty -LiteralPath $path -Name DelayedAutoStart -PropertyType DWord `
            -Value $DelayedAutoStart -Force | Out-Null
    }
}

function Stop-UpdateServices {
    foreach ($name in @('UsoSvc', 'wuauserv', 'BITS', 'DoSvc', 'InstallService', 'CryptSvc')) {
        Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
    }
}

function Start-UpdateServices {
    foreach ($name in @('CryptSvc', 'BITS', 'wuauserv', 'DoSvc', 'InstallService', 'UsoSvc')) {
        Start-Service -Name $name -ErrorAction SilentlyContinue
    }
}

function Move-UpdateCache([string]$Path, [string]$Suffix) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $destination = $Path + '.repair-' + $Suffix
    try {
        if (Test-Path -LiteralPath $destination) {
            Remove-Item -LiteralPath $destination -Recurse -Force -ErrorAction SilentlyContinue
        }
        Move-Item -LiteralPath $Path -Destination $destination -Force
    } catch {
        Add-Content -LiteralPath (Join-Path $root 'cache-reset-warnings.txt') `
            -Value ((Get-Date).ToString('o') + ' ' + $Path + ': ' + $_.Exception.Message)
    }
}

try {
    Write-State 'UNBLOCKING' 'Removing Windows Update policies and enabling services'

    $windowsUpdatePolicy = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate'
    if (Test-Path -LiteralPath $windowsUpdatePolicy) {
        Remove-Item -LiteralPath $windowsUpdatePolicy -Recurse -Force
    }

    $servicingPolicy = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\Servicing'
    New-Item -Path $servicingPolicy -Force | Out-Null
    New-ItemProperty -LiteralPath $servicingPolicy -Name RepairContentServerSource `
        -PropertyType DWord -Value 2 -Force | Out-Null
    Remove-ItemProperty -LiteralPath $servicingPolicy -Name LocalSourcePath -Force -ErrorAction SilentlyContinue

    Set-ServiceStart 'BITS' 3
    Set-ServiceStart 'wuauserv' 3
    Set-ServiceStart 'DoSvc' 2 1
    Set-ServiceStart 'InstallService' 3
    Set-ServiceStart 'UsoSvc' 2 1
    Set-ServiceStart 'WaaSMedicSvc' 3
    Set-ServiceStart 'CryptSvc' 2

    Stop-UpdateServices
    $suffix = Get-Date -Format 'yyyyMMdd-HHmmss'
    Move-UpdateCache (Join-Path $env:windir 'SoftwareDistribution') $suffix
    Move-UpdateCache (Join-Path $env:windir 'System32\catroot2') $suffix
    Start-UpdateServices

    Write-State 'DISM_ONLINE' 'RestoreHealth with Microsoft repair content enabled'
    $dism = Start-Process -FilePath "$env:windir\System32\dism.exe" -ArgumentList @(
        '/Online', '/Cleanup-Image', '/RestoreHealth', '/English'
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

    Write-State 'DONE' 'Windows Update unblocked; DISM, SFC, and CheckHealth completed'
} catch {
    Write-State 'FAILED' $_.Exception.Message
}
