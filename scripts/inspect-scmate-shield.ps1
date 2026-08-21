$ErrorActionPreference = 'Continue'

function Get-BinaryHints {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        $texts = @(
            [Text.Encoding]::ASCII.GetString($bytes),
            [Text.Encoding]::Unicode.GetString($bytes)
        )
        @($texts | ForEach-Object {
            [regex]::Matches($_, '[\x20-\x7e]{6,}') | ForEach-Object Value
        } |
            Where-Object {
                $_ -match 'snapshot|baseline|restore|recover|protect|operator|console|command.line|usage|silent|commit|sync' -and
                $_ -notmatch 'Synchronizer|OperatorOverload|SaveToStream|SaveToFile|CommitFlags' -and
                $_.Length -le 140
            } |
            Select-Object -Unique -First 18)
    } catch { @() }
}

$root = 'C:/Program Files/SCMATE_RX2Client/Shield'
$files = @(Get-ChildItem -LiteralPath $root -File -Force -ErrorAction SilentlyContinue |
    Select-Object Name, Length, @{Name = 'Version'; Expression = { $_.VersionInfo.ProductVersion } })
$docs = @(Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction SilentlyContinue |
    Where-Object Extension -In '.txt', '.chm', '.pdf', '.htm', '.html' |
    Select-Object -First 40 Name, DirectoryName, Length)

$configHints = @()
Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction SilentlyContinue |
    Where-Object Extension -In '.ini', '.cfg', '.conf', '.xml' |
    Select-Object -First 30 |
    ForEach-Object {
        $file = $_
        Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue |
            Where-Object {
                $_ -match 'snapshot|baseline|restore|recover|protect|boot|mode|sync|commit' -and
                $_ -notmatch 'pass|password|pwd|secret|token|credential|license|serial|user[0-9]|hash|checksum'
            } |
            Select-Object -First 10 |
            ForEach-Object { $configHints += "{0}:{1}" -f $file.Name, $_.Trim() }
    }

$executables = @('Shield.exe', 'ShdSync.exe', 'ShdTray.exe', 'shdserv.exe')
$binaryHints = foreach ($name in $executables) {
    $path = Join-Path $root $name
    $hints = @(Get-BinaryHints -Path $path)
    if ($hints.Count) { [ordered]@{ file = $name; hints = $hints } }
}

$registryNames = @()
foreach ($path in @('HKLM:/SOFTWARE/WAREMATE', 'HKLM:/SOFTWARE/WOW6432Node/WAREMATE', 'HKLM:/SOFTWARE/SCMATE', 'HKLM:/SOFTWARE/WOW6432Node/SCMATE')) {
    if (Test-Path $path) {
        $registryNames += [ordered]@{
            path = $path
            valueNames = @((Get-ItemProperty $path).PSObject.Properties.Name |
                Where-Object { $_ -notmatch '^PS|pass|pwd|secret|token|license|serial|user|hash' })
        }
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    files = $files
    docs = $docs
    configHints = @($configHints | Select-Object -Unique -First 40)
    binaryHints = @($binaryHints)
    registryNames = @($registryNames)
} | ConvertTo-Json -Depth 6 -Compress
