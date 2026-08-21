$ErrorActionPreference = 'Continue'

$paths = @(
    'C:/Program Files/SCMATE_RX2Server/SCMateServer.exe',
    'C:/Program Files/SCMATE_RX2Server/FirebirdServer.exe'
)

function Get-Strings {
    param([string] $Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    $ascii = [Text.Encoding]::ASCII.GetString($bytes)
    $unicode = [Text.Encoding]::Unicode.GetString($bytes)
    @(
        [regex]::Matches($ascii, '[\x20-\x7e]{5,}') | ForEach-Object Value
        [regex]::Matches($unicode, '[\x20-\x7e]{5,}') | ForEach-Object Value
    ) | Select-Object -Unique
}

$results = foreach ($path in $paths) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $strings = @(Get-Strings -Path $path)
    $safeHints = @($strings |
        Where-Object {
            $_ -match 'SCMate\.Fdb|Firebird|localhost|127\.0\.0\.1|3050|SYSDBA|DatabaseName|User_Name|Protocol' -and
            $_ -notmatch '(?i)pass|password|pwd|secret|token|credential|license|serial'
        } |
        Where-Object Length -le 180 |
        Select-Object -First 50)

    [ordered]@{
        file = Split-Path $path -Leaf
        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        containsSysdba = @($strings | Where-Object { $_ -match '(?i)SYSDBA' }).Count -gt 0
        containsDefaultPasswordMarker = @($strings | Where-Object { $_ -match '(?i)masterkey' }).Count -gt 0
        safeHints = $safeHints
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    results = @($results)
} | ConvertTo-Json -Depth 5 -Compress
