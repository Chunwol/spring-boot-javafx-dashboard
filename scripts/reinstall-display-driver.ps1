param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Intel', 'Nvidia')]
    [string]$Adapter
)

$ErrorActionPreference = 'Stop'

function ProblemCode([string]$InstanceId) {
    $property = Get-PnpDeviceProperty -InstanceId $InstanceId `
        -KeyName 'DEVPKEY_Device_ProblemCode' -ErrorAction SilentlyContinue
    if ($null -eq $property) { return $null }
    return $property.Data
}

function Run-Pnp([string[]]$Arguments, [string]$Label) {
    $output = & pnputil.exe @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = (([string]($output -join ' ') -replace '[\r\n]+', ' ' `
        -replace '\s{2,}', ' ').Trim())
    if ($text.Length -gt 700) { $text = $text.Substring(0, 700) }
    Write-Output ('{0} exit={1} result={2}' -f $Label, $exitCode, $text)
    if ($exitCode -ne 0) { throw "$Label failed with exit code $exitCode." }
}

$friendlyName = if ($Adapter -eq 'Intel') {
    'Intel(R) UHD Graphics 630'
} else {
    'NVIDIA GeForce GTX 1070'
}
$device = Get-PnpDevice -PresentOnly -Class Display | Where-Object {
    $_.FriendlyName -eq $friendlyName
} | Select-Object -First 1
if (-not $device) { throw "$friendlyName was not found." }

$inf = [string](Get-PnpDeviceProperty -InstanceId $device.InstanceId `
    -KeyName 'DEVPKEY_Device_DriverInfPath').Data
if ($inf -notmatch '^oem\d+\.inf$') { throw "Unexpected display INF: $inf" }

$backupRoot = Join-Path $env:ProgramData 'PCAgent\display-driver-backup'
$backupDir = Join-Path $backupRoot $Adapter.ToLowerInvariant()
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
Write-Output ('REINSTALL before name={0} status={1} problem={2} inf={3} backup={4}' -f
    $friendlyName, $device.Status, (ProblemCode $device.InstanceId), $inf, $backupDir)

Run-Pnp @('/export-driver', $inf, $backupDir) 'EXPORT'
$exportedInfs = @(Get-ChildItem -LiteralPath $backupDir -Filter *.inf -Recurse -File)
if ($exportedInfs.Count -eq 0) { throw 'No INF was exported.' }
Write-Output ('EXPORT verifiedInfCount=' + $exportedInfs.Count)

Run-Pnp @('/delete-driver', $inf, '/uninstall', '/force') 'DELETE'
Run-Pnp @('/scan-devices') 'SCAN_AFTER_DELETE'
Run-Pnp @('/add-driver', (Join-Path $backupDir '*.inf'), '/subdirs', '/install') 'INSTALL'
Run-Pnp @('/scan-devices') 'SCAN_AFTER_INSTALL'

Start-Sleep -Seconds 45
$after = Get-PnpDevice -PresentOnly -Class Display | Where-Object {
    $_.FriendlyName -eq $friendlyName
} | Select-Object -First 1
if (-not $after) {
    Write-Output 'REINSTALL after device=missing'
    exit 3
}
$afterInf = [string](Get-PnpDeviceProperty -InstanceId $after.InstanceId `
    -KeyName 'DEVPKEY_Device_DriverInfPath').Data
$afterProblem = ProblemCode $after.InstanceId
Write-Output ('REINSTALL after name={0} status={1} problem={2} inf={3}' -f
    $friendlyName, $after.Status, $afterProblem, $afterInf)
if ($after.Status -ne 'OK' -or $afterProblem -ne 0) { exit 2 }

