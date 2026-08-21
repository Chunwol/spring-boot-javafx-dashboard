$ErrorActionPreference = 'Stop'

$files = @(
    'C:/Program Files/SCMATE_RX2Client/Shield/ShdCmd.exe',
    'C:/Program Files/SCMATE_RX2Client/Shield/ShdApi.dll'
)
$result = foreach ($path in $files) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $bytes = [IO.File]::ReadAllBytes($path)
    $all = @()
    foreach ($text in @([Text.Encoding]::ASCII.GetString($bytes), [Text.Encoding]::Unicode.GetString($bytes))) {
        $all += @([regex]::Matches($text, '[\x20-\x7e]{4,}') | ForEach-Object Value)
    }
    $hints = @($all |
        Where-Object {
            $_ -match 'Shd[A-Za-z]+|snapshot|baseline|restore|recover|protect|commit|sync|command|usage|option|silent|reboot' -and
            $_ -notmatch 'Synchronizer|OperatorOverload|SaveToStream|SaveToFile|CommitFlags' -and
            $_.Length -le 180
        } |
        Select-Object -Unique -First 120)
    [ordered]@{ file = Split-Path $path -Leaf; hints = $hints }
}

[ordered]@{ computerName = $env:COMPUTERNAME; files = @($result) } |
    ConvertTo-Json -Depth 5 -Compress
