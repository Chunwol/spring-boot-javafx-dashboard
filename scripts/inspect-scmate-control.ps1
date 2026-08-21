$ErrorActionPreference = 'Continue'

function Get-PrintableStrings {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        $ascii = [Text.Encoding]::ASCII.GetString($bytes)
        $unicode = [Text.Encoding]::Unicode.GetString($bytes)
        @(
            [regex]::Matches($ascii, '[\x20-\x7e]{5,}') | ForEach-Object Value
            [regex]::Matches($unicode, '[\x20-\x7e]{5,}') | ForEach-Object Value
        ) |
            Where-Object { $_ -match 'snapshot|restore|recover|backup|save|commit|protect|operator|admin|console|mode|command|shield|sync|boot' } |
            Where-Object { $_.Length -le 180 } |
            Select-Object -Unique -First 50
    } catch { @() }
}

$roots = @(
    'C:/Program Files/SCMATE_RX2Server',
    'C:/Program Files/SCMATE_RX2Client'
) | Where-Object { Test-Path -LiteralPath $_ -PathType Container }

$processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object ExecutablePath -Match 'SCMATE_RX2' |
    Select-Object Name, ProcessId, ExecutablePath)
$processIds = @($processes.ProcessId)
$listeners = if ($processIds.Count) {
    @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object OwningProcess -In $processIds |
        Select-Object LocalAddress, LocalPort, OwningProcess)
} else { @() }

$configHints = @(foreach ($root in $roots) {
    Get-ChildItem -LiteralPath $root -File -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object Extension -In '.ini', '.cfg', '.conf', '.json', '.xml' |
        Select-Object -First 40 |
        ForEach-Object {
            $file = $_
            Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue |
                Where-Object {
                    $_ -match 'port|listen|url|host|server|mode|restore|recover|backup|shield|snapshot|save|commit' -and
                    $_ -notmatch 'pass|password|pwd|secret|token|credential|license|serial'
                } |
                Select-Object -First 12 |
                ForEach-Object { "{0}:{1}" -f $file.Name, $_.Trim() }
        }
}) | Select-Object -Unique -First 60

$candidateExecutables = @(
    'C:/Program Files/SCMATE_RX2Client/Shield/Shield.exe',
    'C:/Program Files/SCMATE_RX2Client/Shield/ShdSync.exe',
    'C:/Program Files/SCMATE_RX2Client/Shield/ShdTray.exe',
    'C:/Program Files/SCMATE_RX2Client/SCMateClient.exe',
    'C:/Program Files/SCMATE_RX2Server/SCMateServer.exe',
    'C:/Program Files/SCMATE_RX2Server/SCMateMidware.exe'
)
$binaryHints = foreach ($path in $candidateExecutables) {
    $values = @(Get-PrintableStrings -Path $path)
    if ($values.Count) {
        [ordered]@{ file = Split-Path $path -Leaf; strings = @($values | Select-Object -First 25) }
    }
}

$shortcutRoots = @(
    'C:/Users/Public/Desktop',
    'C:/ProgramData/Microsoft/Windows/Start Menu/Programs'
)
$shortcuts = @()
$shell = New-Object -ComObject WScript.Shell
foreach ($root in $shortcutRoots) {
    Get-ChildItem -LiteralPath $root -Filter '*.lnk' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object Name -Match 'SCMATE|SCMate|Shield' |
        ForEach-Object {
            $link = $shell.CreateShortcut($_.FullName)
            $shortcuts += [ordered]@{ name = $_.Name; target = $link.TargetPath; arguments = $link.Arguments }
        }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    processes = $processes
    listeners = $listeners
    configHints = @($configHints)
    binaryHints = @($binaryHints)
    shortcuts = @($shortcuts)
} | ConvertTo-Json -Depth 6 -Compress
