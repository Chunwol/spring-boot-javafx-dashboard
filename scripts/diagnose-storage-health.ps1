$ErrorActionPreference = 'SilentlyContinue'

function Compact([object]$InputValue, [int]$Limit = 180) {
    $text = ([string]$InputValue -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()
    if ($text.Length -le $Limit) { return $text }
    return $text.Substring(0, $Limit)
}

Write-Output ('TIME=' + (Get-Date).ToString('o'))
$volume = Get-Volume -DriveLetter C
Write-Output ('VOLUME=health:{0}|status:{1}|size:{2}|free:{3}|fs:{4}' -f
    $volume.HealthStatus, ($volume.OperationalStatus -join ','), $volume.Size,
    $volume.SizeRemaining, $volume.FileSystem)

$dirty = & fsutil.exe dirty query C: 2>&1
Write-Output ('DIRTY exit={0}|result={1}' -f $LASTEXITCODE, (Compact ($dirty -join ' ') 300))

foreach ($disk in Get-CimInstance Win32_DiskDrive) {
    Write-Output ('DISK=model:{0}|status:{1}|size:{2}|serial:{3}|interface:{4}' -f
        (Compact $disk.Model 100), $disk.Status, $disk.Size,
        (Compact $disk.SerialNumber 80), $disk.InterfaceType)
}

foreach ($disk in Get-PhysicalDisk) {
    $reliability = Get-StorageReliabilityCounter -PhysicalDisk $disk
    Write-Output ('PHYSICAL=name:{0}|health:{1}|status:{2}|media:{3}|wear:{4}|readErrors:{5}|writeErrors:{6}|temp:{7}' -f
        (Compact $disk.FriendlyName 100), $disk.HealthStatus,
        ($disk.OperationalStatus -join ','), $reliability.MediaErrorsTotal,
        $reliability.Wear, $reliability.ReadErrorsTotal,
        $reliability.WriteErrorsTotal, $reliability.Temperature)
}

$events = Get-WinEvent -FilterHashtable @{
    LogName = 'System'
    StartTime = (Get-Date).AddDays(-14)
    Level = @(1, 2, 3)
} -MaxEvents 1000 | Where-Object {
    $_.ProviderName -match 'Disk|Ntfs|storahci|stornvme|volmgr|WHEA'
} | Select-Object -First 15
foreach ($event in $events) {
    [xml]$xml = $event.ToXml()
    $data = @($xml.Event.EventData.Data | ForEach-Object { [string]$_.'#text' })
    Write-Output ('EVENT={0}|{1}|{2}|{3}' -f $event.TimeCreated.ToString('s'),
        $event.ProviderName, $event.Id, (Compact ($data -join ';') 180))
}

