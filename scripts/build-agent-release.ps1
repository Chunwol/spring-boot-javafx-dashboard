[CmdletBinding()]
param(
    [string]$Version,
    [string]$BaseManualDir = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$distRoot = Join-Path $repoRoot 'dist'
$jarPath = Join-Path $repoRoot 'agent\build\libs\agent.jar'

if ([string]::IsNullOrWhiteSpace($Version)) {
    $buildText = Get-Content -LiteralPath (Join-Path $repoRoot 'agent\build.gradle') -Raw
    $match = [regex]::Match($buildText, "(?m)^version\s*=\s*'([^']+)'\s*$")
    if (-not $match.Success) { throw 'Unable to read agent version from agent/build.gradle.' }
    $Version = $match.Groups[1].Value
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid version: $Version" }
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Build the agent JAR first: $jarPath"
}

if ([string]::IsNullOrWhiteSpace($BaseManualDir)) {
    $BaseManualDir = Get-ChildItem (Join-Path $distRoot 'manual-install') -Directory |
        Where-Object { $_.Name -match '^PCAgent-\d+\.\d+\.\d+-manual$' } |
        Sort-Object { [version](($_.Name -replace '^PCAgent-', '') -replace '-manual$', '') } -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not (Test-Path -LiteralPath $BaseManualDir -PathType Container)) {
    throw "Base manual package not found: $BaseManualDir"
}

$manualName = "PCAgent-$Version-manual"
$bundleName = "PCAgent-$Version-update-bundle"
$manualDir = Join-Path $distRoot "manual-install\$manualName"
$manualZip = Join-Path $distRoot "manual-install\$manualName.zip"
$bundleDir = Join-Path $distRoot "update-bundle\$bundleName"
$bundleZip = Join-Path $distRoot "update-bundle\$bundleName.zip"

foreach ($target in @($manualDir, $manualZip, $bundleDir, $bundleZip)) {
    if (Test-Path -LiteralPath $target) {
        if (-not $Force) { throw "Release target already exists: $target" }
        $resolved = [IO.Path]::GetFullPath($target)
        $allowedRoot = [IO.Path]::GetFullPath($distRoot) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove a path outside dist: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

function Copy-ReleaseFile([string]$source, [string]$destination) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Required release file not found: $source"
    }
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

function Get-RelativePath([string]$root, [string]$path) {
    return [IO.Path]::GetRelativePath($root, $path).Replace('\', '/')
}

function Write-BundleManifest([string]$directory, [string]$jreVersion, [string]$vncVersion) {
    $files = @(Get-ChildItem -LiteralPath $directory -File -Recurse -Force |
        Where-Object { $_.Name -ne 'bundle-manifest.json' } |
        ForEach-Object {
            [ordered]@{
                path = Get-RelativePath $directory $_.FullName
                size = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        } | Sort-Object path)
    $manifest = [ordered]@{
        schemaVersion = 1
        bundleVersion = $Version
        minimumBootstrapVersion = '1.1.20'
        createdAt = [DateTimeOffset]::Now.ToString('o')
        components = [ordered]@{
            agent = $Version
            jre = $jreVersion
            vnc = $vncVersion
            watchdog = $Version
        }
        files = $files
    }
    $manifest | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath (Join-Path $directory 'bundle-manifest.json') -Encoding UTF8
}

function Test-BundleManifest([string]$directory) {
    $manifest = Get-Content -LiteralPath (Join-Path $directory 'bundle-manifest.json') -Raw | ConvertFrom-Json
    if ([string]$manifest.bundleVersion -ne $Version) { throw "Manifest version mismatch: $directory" }
    foreach ($file in $manifest.files) {
        $path = Join-Path $directory ([string]$file.path).Replace('/', '\')
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Manifest file missing: $path" }
        $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne ([string]$file.sha256).ToLowerInvariant()) {
            throw "Manifest hash mismatch: $path"
        }
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$baseManifest = Get-Content -LiteralPath (Join-Path $BaseManualDir 'bundle-manifest.json') -Raw | ConvertFrom-Json
$jreVersion = [string]$baseManifest.components.jre
$vncVersion = [string]$baseManifest.components.vnc

New-Item -ItemType Directory -Path $manualDir -Force | Out-Null
Get-ChildItem -LiteralPath $BaseManualDir -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $manualDir -Recurse -Force
}
Copy-ReleaseFile $jarPath (Join-Path $manualDir 'agent.jar')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'agent-watchdog.ps1') (Join-Path $manualDir 'agent-watchdog.ps1')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'install-helper.ps1') (Join-Path $manualDir 'install-helper.ps1')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'agent-bundle-updater.ps1') (Join-Path $manualDir 'agent-bundle-updater.ps1')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'manual-install-cleanup.ps1') (Join-Path $manualDir 'manual-install-cleanup.ps1')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'install.bat') (Join-Path $manualDir 'install-agent.bat')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'uninstall.bat') (Join-Path $manualDir 'uninstall-agent.bat')
Copy-ReleaseFile (Join-Path $PSScriptRoot 'manual-install-readme.txt') (Join-Path $manualDir 'README.txt')
Write-BundleManifest $manualDir $jreVersion $vncVersion
Test-BundleManifest $manualDir
[IO.Compression.ZipFile]::CreateFromDirectory($manualDir, $manualZip, [IO.Compression.CompressionLevel]::Optimal, $false)

New-Item -ItemType Directory -Path $bundleDir -Force | Out-Null
foreach ($name in @('jre', 'vnc')) {
    Copy-Item -LiteralPath (Join-Path $manualDir $name) -Destination $bundleDir -Recurse -Force
}
foreach ($name in @('agent.jar', 'agent-watchdog.ps1', 'install-helper.ps1', 'agent-bundle-updater.ps1')) {
    Copy-ReleaseFile (Join-Path $manualDir $name) (Join-Path $bundleDir $name)
}
Write-BundleManifest $bundleDir $jreVersion $vncVersion
Test-BundleManifest $bundleDir
[IO.Compression.ZipFile]::CreateFromDirectory($bundleDir, $bundleZip, [IO.Compression.CompressionLevel]::Optimal, $false)

[pscustomobject]@{
    version = $Version
    agentJar = $jarPath
    agentJarSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    manualZip = $manualZip
    manualZipSha256 = (Get-FileHash -LiteralPath $manualZip -Algorithm SHA256).Hash.ToLowerInvariant()
    updateBundleZip = $bundleZip
    updateBundleZipSha256 = (Get-FileHash -LiteralPath $bundleZip -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json -Depth 4
