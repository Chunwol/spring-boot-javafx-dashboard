$ErrorActionPreference = 'SilentlyContinue'

function Value([string]$InstanceId, [string]$Key) {
    $property = Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName $Key
    if ($null -eq $property) { return '' }
    return $property.Data
}

function Compact([object]$InputValue, [int]$Limit = 160) {
    $text = ([string]$InputValue -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()
    if ($text.Length -le $Limit) { return $text }
    return $text.Substring(0, $Limit)
}

$os = Get-CimInstance Win32_OperatingSystem
$safeBoot = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\SafeBoot\Option'
$bcd = (& bcdedit.exe /enum '{current}' 2>&1) -join ' '
Write-Output ('BOOT={0}|state={1}|safeOption={2}|bcdSafe={3}' -f
    $os.LastBootUpTime.ToString('s'), (Get-CimInstance Win32_ComputerSystem).BootupState,
    $safeBoot.OptionValue, [bool]($bcd -match 'safeboot'))

$devices = @(Get-PnpDevice -PresentOnly -Class Display | Sort-Object FriendlyName)
foreach ($device in $devices) {
    $problem = Value $device.InstanceId 'DEVPKEY_Device_ProblemCode'
    $problemStatus = Value $device.InstanceId 'DEVPKEY_Device_ProblemStatus'
    if ($problemStatus -is [int] -or $problemStatus -is [long]) {
        $problemStatus = '0x{0:X8}' -f ([uint32]$problemStatus)
    }
    Write-Output ('DEVICE={0}|status={1}|problem={2}|ntstatus={3}|service={4}|inf={5}|version={6}' -f
        $device.FriendlyName, $device.Status, $problem, $problemStatus,
        (Value $device.InstanceId 'DEVPKEY_Device_Service'),
        (Value $device.InstanceId 'DEVPKEY_Device_DriverInfPath'),
        (Value $device.InstanceId 'DEVPKEY_Device_DriverVersion'))
    Write-Output ('INSTANCE=' + $device.InstanceId)
}

foreach ($file in @(
    "$env:windir\System32\dwm.exe",
    "$env:windir\System32\DispBroker.dll"
)) {
    $signature = Get-AuthenticodeSignature -FilePath $file
    $version = (Get-Item $file).VersionInfo.FileVersion
    Write-Output ('FILE={0}|version={1}|signature={2}' -f
        (Split-Path $file -Leaf), $version, $signature.Status)
}

$boot = $os.LastBootUpTime
$events = Get-WinEvent -FilterHashtable @{ LogName = 'System'; StartTime = $boot } -MaxEvents 600 |
    Where-Object { $_.ProviderName -match 'Kernel-PnP|Display|nvlddmkm|igfx' } |
    Select-Object -First 12
foreach ($event in $events) {
    [xml]$xml = $event.ToXml()
    $data = @($xml.Event.EventData.Data | ForEach-Object { [string]$_.'#text' })
    Write-Output ('PNPEVENT={0}|{1}|{2}|{3}' -f $event.TimeCreated.ToString('s'),
        $event.ProviderName, $event.Id, (Compact ($data -join ';') 180))
}
