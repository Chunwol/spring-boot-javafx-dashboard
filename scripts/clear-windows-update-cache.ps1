[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$cacheRoot = Join-Path $env:SystemRoot 'SoftwareDistribution'
$removedFiles = 0
$skippedFiles = 0
$removedDirectories = 0
$skippedDirectories = 0

foreach ($serviceName in @('UsoSvc', 'wuauserv', 'BITS', 'DoSvc')) {
    & "$env:SystemRoot\System32\sc.exe" stop $serviceName 2>$null | Out-Null
}

foreach ($processName in @('MoUsoCoreWorker', 'TiWorker', 'MusNotification', 'MusNotificationUx', 'usoclient')) {
    Get-Process -Name $processName -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Seconds 2

if (-not (Test-Path -LiteralPath $cacheRoot)) {
    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
}

foreach ($file in @(Get-ChildItem -LiteralPath $cacheRoot -Force -Recurse -File -ErrorAction SilentlyContinue)) {
    try {
        Remove-Item -LiteralPath $file.FullName -Force -ErrorAction Stop
        $removedFiles++
    } catch {
        $skippedFiles++
    }
}

foreach ($directory in @(Get-ChildItem -LiteralPath $cacheRoot -Force -Recurse -Directory -ErrorAction SilentlyContinue |
    Sort-Object { $_.FullName.Length } -Descending)) {
    try {
        Remove-Item -LiteralPath $directory.FullName -Force -ErrorAction Stop
        $removedDirectories++
    } catch {
        $skippedDirectories++
    }
}

$remainingFiles = @(Get-ChildItem -LiteralPath $cacheRoot -Force -Recurse -File -ErrorAction SilentlyContinue)
$remainingBytes = [long](($remainingFiles | Measure-Object -Property Length -Sum).Sum)

Write-Output ('RESULT removedFiles={0} skippedLockedFiles={1} removedDirs={2} skippedDirs={3} remainingFiles={4} remainingBytes={5}' -f
    $removedFiles,
    $skippedFiles,
    $removedDirectories,
    $skippedDirectories,
    $remainingFiles.Count,
    $remainingBytes)

exit 0
