$ErrorActionPreference = 'Continue'

$root = 'C:/Program Files/SCMATE_RX2Client'
$paths = @(
    'restore/frcmd.exe',
    'ShieldSetup/setup.exe',
    'ShieldSetup/x64.exe',
    'ShieldSetup/setup.ini',
    'Shield/Shield.exe',
    'Shield/ShdCmd.exe'
)

$items = foreach ($relative in $paths) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $item = Get-Item -LiteralPath $path
        [ordered]@{
            path = $relative
            exists = $true
            size = $item.Length
            version = $item.VersionInfo.ProductVersion
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } else {
        [ordered]@{
            path = $relative
            exists = $false
        }
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    items = @($items)
} | ConvertTo-Json -Depth 4 -Compress
