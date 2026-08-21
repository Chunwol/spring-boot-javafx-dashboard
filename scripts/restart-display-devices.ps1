$ErrorActionPreference = 'Stop'

function ProblemCode([string]$InstanceId) {
    $property = Get-PnpDeviceProperty -InstanceId $InstanceId `
        -KeyName 'DEVPKEY_Device_ProblemCode' -ErrorAction SilentlyContinue
    if ($null -eq $property) { return $null }
    return $property.Data
}

$targets = @(Get-PnpDevice -PresentOnly -Class Display -ErrorAction Stop |
    Where-Object { $_.Status -ne 'OK' -or (ProblemCode $_.InstanceId) -ne 0 } |
    Sort-Object FriendlyName)

Write-Output ('DISPLAY_RESTART targets=' + $targets.Count)
foreach ($target in $targets) {
    $before = ProblemCode $target.InstanceId
    Write-Output ('BEFORE name={0} status={1} problem={2}' -f
        $target.FriendlyName, $target.Status, $before)
    $output = & pnputil.exe /restart-device $target.InstanceId 2>&1
    $exitCode = $LASTEXITCODE
    Write-Output ('RESTART name={0} exit={1} result={2}' -f
        $target.FriendlyName, $exitCode,
        (([string]($output -join ' ') -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()))
}

& pnputil.exe /scan-devices | Out-Null
Start-Sleep -Seconds 15

$afterDevices = @(Get-PnpDevice -PresentOnly -Class Display -ErrorAction Stop |
    Sort-Object FriendlyName)
$remaining = 0
foreach ($device in $afterDevices) {
    $problem = ProblemCode $device.InstanceId
    if ($device.Status -ne 'OK' -or $problem -ne 0) { $remaining++ }
    Write-Output ('AFTER name={0} status={1} problem={2}' -f
        $device.FriendlyName, $device.Status, $problem)
}

$dwm = @(Get-Process dwm -ErrorAction SilentlyContinue)
Write-Output ('DWM count={0} ids={1}' -f $dwm.Count, (($dwm.Id -join ',')))
Write-Output ('DISPLAY_RESTART remainingErrors=' + $remaining)
if ($remaining -gt 0) { exit 2 }

