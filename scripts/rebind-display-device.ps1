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

$pattern = if ($Adapter -eq 'Intel') { 'Intel(R) UHD Graphics 630' } else { 'NVIDIA GeForce GTX 1070' }
$target = Get-PnpDevice -PresentOnly -Class Display | Where-Object {
    $_.FriendlyName -eq $pattern
} | Select-Object -First 1
if (-not $target) { throw "$Adapter display device was not found." }

$instanceId = $target.InstanceId
$driverInf = (Get-PnpDeviceProperty -InstanceId $instanceId `
    -KeyName 'DEVPKEY_Device_DriverInfPath').Data
Write-Output ('REBIND before name={0} status={1} problem={2} inf={3}' -f
    $target.FriendlyName, $target.Status, (ProblemCode $instanceId), $driverInf)

$removeOutput = & pnputil.exe /remove-device $instanceId 2>&1
$removeExit = $LASTEXITCODE
Write-Output ('REMOVE exit={0} result={1}' -f $removeExit,
    (([string]($removeOutput -join ' ') -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()))
if ($removeExit -ne 0) { exit $removeExit }

$scanOutput = & pnputil.exe /scan-devices 2>&1
$scanExit = $LASTEXITCODE
Write-Output ('SCAN exit={0} result={1}' -f $scanExit,
    (([string]($scanOutput -join ' ') -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()))
if ($scanExit -ne 0) { exit $scanExit }

Start-Sleep -Seconds 35
$after = Get-PnpDevice -PresentOnly -Class Display | Where-Object {
    $_.FriendlyName -eq $pattern
} | Select-Object -First 1
if (-not $after) {
    Write-Output 'REBIND after device=missing'
    exit 3
}

$afterInf = (Get-PnpDeviceProperty -InstanceId $after.InstanceId `
    -KeyName 'DEVPKEY_Device_DriverInfPath').Data
$afterProblem = ProblemCode $after.InstanceId
Write-Output ('REBIND after name={0} status={1} problem={2} inf={3}' -f
    $after.FriendlyName, $after.Status, $afterProblem, $afterInf)
if ($after.Status -ne 'OK' -or $afterProblem -ne 0) { exit 2 }

