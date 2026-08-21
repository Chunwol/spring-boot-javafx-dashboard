$ErrorActionPreference = 'Continue'

$root = 'C:/Program Files (x86)/Firebird/Firebird_2_5'
$serverDb = 'C:/Program Files/SCMATE_RX2Server/Data/SCMate.Fdb'

$files = @()
if (Test-Path -LiteralPath $root -PathType Container) {
    $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^(isql|gbak|gstat|fbserver|fbguard|nbackup)\.exe$' -or
            $_.Name -in @('firebird.conf', 'aliases.conf', 'databases.conf', 'security2.fdb')
        } |
        ForEach-Object {
            [ordered]@{
                path = $_.FullName
                size = $_.Length
                version = if ($_.Extension -eq '.exe') { $_.VersionInfo.FileVersion } else { $null }
            }
        })
}

$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object LocalPort -In 3050, 3051 |
    Select-Object LocalAddress, LocalPort, OwningProcess)

$database = if (Test-Path -LiteralPath $serverDb -PathType Leaf) {
    $item = Get-Item -LiteralPath $serverDb
    [ordered]@{
        path = $item.FullName
        size = $item.Length
        modified = $item.LastWriteTime.ToString('s')
    }
} else { $null }

$aliasLines = @()
foreach ($name in @('aliases.conf', 'databases.conf')) {
    $path = Join-Path $root $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $aliasLines += @(Get-Content -LiteralPath $path -ErrorAction SilentlyContinue |
            Where-Object { $_ -match '=' -and $_ -notmatch '^\s*#' } |
            ForEach-Object { [ordered]@{ file = $name; value = $_.Trim() } })
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    rootExists = Test-Path -LiteralPath $root -PathType Container
    database = $database
    listeners = $listeners
    files = $files
    aliases = $aliasLines
} | ConvertTo-Json -Depth 5 -Compress
