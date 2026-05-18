param(
    [switch]$NoBanner
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Write-Step([string]$Message) {
    Write-Host "[DB-BACKUP] $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Get-EnvMap([string]$Path) {
    $map = [ordered]@{}
    if (-not (Test-Path $Path)) {
        return $map
    }
    foreach ($line in Get-Content -Path $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.TrimStart().StartsWith("#")) { continue }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1)
        $map[$key] = $value
    }
    return $map
}

function Parse-PostgresJdbc([string]$JdbcUrl) {
    $match = [regex]::Match($JdbcUrl, '^jdbc:postgresql://(?<host>[^:/\?]+)(:(?<port>\d+))?/(?<db>[^?\s]+)')
    if (-not $match.Success) {
        throw "DB_URL must be jdbc:postgresql://host:port/database format. Current: $JdbcUrl"
    }
    $dbHost = $match.Groups["host"].Value
    $port = if ($match.Groups["port"].Success) { [int]$match.Groups["port"].Value } else { 5432 }
    $db = $match.Groups["db"].Value
    return [pscustomobject]@{
        Host = $dbHost
        Port = $port
        Database = $db
    }
}

function Find-PostgresBinary([string]$BinaryName) {
    $cmd = Get-Command $BinaryName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source) {
        return $cmd.Source
    }
    $roots = @(
        "C:\Program Files\PostgreSQL",
        "C:\Program Files (x86)\PostgreSQL"
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $candidate = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName ("bin\" + $BinaryName + ".exe") } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate
        }
    }
    return $null
}

function Resolve-PathFromProject([string]$ProjectRoot, [string]$PathValue) {
    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot "backups\postgres"))
    }
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $PathValue))
}

$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir ".."))
$backendEnvPath = Join-Path $projectRoot "backend\.env"

if (-not $NoBanner) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host "=== PostgreSQL Backup                                      " -ForegroundColor DarkCyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
}

$envMap = Get-EnvMap -Path $backendEnvPath
if (-not $envMap.Contains("DB_URL")) {
    throw "backend/.env missing DB_URL"
}

$dbInfo = Parse-PostgresJdbc -JdbcUrl $envMap["DB_URL"]
$dbUser = if ($envMap.Contains("DB_USERNAME")) { $envMap["DB_USERNAME"] } else { "dashboard" }
$dbPassword = if ($envMap.Contains("DB_PASSWORD")) { $envMap["DB_PASSWORD"] } else { "" }

$pgDumpPath = Find-PostgresBinary -BinaryName "pg_dump"
if (-not $pgDumpPath) {
    throw "pg_dump executable not found. Install PostgreSQL client tools."
}

$backupDir = Resolve-PathFromProject -ProjectRoot $projectRoot -PathValue $envMap["DB_BACKUP_DIR"]
if (-not (Test-Path $backupDir)) {
    New-Item -Path $backupDir -ItemType Directory -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backupFile = Join-Path $backupDir ("{0}_{1}.dump" -f $dbInfo.Database, $timestamp)

Write-Step "Creating backup: $backupFile"
$oldPwd = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $dbPassword
    & $pgDumpPath -h $dbInfo.Host -p $dbInfo.Port -U $dbUser -d $dbInfo.Database -F c --no-owner --no-privileges -f $backupFile
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($null -eq $oldPwd) {
        Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    } else {
        $env:PGPASSWORD = $oldPwd
    }
}

$keepDays = 14
if ($envMap.Contains("DB_BACKUP_KEEP_DAYS")) {
    [void][int]::TryParse($envMap["DB_BACKUP_KEEP_DAYS"], [ref]$keepDays)
}
$cutoff = (Get-Date).AddDays(-1 * $keepDays)
Get-ChildItem -Path $backupDir -Filter "*.dump" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    Remove-Item -Force -ErrorAction SilentlyContinue

Write-Ok "Backup completed"
Write-Host "  File: $backupFile"
Write-Host "  Keep days: $keepDays"
Write-Host ""

exit 0
