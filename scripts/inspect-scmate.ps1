$ErrorActionPreference = 'Continue'

function Select-ScmateItems {
    param($Items)
    @($Items | Where-Object {
        ([string]$_.Name) -match 'SCMATE|SCMate|^Shd|Shield' -or
        ([string]$_.DisplayName) -match 'SCMATE|SCMate|^Shd|Shield' -or
        ([string]$_.PathName) -match 'SCMATE|SCMate|\\Shd|Shield' -or
        ([string]$_.ExecutablePath) -match 'SCMATE|SCMate|\\Shd|Shield'
    })
}

$uninstallRoots = @(
    'HKLM:/SOFTWARE/Microsoft/Windows/CurrentVersion/Uninstall/*',
    'HKLM:/SOFTWARE/WOW6432Node/Microsoft/Windows/CurrentVersion/Uninstall/*'
)
$installed = foreach ($root in $uninstallRoots) {
    Get-ItemProperty $root -ErrorAction SilentlyContinue |
        Where-Object DisplayName -Match 'SCMATE|SCMate' |
        Select-Object DisplayName, DisplayVersion, Publisher, InstallLocation, UninstallString
}

$processes = Select-ScmateItems (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
$services = Select-ScmateItems (Get-CimInstance Win32_Service -ErrorAction SilentlyContinue)
$drivers = Select-ScmateItems (Get-CimInstance Win32_SystemDriver -ErrorAction SilentlyContinue)

$rootDirectories = @('C:/', 'C:/Program Files', 'C:/Program Files (x86)', 'C:/ProgramData')
$matchedDirectories = foreach ($root in $rootDirectories) {
    Get-ChildItem -LiteralPath $root -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object Name -Match 'SCMATE|SCMate' |
        Select-Object -ExpandProperty FullName
}
$matchedDirectories = @($matchedDirectories | Select-Object -Unique)

$files = foreach ($directory in $matchedDirectories) {
    Get-ChildItem -LiteralPath $directory -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.exe', '.ini', '.cfg', '.conf', '.xml', '.json', '.db', '.dat', '.bat', '.cmd' } |
        Select-Object -First 12 Name, @{Name = 'ProductVersion'; Expression = { $_.VersionInfo.ProductVersion } }
}

$keyFiles = foreach ($directory in $matchedDirectories) {
    Get-ChildItem -LiteralPath $directory -File -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^SCMate(Client|Server|Midware)?\.exe$|^Shd(Tray|Sync)?\.exe$|^Shield\.exe$|operator|admin|console' -and
            $_.Extension -in '.exe', '.ini', '.cfg', '.conf', '.xml', '.json'
        } |
        Select-Object -First 12 Name, @{Name = 'Parent'; Expression = { Split-Path $_.DirectoryName -Leaf } }, @{Name = 'ProductVersion'; Expression = { $_.VersionInfo.ProductVersion } }
}

$tasks = Get-ScheduledTask -ErrorAction SilentlyContinue | ForEach-Object {
    $actionText = @($_.Actions | ForEach-Object { "$($_.Execute) $($_.Arguments)" }) -join ' | '
    if ($_.TaskName -match 'SCMATE|SCMate' -or $actionText -match 'SCMATE|SCMate') {
        [ordered]@{
            taskPath = $_.TaskPath
            taskName = $_.TaskName
            state = [string]$_.State
            actions = $actionText
        }
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    installed = @($installed)
    processes = @($processes | Select-Object Name, ProcessId, ExecutablePath)
    services = @($services | Select-Object Name, DisplayName, State, StartMode, PathName)
    drivers = @($drivers | Select-Object Name, State, StartMode, PathName)
    directories = $matchedDirectories
    files = @($files)
    keyFiles = @($keyFiles)
    scheduledTasks = @($tasks)
} | ConvertTo-Json -Depth 6 -Compress
