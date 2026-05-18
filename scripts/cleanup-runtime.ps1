param(
    [switch]$NoBanner,
    [switch]$IncludeDocker
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host "[CLEANUP] $Message" -ForegroundColor Cyan
}

function Write-WarnLine([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Get-EnvMap([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in Get-Content -Path $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.TrimStart().StartsWith("#")) { continue }
        $idx = $line.IndexOf("=")
        if ($idx -le 0) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        $map[$key] = $value
    }
    return $map
}

function Stop-ProcessByPid([int]$ProcessId, [string]$Reason) {
    if ($ProcessId -le 0) { return }
    if (-not $script:StoppedProcessIds) {
        $script:StoppedProcessIds = @{}
    }
    if ($script:StoppedProcessIds.ContainsKey($ProcessId)) { return }
    $script:StoppedProcessIds[$ProcessId] = $true
    try {
        $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($null -ne $proc) {
            Write-Step "Stopping PID $ProcessId ($Reason)"
            Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
        } else {
            # No process found — may be a phantom TCP socket (Windows TCP stack artifact after sleep/resume).
            # taskkill is attempted as a last resort; failure is expected and harmless.
            Write-Step "Attempting taskkill on phantom PID $ProcessId ($Reason)"
            try { $null = & cmd /c "taskkill /F /PID $ProcessId > nul 2>&1" } catch {}
        }
    } catch {
        # Ignore — best-effort cleanup.
    }
}

function Stop-FromPidFile([string]$Path, [string]$Reason) {
    if (-not (Test-Path $Path)) { return }
    try {
        $raw = (Get-Content -Path $Path -TotalCount 1 -ErrorAction SilentlyContinue | Out-String).Trim()
        $pidValue = 0
        if ([int]::TryParse($raw, [ref]$pidValue) -and $pidValue -gt 0) {
            Stop-ProcessByPid -ProcessId $pidValue -Reason $Reason
        }
    } finally {
        Remove-Item -Path $Path -Force -ErrorAction SilentlyContinue
    }
}

function Stop-ListenersByPort([int[]]$Ports) {
    foreach ($port in ($Ports | Select-Object -Unique)) {
        if ($port -le 0) { continue }
        try {
            $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
            $listenerPids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
            foreach ($listenerPid in $listenerPids) {
                Stop-ProcessByPid -ProcessId $listenerPid -Reason "listening on port $port"
            }
            # Extra wait after killing to let the OS release the socket.
            if ($listenerPids.Count -gt 0) { Start-Sleep -Milliseconds 600 }
        } catch {
            # Non-fatal on systems without Get-NetTCPConnection support.
        }
    }
}

$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir ".."))

if (-not $NoBanner) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host "=== Runtime Cleanup                                        " -ForegroundColor DarkCyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
}

$backendEnv = Join-Path $projectRoot "backend\.env"
$envMap = Get-EnvMap -Path $backendEnv

$postgresRuntimePort = 5433
[void][int]::TryParse($envMap["POSTGRES_RUNTIME_PORT"], [ref]$postgresRuntimePort)
if ($postgresRuntimePort -le 0) { $postgresRuntimePort = 5433 }

$redisPort = 6379
[void][int]::TryParse($envMap["REDIS_PORT"], [ref]$redisPort)
if ($redisPort -le 0) {
    [void][int]::TryParse($envMap["REDIS_RUNTIME_PORT"], [ref]$redisPort)
}
if ($redisPort -le 0) { $redisPort = 6379 }

$redisPidFile = Join-Path $projectRoot ".runtime\redis\redis-runtime.pid"
$postgresPidFile = Join-Path $projectRoot ".runtime\postgres\data\postmaster.pid"

Write-Step "Project root: $projectRoot"
Stop-FromPidFile -Path $redisPidFile -Reason "redis runtime pid file"
Stop-FromPidFile -Path $postgresPidFile -Reason "postgres runtime pid file"

# Kill ALL postgres.exe processes running from the runtime bin directory.
# This is needed when the postmaster.pid is missing or stale, or the process
# was started on a fallback port not reflected in the pid file.
$runtimePgBinDir = Join-Path $projectRoot ".runtime\postgres\bin"
if (Test-Path $runtimePgBinDir) {
    $runtimePgBinNorm = [System.IO.Path]::GetFullPath($runtimePgBinDir).TrimEnd('\')
    $runtimePostgresProcs = @(Get-Process -Name "postgres" -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -and [System.IO.Path]::GetFullPath($_.Path).StartsWith(
            $runtimePgBinNorm, [System.StringComparison]::OrdinalIgnoreCase)
    })
    foreach ($p in $runtimePostgresProcs) {
        Write-Step "Killing runtime postgres process (PID $($p.Id))"
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
    if ($runtimePostgresProcs.Count -gt 0) {
        Start-Sleep -Milliseconds 800
    }
}

# Diagnose and report what is holding the runtime ports before killing.
foreach ($diagPort in @($postgresRuntimePort, $redisPort) | Select-Object -Unique) {
    if ($diagPort -le 0) { continue }
    try {
        $listeners = @(Get-NetTCPConnection -LocalPort $diagPort -State Listen -ErrorAction SilentlyContinue)
        foreach ($l in $listeners) {
            $lp = Get-Process -Id $l.OwningProcess -ErrorAction SilentlyContinue
            $lpPath = if ($lp -and $lp.MainModule) { $lp.MainModule.FileName } else { "(no path)" }
            Write-WarnLine "Port $diagPort held by PID $($l.OwningProcess) [$($lp.ProcessName)] @ $lpPath"
        }
    } catch {}
}

# Fallback: ensure configured runtime ports are free.
Stop-ListenersByPort -Ports @($postgresRuntimePort, $redisPort)

if ($IncludeDocker) {
    $composeFile = Join-Path $projectRoot "docker-compose.yml"
    if (Test-Path $composeFile) {
        $dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
        if ($dockerCmd) {
            Write-Step "Stopping docker compose stack"
            Push-Location $projectRoot
            try {
                & docker compose down -v 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    & docker-compose down -v 2>$null | Out-Null
                }
            } catch {
                Write-WarnLine "Docker compose cleanup skipped: $($_.Exception.Message)"
            } finally {
                Pop-Location
            }
        }
    }
}

Write-Step "Cleanup complete"
exit 0
