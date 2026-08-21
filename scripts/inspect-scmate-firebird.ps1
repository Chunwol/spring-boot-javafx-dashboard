$ErrorActionPreference = 'Continue'

$serverRoot = 'C:/Program Files/SCMATE_RX2Server'
$dataRoot = Join-Path $serverRoot 'Data'

function Get-SafeConfigHints {
    param([string] $Root)

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return @()
    }

    $sensitive = 'pass|password|pwd|secret|token|credential|license|serial|key'
    @(Get-ChildItem -LiteralPath $Root -File -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object Extension -In '.ini', '.cfg', '.conf', '.xml', '.json' |
        Select-Object -First 80 |
        ForEach-Object {
            $file = $_
            Get-Content -LiteralPath $file.FullName -ErrorAction SilentlyContinue |
                Where-Object {
                    $_ -match 'database|alias|host|port|server|path|firebird|scmate' -and
                    $_ -notmatch $sensitive
                } |
                Select-Object -First 8 |
                ForEach-Object {
                    [ordered]@{
                        file = $file.FullName.Substring($serverRoot.Length).TrimStart('\', '/')
                        value = $_.Trim()
                    }
                }
        } | Select-Object -First 60)
}

$processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'firebird|fbserver|fbguard|scmate' -or
        $_.ExecutablePath -match 'Firebird|SCMATE_RX2Server'
    } |
    ForEach-Object {
        $commandLine = [string]$_.CommandLine
        if ($commandLine -match '(?i)pass|password|pwd|secret|token|credential') {
            $commandLine = '[redacted]'
        }
        [ordered]@{
            name = $_.Name
            pid = $_.ProcessId
            path = $_.ExecutablePath
            commandLine = $commandLine
        }
    })

$services = @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match 'firebird|scmate' -or
        $_.DisplayName -match 'firebird|scmate' -or
        $_.PathName -match 'Firebird|SCMATE_RX2Server'
    } |
    Select-Object Name, DisplayName, State, StartMode, PathName)

$tools = @()
if (Test-Path -LiteralPath $serverRoot -PathType Container) {
    $tools = @(Get-ChildItem -LiteralPath $serverRoot -File -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object Name -Match '^(isql|gbak|gstat|fbserver|fbguard|firebird.*)\.exe$|firebird\.conf$|databases\.conf$|aliases\.conf$|security.*\.fdb$' |
        Select-Object FullName, Length, LastWriteTime)
}

$dataFiles = @()
if (Test-Path -LiteralPath $dataRoot -PathType Container) {
    $dataFiles = @(Get-ChildItem -LiteralPath $dataRoot -Force -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 300 |
        ForEach-Object {
            [ordered]@{
                relativePath = $_.FullName.Substring($dataRoot.Length).TrimStart('\', '/')
                kind = if ($_.PSIsContainer) { 'directory' } else { 'file' }
                size = if ($_.PSIsContainer) { $null } else { $_.Length }
                modified = $_.LastWriteTime.ToString('s')
            }
        })
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    serverRootExists = Test-Path -LiteralPath $serverRoot -PathType Container
    processes = $processes
    services = $services
    tools = $tools
    dataFiles = $dataFiles
    safeConfigHints = @(Get-SafeConfigHints -Root $serverRoot)
} | ConvertTo-Json -Depth 6 -Compress
