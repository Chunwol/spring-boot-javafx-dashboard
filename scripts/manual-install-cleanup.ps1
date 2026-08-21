#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,
    [Parameter(Mandatory = $true)]
    [string]$InstallDir,
    [Parameter(Mandatory = $true)]
    [string]$LogFile,
    [string]$BootstrapCopy = ''
)

$ErrorActionPreference = 'Stop'

function Write-CleanupLog {
    param([string]$Message)
    try {
        Add-Content -LiteralPath $LogFile `
            -Value ("{0} Cleanup: {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) `
            -Encoding UTF8
    } catch {}
}

try {
    Start-Sleep -Seconds 3

    $source = [IO.Path]::GetFullPath($SourceDir).TrimEnd('\')
    $install = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
    $leaf = Split-Path -Leaf $source
    $driveRoot = [IO.Path]::GetPathRoot($source)

    if ($source.StartsWith('\\') -or $source -eq $install -or $source.Length -le $driveRoot.Length) {
        throw "Unsafe source path: $source"
    }
    if ($leaf -notmatch '^PCAgent-\d+(?:\.\d+){2}-manual(?:-[A-Za-z0-9]+)?$') {
        throw "Unexpected source folder name: $leaf"
    }

    $sourceItem = Get-Item -LiteralPath $source -Force
    if (($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Source folder is a reparse point: $source"
    }

    $driveId = $driveRoot.TrimEnd('\')
    $drive = Get-CimInstance Win32_LogicalDisk -ErrorAction SilentlyContinue |
        Where-Object { $_.DeviceID -eq $driveId } |
        Select-Object -First 1
    if (!$drive -or $drive.DriveType -ne 3) {
        throw "Source is not on a local fixed disk: $source"
    }

    $required = @(
        '.pcagent-manual-package',
        'install-agent.bat',
        'agent.jar',
        'agent-watchdog.ps1',
        'agent-bundle-updater.ps1',
        'bundle-manifest.json',
        'manual-install-cleanup.ps1',
        'jre\bin\java.exe',
        'jre\lib\modules'
    )
    foreach ($relativePath in $required) {
        if (!(Test-Path -LiteralPath (Join-Path $source $relativePath))) {
            throw "Required package file is missing: $relativePath"
        }
    }

    $marker = Get-Content -LiteralPath (Join-Path $source '.pcagent-manual-package') -Raw
    if ($marker.Trim() -ne 'PCAgent manual install package') {
        throw 'Package marker is invalid.'
    }

    Set-Location -LiteralPath $env:TEMP
    [Environment]::CurrentDirectory = $env:TEMP
    Remove-Item -LiteralPath $source -Recurse -Force
    Write-CleanupLog "Removed source folder: $source"

    if ($BootstrapCopy) {
        $bootstrapPath = [IO.Path]::GetFullPath($BootstrapCopy)
        $tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        if ($bootstrapPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $bootstrapPath) -like 'PCAgentManualInstall-*.cmd') {
            Remove-Item -LiteralPath $bootstrapPath -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-CleanupLog $_.Exception.Message
    exit 1
} finally {
    $selfPath = $PSCommandPath
    if ($selfPath -and (Test-Path -LiteralPath $selfPath)) {
        Remove-Item -LiteralPath $selfPath -Force -ErrorAction SilentlyContinue
    }
}
