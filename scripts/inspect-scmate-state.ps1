$ErrorActionPreference = 'Continue'

$registryPath = 'HKLM:/SOFTWARE/WOW6432Node/SCMATE'
$safeRegistryNames = @(
    'SCMateClientVersion',
    'SCMateServerHOST',
    'SCMateServerPORT',
    'SCMateRestore',
    'SCMateAutoReset',
    'RestoreSec',
    'RestoreDefrag',
    'WindowUpdateYN',
    'SCMateFilePort',
    'LocalAuthYN',
    'ClientLoginYN',
    'TrayIconBlock',
    'MainProgramName'
)
$safeRegistry = [ordered]@{}
if (Test-Path $registryPath) {
    $values = Get-ItemProperty $registryPath
    foreach ($name in $safeRegistryNames) {
        if ($null -ne $values.$name) { $safeRegistry[$name] = $values.$name }
    }
}

$shieldRoot = 'C:/Program Files/SCMATE_RX2Client/Shield'
$files = @(Get-ChildItem -LiteralPath $shieldRoot -File -Force -ErrorAction SilentlyContinue |
    Select-Object Name, Length, @{Name = 'Version'; Expression = { $_.VersionInfo.ProductVersion } })

$service = Get-CimInstance Win32_Service -Filter "Name='ShdServ'" -ErrorAction SilentlyContinue
$drivers = @(Get-CimInstance Win32_SystemDriver -ErrorAction SilentlyContinue |
    Where-Object Name -In 'Shdbus', 'Shield', 'Shieldm' |
    Select-Object Name, State, StartMode)

[ordered]@{
    computerName = $env:COMPUTERNAME
    registry = $safeRegistry
    shieldFiles = $files
    service = if ($service) { [ordered]@{ state = $service.State; startMode = $service.StartMode; path = $service.PathName } } else { $null }
    drivers = $drivers
} | ConvertTo-Json -Depth 5 -Compress
