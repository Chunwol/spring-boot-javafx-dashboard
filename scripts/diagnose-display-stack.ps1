$ErrorActionPreference = 'SilentlyContinue'

function OneLine([object]$Value, [int]$Limit = 180) {
    $text = ([string]$Value -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()
    if ($text.Length -le $Limit) { return $text }
    return $text.Substring(0, $Limit)
}

Write-Output ('TIME=' + (Get-Date).ToString('o'))
Write-Output ('USER=' + (Get-CimInstance Win32_ComputerSystem).UserName)
Write-Output ('SESSION=' + (OneLine ((& quser.exe 2>&1) -join ' | ') 400))

$processes = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in @('explorer.exe', 'dwm.exe', 'winlogon.exe', 'userinit.exe', 'LogonUI.exe')
} | Sort-Object SessionId, Name
foreach ($process in $processes) {
    Write-Output ('PROC={0}|pid={1}|session={2}|path={3}' -f $process.Name,
        $process.ProcessId, $process.SessionId, (OneLine $process.ExecutablePath 100))
}

$signedDrivers = @(Get-CimInstance Win32_PnPSignedDriver | Where-Object {
    $_.DeviceClass -eq 'DISPLAY'
})
$displayDevices = @(Get-PnpDevice -Class Display)
foreach ($device in $displayDevices) {
    $problem = (Get-PnpDeviceProperty -InstanceId $device.InstanceId `
        -KeyName 'DEVPKEY_Device_ProblemCode').Data
    $driver = $signedDrivers | Where-Object { $_.DeviceID -eq $device.InstanceId } |
        Select-Object -First 1
    Write-Output ('GPU={0}|status={1}|problem={2}|instance={3}' -f
        (OneLine $device.FriendlyName 80), $device.Status, $problem,
        (OneLine $device.InstanceId 180))
    Write-Output ('DRIVER=inf={0}|version={1}|date={2}|provider={3}|signed={4}' -f
        $driver.InfName, $driver.DriverVersion,
        $(if ($driver.DriverDate) { $driver.DriverDate.ToString('s') } else { '' }),
        (OneLine $driver.DriverProviderName 80), $driver.IsSigned)
}

$graphicsKey = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
Write-Output ('TDR=Level:{0}|Delay:{1}|DdiDelay:{2}|HwSchMode:{3}' -f
    $graphicsKey.TdrLevel, $graphicsKey.TdrDelay, $graphicsKey.TdrDdiDelay,
    $graphicsKey.HwSchMode)

foreach ($name in @('nvlddmkm', 'igfx', 'BasicDisplay')) {
    $service = Get-CimInstance Win32_SystemDriver -Filter "Name='$name'"
    if ($service) {
        Write-Output ('SERVICE={0}|state={1}|start={2}|path={3}' -f $service.Name,
            $service.State, $service.StartMode, (OneLine $service.PathName 150))
    }
}

$recentDrivers = Get-CimInstance Win32_PnPSignedDriver | Where-Object {
    $_.DeviceClass -eq 'DISPLAY'
} | Sort-Object DriverDate -Descending
foreach ($driver in $recentDrivers) {
    Write-Output ('DISPLAY_DRIVER={0}|inf={1}|version={2}|date={3}' -f
        (OneLine $driver.DeviceName 80), $driver.InfName, $driver.DriverVersion,
        $(if ($driver.DriverDate) { $driver.DriverDate.ToString('s') } else { '' }))
}

$updates = Get-HotFix | Sort-Object InstalledOn -Descending | Select-Object -First 5
foreach ($update in $updates) {
    Write-Output ('HOTFIX={0}|installed={1}' -f $update.HotFixID,
        $(if ($update.InstalledOn) { $update.InstalledOn.ToString('yyyy-MM-dd') } else { '' }))
}

$events = Get-WinEvent -FilterHashtable @{
    LogName = @('System', 'Application')
    StartTime = (Get-Date).AddHours(-3)
    Level = @(1, 2, 3)
} -MaxEvents 150 | Where-Object {
    $_.ProviderName -match 'Display|Dwm|Desktop Window Manager|Kernel-PnP|nvlddmkm|igfx|Application Error'
} | Select-Object -First 5
foreach ($event in $events) {
    Write-Output ('EVENT={0}|{1}|{2}|{3}' -f $event.TimeCreated.ToString('s'),
        $event.ProviderName, $event.Id, (OneLine $event.Message 100))
}
