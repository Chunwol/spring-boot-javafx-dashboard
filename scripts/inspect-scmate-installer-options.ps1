$ErrorActionPreference = 'Stop'

$root = 'C:/Program Files/SCMATE_RX2Client/ShieldSetup'
$executables = foreach ($name in 'setup.exe', 'x64.exe') {
    $path = Join-Path $root $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $bytes = [IO.File]::ReadAllBytes($path)
    $ascii = [Text.Encoding]::ASCII.GetString($bytes)
    $unicode = [Text.Encoding]::Unicode.GetString($bytes)
    $strings = @(
        [regex]::Matches($ascii, '[\x20-\x7e]{4,}') | ForEach-Object Value
        [regex]::Matches($unicode, '[\x20-\x7e]{4,}') | ForEach-Object Value
    ) | Where-Object {
        $_ -match '(?i)(^|[ /-])(install|uninstall|silent|quiet|reboot|noreboot|repair|setup|driver)([ /-]|$)'
    } | Where-Object {
        $_ -notmatch '(?i)(password|serial|license|credential|token)'
    } | Select-Object -Unique -First 80

    [ordered]@{ file = $name; strings = @($strings) }
}

$iniPath = Join-Path $root 'setup.ini'
$safeIniNames = @('autorun', 'reboot', 'BootDiskNumber', 'InstallPath', 'ProtectedDrives', 'LowerFilter')
$safeIniValues = [ordered]@{}
$iniKeys = if (Test-Path -LiteralPath $iniPath -PathType Leaf) {
    @(Get-Content -LiteralPath $iniPath | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\[.+\]$') { return $line }
        if ($line -match '^([^;#][^=]+)=(.*)$') {
            $name = $Matches[1].Trim()
            if ($safeIniNames -contains $name) {
                $safeIniValues[$name] = $Matches[2].Trim()
            }
            return $name
        }
    } | Where-Object { $_ } | Select-Object -Unique)
} else { @() }

[ordered]@{
    computerName = $env:COMPUTERNAME
    executables = @($executables)
    iniKeys = @($iniKeys)
    safeIniValues = $safeIniValues
} | ConvertTo-Json -Depth 5 -Compress
