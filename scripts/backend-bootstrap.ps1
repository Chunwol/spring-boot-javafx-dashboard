param(
    [bool]$AutoInstall = $true,
    [bool]$RegisterBackupTask = $false,
    [bool]$RestoreIfEmpty = $true,
    [switch]$NoBanner
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Write-Step([string]$Message) {
    Write-Host "[BOOTSTRAP] $Message" -ForegroundColor Cyan
}

function Write-WarnLine([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
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

function Set-EnvValue([string]$Path, [string]$Key, [string]$Value) {
    $lines = New-Object System.Collections.Generic.List[string]
    if (Test-Path $Path) {
        foreach ($line in Get-Content -Path $Path) {
            $lines.Add($line)
        }
    }
    $pattern = "^\s*" + [regex]::Escape($Key) + "\s*="
    $updated = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) {
            $lines[$i] = "$Key=$Value"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        if ($lines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($lines[$lines.Count - 1])) {
            $lines.Add("")
        }
        $lines.Add("$Key=$Value")
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $lines, $utf8NoBom)
}

function Has-Text([string]$Value) {
    return -not [string]::IsNullOrWhiteSpace($Value)
}

function Parse-BoolOrDefault([string]$Value, [bool]$DefaultValue = $false) {
    if (-not (Has-Text $Value)) {
        return $DefaultValue
    }
    $normalized = $Value.Trim().ToLowerInvariant()
    if ($normalized -in @("1", "true", "yes", "y", "on")) {
        return $true
    }
    if ($normalized -in @("0", "false", "no", "n", "off")) {
        return $false
    }
    return $DefaultValue
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

function Test-TcpPort([string]$Address, [int]$Port, [int]$TimeoutMs = 1500) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($Address, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            return $false
        }
        $client.EndConnect($async) | Out-Null
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Find-AvailableTcpPort([int]$StartPort, [int]$MaxAttempts = 50) {
    $port = $StartPort
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        if (-not (Test-TcpPort -Address "127.0.0.1" -Port $port -TimeoutMs 300)) {
            return $port
        }
        $port++
    }
    return $null
}

function Find-CommandOrNull([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd -and $cmd.Source) {
        return $cmd.Source
    }
    return $null
}

function Find-PostgresBinary([string]$BinaryName, [string]$ExcludePathPrefix = "") {
    $excludePrefix = ""
    if (Has-Text $ExcludePathPrefix) {
        $excludePrefix = [System.IO.Path]::GetFullPath($ExcludePathPrefix).TrimEnd('\\')
    }

    $byPath = Find-CommandOrNull $BinaryName
    if (Has-Text $byPath) {
        if (-not (Has-Text $excludePrefix)) {
            return $byPath
        }

        $fullByPath = [System.IO.Path]::GetFullPath($byPath)
        if (-not $fullByPath.StartsWith($excludePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $byPath
        }
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
        if (Has-Text $candidate) {
            if (-not (Has-Text $excludePrefix)) {
                return $candidate
            }

            $fullCandidate = [System.IO.Path]::GetFullPath($candidate)
            if (-not $fullCandidate.StartsWith($excludePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
                return $candidate
            }
        }
    }
    return $null
}

function Get-ServiceNamesLike([string[]]$Patterns) {
    $all = @()
    try {
        $all = Get-CimInstance Win32_Service -ErrorAction Stop
    } catch {
        return @()
    }
    $matched = New-Object System.Collections.Generic.List[string]
    foreach ($svc in $all) {
        foreach ($pattern in $Patterns) {
            if ($svc.Name -like $pattern -or $svc.DisplayName -like $pattern) {
                $matched.Add($svc.Name)
                break
            }
        }
    }
    return $matched | Select-Object -Unique
}

function Start-ServicesIfNeeded([string[]]$Names) {
    foreach ($name in $Names) {
        try {
            $service = Get-Service -Name $name -ErrorAction Stop
            if ($service.Status -ne "Running") {
                Write-Step "Starting service: $name"
                Start-Service -Name $name -ErrorAction Stop
                Start-Sleep -Seconds 2
            }
        } catch {
            Write-WarnLine "Could not start service '$name': $($_.Exception.Message)"
        }
    }
}

function Invoke-PsqlQuery(
    [string]$PsqlPath,
    [string]$DbHost,
    [int]$Port,
    [string]$User,
    [string]$Database,
    [string]$Password,
    [string]$Sql
) {
    $oldPwd = $env:PGPASSWORD
    $sqlFile = [System.IO.Path]::GetTempFileName()
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $env:PGPASSWORD = $Password
        Set-Content -Path $sqlFile -Value $Sql -Encoding ASCII
        $args = @(
            "-h", $DbHost,
            "-p", "$Port",
            "-U", $User,
            "-d", $Database,
            "-v", "ON_ERROR_STOP=1",
            "-tA",
            "-f", $sqlFile
        )
        $proc = Start-Process -FilePath $PsqlPath -ArgumentList $args -NoNewWindow -PassThru -Wait `
            -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
        $stdout = ""
        $stderr = ""
        if (Test-Path $stdoutFile) {
            $stdoutRaw = Get-Content -Path $stdoutFile -Raw -ErrorAction SilentlyContinue
            if ($null -ne $stdoutRaw) { $stdout = ($stdoutRaw | Out-String).Trim() }
        }
        if (Test-Path $stderrFile) {
            $stderrRaw = Get-Content -Path $stderrFile -Raw -ErrorAction SilentlyContinue
            if ($null -ne $stderrRaw) { $stderr = ($stderrRaw | Out-String).Trim() }
        }

        if ($proc.ExitCode -ne 0) {
            return [pscustomobject]@{
                Success = $false
                Output = $stderr
            }
        }
        return [pscustomobject]@{
            Success = $true
            Output = $stdout
        }
    } finally {
        if ($null -eq $oldPwd) {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PGPASSWORD = $oldPwd
        }
        Remove-Item -Path $sqlFile -ErrorAction SilentlyContinue
        Remove-Item -Path $stdoutFile -ErrorAction SilentlyContinue
        Remove-Item -Path $stderrFile -ErrorAction SilentlyContinue
    }
}

function Test-DbConnection(
    [string]$PsqlPath,
    [string]$DbHost,
    [int]$Port,
    [string]$User,
    [string]$Database,
    [string]$Password
) {
    $result = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $User -Database $Database -Password $Password -Sql "SELECT 1;"
    return $result.Success -and $result.Output -eq "1"
}

function Ensure-RoleAndDatabase(
    [string]$PsqlPath,
    [string]$DbHost,
    [int]$Port,
    [string]$DbUser,
    [string]$DbPassword,
    [string]$DbName,
    [string]$SuperUser,
    [string]$SuperPassword
) {
    $dbLoginResult = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $DbUser -Database $DbName -Password $DbPassword -Sql "SELECT 1;"
    if ($dbLoginResult.Success -and $dbLoginResult.Output -eq "1") {
        return $true
    }
    if ($dbLoginResult.Success -and $dbLoginResult.Output -ne "1") {
        Write-WarnLine "Unexpected DB probe output on ${DbHost}:$Port for user '$DbUser': '$($dbLoginResult.Output)'"
    }

    if ($DbUser -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Unsupported DB username for auto-provisioning: $DbUser"
    }
    if ($DbName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw "Unsupported DB name for auto-provisioning: $DbName"
    }

    $superProbe = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $SuperUser -Database "postgres" -Password $SuperPassword -Sql "SELECT 1;"
    if (-not ($superProbe.Success -and $superProbe.Output -eq "1")) {
        Write-WarnLine "Superuser login failed on ${DbHost}:$Port. role/db auto-provision skipped."
        return $false
    }

    $escapedPwd = $DbPassword.Replace("'", "''")
    $roleExistsResult = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $SuperUser -Database "postgres" -Password $SuperPassword -Sql "SELECT 1 FROM pg_roles WHERE rolname = '$DbUser';"
    if (-not $roleExistsResult.Success) {
        Write-WarnLine "Role existence check failed: $($roleExistsResult.Output)"
        return $false
    }

    $roleSql = if ($roleExistsResult.Output -eq "1") {
        "ALTER ROLE `"$DbUser`" WITH LOGIN PASSWORD '$escapedPwd';"
    } else {
        "CREATE ROLE `"$DbUser`" LOGIN PASSWORD '$escapedPwd';"
    }
    $roleResult = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $SuperUser -Database "postgres" -Password $SuperPassword -Sql $roleSql
    if (-not $roleResult.Success) {
        Write-WarnLine "Role provisioning failed: $($roleResult.Output)"
        return $false
    }

    $dbExistsResult = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $SuperUser -Database "postgres" -Password $SuperPassword -Sql "SELECT 1 FROM pg_database WHERE datname = '$DbName';"
    if (-not $dbExistsResult.Success) {
        Write-WarnLine "Database existence check failed: $($dbExistsResult.Output)"
        return $false
    }
    if ($dbExistsResult.Output -ne "1") {
        $createDbResult = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $SuperUser -Database "postgres" -Password $SuperPassword -Sql "CREATE DATABASE `"$DbName`" OWNER `"$DbUser`";"
        if (-not $createDbResult.Success) {
            Write-WarnLine "Database creation failed: $($createDbResult.Output)"
            return $false
        }
    }

    return (Test-DbConnection -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $DbUser -Database $DbName -Password $DbPassword)
}

function Ensure-PortablePostgres(
    [string]$ProjectRoot,
    [string]$PsqlPath,
    [int]$Port,
    [string]$DbUser,
    [string]$DbPassword,
    [string]$DbName,
    [string]$SuperUser,
    [string]$SuperPassword
) {
    $binDir = Split-Path $PsqlPath -Parent
    $initdbPath = Join-Path $binDir "initdb.exe"
    $pgCtlPath = Join-Path $binDir "pg_ctl.exe"

    if (-not (Test-Path $initdbPath) -or -not (Test-Path $pgCtlPath)) {
        Write-WarnLine "Portable PostgreSQL startup skipped: initdb/pg_ctl binaries not found."
        return [pscustomobject]@{
            Success = $false
            Port = $Port
        }
    }

    $runtimeRoot = Join-Path $ProjectRoot ".runtime\postgres"
    $dataDir = Join-Path $runtimeRoot "data"
    $logPath = Join-Path $runtimeRoot "postgres.log"
    New-Item -Path $runtimeRoot -ItemType Directory -Force | Out-Null
    New-Item -Path $dataDir -ItemType Directory -Force | Out-Null

    $versionFile = Join-Path $dataDir "PG_VERSION"
    if (-not (Test-Path $versionFile)) {
        $existing = Get-ChildItem -Path $dataDir -Force -ErrorAction SilentlyContinue
        if ($existing) {
            Remove-Item -Path (Join-Path $dataDir "*") -Recurse -Force -ErrorAction SilentlyContinue
        }

        $pwFile = Join-Path $runtimeRoot "super.pw"
        Set-Content -Path $pwFile -Value $SuperPassword -Encoding ASCII
        try {
            Write-Step "Initializing portable PostgreSQL data directory..."
            & $initdbPath -D $dataDir -U $SuperUser --auth-local=scram-sha-256 --auth-host=scram-sha-256 --pwfile=$pwFile | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-WarnLine "Portable PostgreSQL initdb failed (exit=$LASTEXITCODE)."
                return [pscustomobject]@{
                    Success = $false
                    Port = $Port
                }
            }
        } finally {
            Remove-Item -Path $pwFile -Force -ErrorAction SilentlyContinue
        }
    }

    $targetPort = $Port
    if (Test-TcpPort -Address "127.0.0.1" -Port $targetPort -TimeoutMs 500) {
        if (Test-DbConnection -PsqlPath $PsqlPath -DbHost "127.0.0.1" -Port $targetPort -User $DbUser -Database $DbName -Password $DbPassword) {
            Write-Ok "Portable PostgreSQL already reachable on 127.0.0.1:$targetPort"
            return [pscustomobject]@{
                Success = $true
                Port = $targetPort
            }
        }

        $nextPort = Find-AvailableTcpPort -StartPort ($targetPort + 1) -MaxAttempts 100
        if ($null -eq $nextPort) {
            Write-WarnLine "Portable PostgreSQL port $targetPort is busy and no fallback port was found."
            return [pscustomobject]@{
                Success = $false
                Port = $targetPort
            }
        }
        Write-WarnLine "Portable PostgreSQL port $targetPort already used by another process. Switching runtime PostgreSQL to $nextPort."
        $targetPort = $nextPort
    }

    & $pgCtlPath -D $dataDir status 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Step "Stopping existing portable PostgreSQL instance..."
        & $pgCtlPath -D $dataDir stop -m fast 2>$null | Out-Null
        Start-Sleep -Milliseconds 700
        
        # Additional check: if still running, forcefully kill postgres processes
        $postgresProcesses = Get-Process -Name "postgres" -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -and $_.Path.StartsWith($runtimePgRoot, [System.StringComparison]::OrdinalIgnoreCase)
        }
        if ($postgresProcesses) {
            Write-Step "Force terminating remaining PostgreSQL processes..."
            $postgresProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 500
        }
    }

    $postmasterPidFile = Join-Path $dataDir "postmaster.pid"
    if (Test-Path $postmasterPidFile) {
        $pidLine = (Get-Content -Path $postmasterPidFile -TotalCount 1 -ErrorAction SilentlyContinue | Out-String).Trim()
        $pidValue = 0
        $pidParsed = [int]::TryParse($pidLine, [ref]$pidValue)
        $ownerProcess = $null
        if ($pidParsed -and $pidValue -gt 0) {
            $ownerProcess = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
        }
        if (($null -eq $ownerProcess) -or ($ownerProcess.ProcessName -notlike "postgres*")) {
            Remove-Item -Path $postmasterPidFile -Force -ErrorAction SilentlyContinue
            Write-Step "Removed stale portable PostgreSQL postmaster.pid."
        }
    }

    if (Test-TcpPort -Address "127.0.0.1" -Port $targetPort -TimeoutMs 500) {
        if (Test-DbConnection -PsqlPath $PsqlPath -DbHost "127.0.0.1" -Port $targetPort -User $DbUser -Database $DbName -Password $DbPassword) {
            Write-Ok "Portable PostgreSQL already reachable on 127.0.0.1:$targetPort"
            return [pscustomobject]@{
                Success = $true
                Port = $targetPort
            }
        }

        $nextPort = Find-AvailableTcpPort -StartPort ($targetPort + 1) -MaxAttempts 100
        if ($null -eq $nextPort) {
            Write-WarnLine "Portable PostgreSQL port $targetPort became busy before start and no fallback port was found."
            return [pscustomobject]@{
                Success = $false
                Port = $targetPort
            }
        }
        Write-WarnLine "Portable PostgreSQL port $targetPort became busy before start. Switching runtime PostgreSQL to $nextPort."
        $targetPort = $nextPort
    }

    Write-Step "Starting portable PostgreSQL on 127.0.0.1:$targetPort ..."
    $startExitCode = -1
    $startTimedOut = $false
    $startOutput = ""
    $startStdOut = [System.IO.Path]::GetTempFileName()
    $startStdErr = [System.IO.Path]::GetTempFileName()
    try {
        $startArgLine = "-D `"$dataDir`" -l `"$logPath`" -o `"-h 127.0.0.1 -p $targetPort`" -w -t 20 start"
        $startProc = Start-Process -FilePath $pgCtlPath -ArgumentList $startArgLine -NoNewWindow -PassThru `
            -RedirectStandardOutput $startStdOut -RedirectStandardError $startStdErr
        if (-not $startProc.WaitForExit(35000)) {
            $startTimedOut = $true
            Write-WarnLine "pg_ctl start did not exit within 35 seconds. Continuing with readiness checks."
            Stop-Process -Id $startProc.Id -Force -ErrorAction SilentlyContinue
        } else {
            $procExitCode = $startProc.ExitCode
            if ($null -eq $procExitCode) {
                $startExitCode = 0
            } else {
                $startExitCode = [int]$procExitCode
            }
        }

        $stdoutText = Get-Content -Path $startStdOut -Raw -ErrorAction SilentlyContinue
        $stderrText = Get-Content -Path $startStdErr -Raw -ErrorAction SilentlyContinue
        $startOutput = ("$stdoutText`n$stderrText").Trim()
    } finally {
        Remove-Item -Path $startStdOut -ErrorAction SilentlyContinue
        Remove-Item -Path $startStdErr -ErrorAction SilentlyContinue
    }

    if (-not $startTimedOut -and $startExitCode -ne 0) {
        $logTail = ""
        if (Test-Path $logPath) {
            $logTail = ((Get-Content -Path $logPath -Tail 5 -ErrorAction SilentlyContinue) -join " | ").Trim()
        }
        if (Has-Text $startOutput) {
            $startOutput = $startOutput -replace '[\r\n]+', ' | '
        }
        if (Has-Text $logTail -and Has-Text $startOutput) {
            Write-WarnLine "Portable PostgreSQL start reported failure (exit=$startExitCode). Continuing with readiness checks. Output: $startOutput | Recent log: $logTail"
        } elseif (Has-Text $logTail) {
            Write-WarnLine "Portable PostgreSQL start reported failure (exit=$startExitCode). Continuing with readiness checks. Recent log: $logTail"
        } elseif (Has-Text $startOutput) {
            Write-WarnLine "Portable PostgreSQL start reported failure (exit=$startExitCode). Continuing with readiness checks. Output: $startOutput"
        } else {
            Write-WarnLine "Portable PostgreSQL start reported failure (exit=$startExitCode). Continuing with readiness checks."
        }
    }

    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        if (Test-TcpPort -Address "127.0.0.1" -Port $targetPort -TimeoutMs 1000) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        Write-WarnLine "Portable PostgreSQL did not open port $targetPort in time."
        return [pscustomobject]@{
            Success = $false
            Port = $targetPort
        }
    }

    if (-not (Ensure-RoleAndDatabase -PsqlPath $PsqlPath -DbHost "127.0.0.1" -Port $targetPort -DbUser $DbUser -DbPassword $DbPassword -DbName $DbName -SuperUser $SuperUser -SuperPassword $SuperPassword)) {
        Write-WarnLine "Portable PostgreSQL role/database provisioning failed."
        return [pscustomobject]@{
            Success = $false
            Port = $targetPort
        }
    }

    Write-Ok "Portable PostgreSQL is ready on 127.0.0.1:$targetPort"
    return [pscustomobject]@{
        Success = $true
        Port = $targetPort
    }
}

function Apply-PostgresTextColumnCompatibility(
    [string]$PsqlPath,
    [string]$DbHost,
    [int]$Port,
    [string]$DbUser,
    [string]$DbName,
    [string]$DbPassword
) {
    $sql = @'
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('agent_auth_tokens', 'issued_at'),
            ('agent_auth_tokens', 'last_used_at'),
            ('command_queue', 'created_at'),
            ('command_queue', 'delivered_at'),
            ('command_queue', 'acked_at'),
            ('command_queue', 'completed_at'),
            ('command_queue', 'scmate_reset_requested_at'),
            ('command_queue', 'scmate_reset_completed_at'),
            ('command_queue', 'verification_started_at'),
            ('command_queue', 'verification_deadline_at'),
            ('command_queue', 'verification_observed_offline_at'),
            ('command_queue', 'verification_completed_at'),
            ('dashboard_widget_items', 'created_at'),
            ('scripts', 'created_at'),
            ('operational_alerts', 'acknowledged_at'),
            ('operational_alerts', 'created_at')
        ) AS t(tbl, col)
    LOOP
        IF EXISTS (
            SELECT 1
            FROM information_schema.columns c
            WHERE c.table_schema = 'public'
              AND c.table_name = r.tbl
              AND c.column_name = r.col
              AND c.data_type <> 'text'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I ALTER COLUMN %I TYPE TEXT USING CASE WHEN %2$I IS NULL THEN NULL ELSE %2$I::text END',
                r.tbl, r.col
            );
        END IF;
    END LOOP;
END $$;
'@

    $result = Invoke-PsqlQuery -PsqlPath $PsqlPath -DbHost $DbHost -Port $Port -User $DbUser -Database $DbName -Password $DbPassword -Sql $sql
    if (-not $result.Success) {
        Write-WarnLine "PostgreSQL column compatibility patch failed: $($result.Output)"
    } else {
        Write-Ok "PostgreSQL column compatibility patch applied"
    }
}

function Resolve-PathFromProject([string]$ProjectRoot, [string]$PathValue) {
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $PathValue))
}

function Register-BackupTask([string]$ProjectRoot, [string]$BackupTime) {
    $taskName = "QsyncDashboardDailyDbBackup"
    if ($BackupTime -notmatch '^\d{2}:\d{2}$') {
        $BackupTime = "01:00"
    }
    $backupScript = Join-Path $ProjectRoot "scripts\db-backup.ps1"
    $taskArgument = "-NoProfile -ExecutionPolicy Bypass -File `"$backupScript`" -NoBanner"
    $triggerAt = [datetime]::ParseExact($BackupTime, "HH:mm", $null)
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $taskArgument
    $trigger = New-ScheduledTaskTrigger -Daily -At $triggerAt
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable

    try {
        $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
        Write-Ok "Daily DB backup task registered ($taskName @ $BackupTime, user=SYSTEM)"
        return
    } catch {
        Write-WarnLine "SYSTEM task registration failed. Retrying with current user. $($_.Exception.Message)"
    }

    try {
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
        Write-Ok "Daily DB backup task registered ($taskName @ $BackupTime, current user)"
    } catch {
        Write-WarnLine "Could not register backup task: $($_.Exception.Message)"
    }
}

function Ensure-RedisReady(
    [bool]$AutoInstall,
    [string]$ProjectRoot,
    [int]$Port,
    [string]$Password,
    [bool]$RuntimeOnly,
    [string]$BackendEnvPath
) {
    $runtimeRoot = Join-Path $ProjectRoot ".runtime\redis"
    $runtimeBinDir = Join-Path $runtimeRoot "bin"
    $runtimeDataDir = Join-Path $runtimeRoot "data"
    $runtimeConf = Join-Path $runtimeRoot "redis-local.conf"
    $runtimePidFile = Join-Path $runtimeRoot "redis-runtime.pid"
    $runtimeLog = Join-Path $runtimeRoot "redis.log"
    $runtimeStdOut = Join-Path $runtimeRoot "redis-out.log"
    $runtimeStdErr = Join-Path $runtimeRoot "redis-err.log"

    New-Item -Path $runtimeRoot -ItemType Directory -Force | Out-Null
    New-Item -Path $runtimeBinDir -ItemType Directory -Force | Out-Null
    New-Item -Path $runtimeDataDir -ItemType Directory -Force | Out-Null

    if (-not (Has-Text $Password)) {
        $bytes = New-Object byte[] 24
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $rng.Dispose()
        $Password = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "A").Replace("/", "B")
        Set-EnvValue -Path $BackendEnvPath -Key "REDIS_PASSWORD" -Value $Password
        Write-Step "REDIS_PASSWORD was empty. Generated local runtime password."
    }

    $redisServer = Join-Path $runtimeBinDir "redis-server.exe"
    if (-not (Test-Path $redisServer)) {
        $sourceRedisServer = Find-CommandOrNull "redis-server"
        if (-not (Has-Text $sourceRedisServer) -and $AutoInstall -and (Find-CommandOrNull "choco")) {
            Write-Step "Redis not found. Trying Chocolatey install for portable runtime..."
            try {
                & choco install redis-64 --yes --no-progress | Out-Null
            } catch {
                try {
                    & choco install redis --yes --no-progress | Out-Null
                } catch {
                    Write-WarnLine "Chocolatey Redis install failed: $($_.Exception.Message)"
                }
            }
            $sourceRedisServer = Find-CommandOrNull "redis-server"
        }

        if (Has-Text $sourceRedisServer) {
            $sourceDir = Split-Path $sourceRedisServer -Parent
            try {
                Copy-Item -Path (Join-Path $sourceDir "*") -Destination $runtimeBinDir -Recurse -Force -ErrorAction Stop
                Write-Ok "Redis binaries cached to portable runtime: $runtimeBinDir"
            } catch {
                Write-WarnLine "Could not cache Redis binaries: $($_.Exception.Message)"
            }
        }

        if (-not (Test-Path $redisServer)) {
            $tempCandidate = Get-ChildItem -Path $env:TEMP -Filter redis-server.exe -Recurse -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($tempCandidate) {
                try {
                    Copy-Item -Path (Join-Path $tempCandidate.Directory.FullName "*") -Destination $runtimeBinDir -Recurse -Force -ErrorAction Stop
                    Write-Ok "Redis binaries imported from temp cache to: $runtimeBinDir"
                } catch {
                    Write-WarnLine "Redis temp binary copy failed: $($_.Exception.Message)"
                }
            }
        }
    }

    if (-not (Test-Path $redisServer)) {
        throw "redis-server.exe not found for portable runtime. Place Redis binaries under .runtime\\redis\\bin or install Redis once."
    }

    $targetPort = $Port
    $runtimeProcessListening = $false
    if (Test-Path $runtimePidFile) {
        $pidText = (Get-Content -Path $runtimePidFile -Raw -ErrorAction SilentlyContinue)
        $pidValue = 0
        if ([int]::TryParse(($pidText | Out-String).Trim(), [ref]$pidValue) -and $pidValue -gt 0) {
            $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
            if ($proc) {
                # Process exists and port is listening, assume it's our Redis
                $runtimeProcessListening = $true
            } else {
                # Process doesn't exist, clean up stale PID file
                Remove-Item -Path $runtimePidFile -Force -ErrorAction SilentlyContinue
            }
        }
    }

    if (Test-TcpPort -Address "127.0.0.1" -Port $targetPort) {
        if ($RuntimeOnly -and -not $runtimeProcessListening) {
            $nextPort = Find-AvailableTcpPort -StartPort ($targetPort + 1) -MaxAttempts 100
            if ($null -eq $nextPort) {
                throw "Redis runtime-only mode is enabled and no available local port was found."
            }
            Write-WarnLine "Redis port $targetPort already in use by another process. Switching runtime Redis to $nextPort."
            $targetPort = $nextPort
            Set-EnvValue -Path $BackendEnvPath -Key "REDIS_PORT" -Value "$targetPort"
        } else {
            Write-Ok "Redis already reachable on 127.0.0.1:$targetPort"
            return [pscustomobject]@{
                Port = $targetPort
                Password = $Password
            }
        }
    }

    $runtimeDataDirForRedis = $runtimeDataDir -replace "\\", "/"
    $runtimeLogForRedis = $runtimeLog -replace "\\", "/"

    $redisLines = @(
        "bind 127.0.0.1",
        "port $targetPort",
        "protected-mode yes",
        "daemonize no",
        "appendonly yes",
        "appendfilename `"appendonly.aof`"",
        "dir `"$runtimeDataDirForRedis`"",
        "logfile `"$runtimeLogForRedis`""
    )
    $redisLines += "requirepass $Password"
    Set-Content -Path $runtimeConf -Value $redisLines -Encoding ASCII

    # Clean up any stale Redis processes before starting a new one
    if (Test-Path $runtimePidFile) {
        $oldPidText = (Get-Content -Path $runtimePidFile -Raw -ErrorAction SilentlyContinue)
        $oldPidValue = 0
        if ([int]::TryParse(($oldPidText | Out-String).Trim(), [ref]$oldPidValue) -and $oldPidValue -gt 0) {
            $oldProc = Get-Process -Id $oldPidValue -ErrorAction SilentlyContinue
            if ($oldProc) {
                Write-Step "Terminating existing Redis process (PID: $oldPidValue)..."
                Stop-Process -Id $oldPidValue -Force -ErrorAction SilentlyContinue
                Start-Sleep -Milliseconds 300
            }
        }
        Remove-Item -Path $runtimePidFile -Force -ErrorAction SilentlyContinue
    }

    Write-Step "Starting portable Redis runtime..."
    $redisProc = Start-Process -FilePath $redisServer `
        -ArgumentList "redis-local.conf" `
        -WorkingDirectory $runtimeRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $runtimeStdOut `
        -RedirectStandardError $runtimeStdErr `
        -PassThru
    Set-Content -Path $runtimePidFile -Value "$($redisProc.Id)" -Encoding ASCII

    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        if (Test-TcpPort -Address "127.0.0.1" -Port $targetPort -TimeoutMs 500) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $ready) {
        throw "Redis failed to start on port $targetPort"
    }
    Write-Ok "Portable Redis started on 127.0.0.1:$targetPort"
    return [pscustomobject]@{
        Port = $targetPort
        Password = $Password
    }
}

function Restore-LatestBackupIfEmpty(
    [bool]$RestoreIfEmpty,
    [string]$PgRestorePath,
    [string]$BackupDir,
    [string]$DbHost,
    [int]$Port,
    [string]$DbName,
    [string]$DbUser,
    [string]$DbPassword
) {
    if (-not $RestoreIfEmpty) { return }
    if (-not (Test-Path $BackupDir)) { return }
    if (-not (Test-Path $PgRestorePath)) { return }

    $countResult = Invoke-PsqlQuery -PsqlPath (Join-Path (Split-Path $PgRestorePath -Parent) "psql.exe") `
        -DbHost $DbHost -Port $Port -User $DbUser -Database $DbName -Password $DbPassword `
        -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';"
    if (-not $countResult.Success) {
        Write-WarnLine "Could not check DB table count for restore: $($countResult.Output)"
        return
    }

    $tableCount = 0
    [void][int]::TryParse($countResult.Output, [ref]$tableCount)
    if ($tableCount -gt 0) {
        return
    }

    $latestBackup = Get-ChildItem -Path $BackupDir -Filter "*.dump" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $latestBackup) {
        return
    }

    Write-Step "Empty DB detected. Restoring from backup: $($latestBackup.Name)"
    $oldPwd = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $DbPassword
        & $PgRestorePath -h $DbHost -p $Port -U $DbUser -d $DbName --clean --if-exists --no-owner --no-privileges $latestBackup.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "pg_restore failed with exit code $LASTEXITCODE"
        }
    } finally {
        if ($null -eq $oldPwd) {
            Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
        } else {
            $env:PGPASSWORD = $oldPwd
        }
    }
    Write-Ok "DB restore completed from latest backup"
}

$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptDir ".."))
$backendEnvPath = Join-Path $projectRoot "backend\.env"

if (-not $NoBanner) {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor DarkCyan
    Write-Host "=== Backend Bootstrap (PostgreSQL + Redis + Backup)       " -ForegroundColor DarkCyan
    Write-Host "============================================================" -ForegroundColor DarkCyan
}

Write-Step "Project root: $projectRoot"

if (-not (Test-Path $backendEnvPath)) {
    $rootExample = Join-Path $projectRoot ".env.example"
    Write-Step "backend/.env not found. Creating from .env.example"
    if (Test-Path $rootExample) {
        Copy-Item -Path $rootExample -Destination $backendEnvPath -Force
    } else {
        New-Item -Path $backendEnvPath -ItemType File -Force | Out-Null
    }
}

$envMap = Get-EnvMap -Path $backendEnvPath
$defaultMap = [ordered]@{
    DB_URL = "jdbc:postgresql://127.0.0.1:5432/dashboard_db"
    DB_DRIVER = "org.postgresql.Driver"
    DB_USERNAME = "dashboard"
    DB_PASSWORD = "dashboard"
    POSTGRES_DB = "dashboard_db"
    POSTGRES_USER = "dashboard"
    POSTGRES_PASSWORD = "dashboard"
    POSTGRES_SUPERUSER = "postgres"
    POSTGRES_RUNTIME_PORT = "5433"
    POSTGRES_RUNTIME_ONLY = "true"
    REDIS_HOST = "127.0.0.1"
    REDIS_PORT = "6379"
    REDIS_PASSWORD = ""
    REDIS_RUNTIME_ONLY = "true"
    REDIS_RUNTIME_PORT = "6379"
    CACHE_PROVIDER = "redis"
    SPRING_CACHE_TYPE = "redis"
    API_SERVER_PROTOCOL = "http"
    API_SERVER_HOST = "127.0.0.1"
    API_SERVER_PORT = "8888"
    API_BASE_URL = "http://127.0.0.1:8888/api/v1"
    WEBSOCKET_PROTOCOL = "ws"
    WEBSOCKET_HOST = "127.0.0.1"
    WEBSOCKET_PORT = "8888"
    WEBSOCKET_STOMP_URL = "ws://127.0.0.1:8888/ws/websocket"
    DEFAULT_LOGIN_HOST = "127.0.0.1"
    DEFAULT_LOGIN_PORT = "8888"
    JPA_DDL_AUTO = "validate"
    FLYWAY_ENABLED = "true"
    FLYWAY_LOCATIONS = "classpath:db/pgmigration"
    DB_BACKUP_ENABLED = "true"
    DB_BACKUP_CRON = "0 0 1 * * *"
    DB_BACKUP_ZONE = "Asia/Seoul"
    DB_BACKUP_DIR = "./backups/postgres"
    DB_BACKUP_KEEP_DAYS = "14"
    DB_BACKUP_TIMEOUT_MINUTES = "30"
    DB_BACKUP_LOCK_KEY = "94503177"
    PG_DUMP_PATH = "pg_dump"
    DB_BACKUP_TIME = "01:00"
}

foreach ($key in $defaultMap.Keys) {
    $current = if ($envMap.Contains($key)) { [string]$envMap[$key] } else { "" }
    if (-not (Has-Text $current)) {
        Set-EnvValue -Path $backendEnvPath -Key $key -Value $defaultMap[$key]
    }
}

$envMap = Get-EnvMap -Path $backendEnvPath
if (-not (Has-Text $envMap["POSTGRES_SUPER_PASSWORD"])) {
    $superPwd = if (Has-Text $envMap["POSTGRES_PASSWORD"]) { $envMap["POSTGRES_PASSWORD"] } else { $envMap["DB_PASSWORD"] }
    Set-EnvValue -Path $backendEnvPath -Key "POSTGRES_SUPER_PASSWORD" -Value $superPwd
    $envMap = Get-EnvMap -Path $backendEnvPath
}

$dbInfo = Parse-PostgresJdbc -JdbcUrl $envMap["DB_URL"]
$dbUser = $envMap["DB_USERNAME"]
$dbPassword = $envMap["DB_PASSWORD"]
$dbName = $dbInfo.Database
$superUser = if (Has-Text $envMap["POSTGRES_SUPERUSER"]) { $envMap["POSTGRES_SUPERUSER"] } else { "postgres" }
$superPassword = $envMap["POSTGRES_SUPER_PASSWORD"]
$runtimePort = 5433
if (Has-Text $envMap["POSTGRES_RUNTIME_PORT"]) {
    [void][int]::TryParse($envMap["POSTGRES_RUNTIME_PORT"], [ref]$runtimePort)
}
if ($runtimePort -le 0) {
    $runtimePort = 5433
}
$postgresRuntimeOnly = Parse-BoolOrDefault -Value $envMap["POSTGRES_RUNTIME_ONLY"] -DefaultValue $true

Write-Step "Checking PostgreSQL binaries..."
$runtimePgRoot = Join-Path $projectRoot ".runtime\postgres"
$runtimePgBinDir = Join-Path $runtimePgRoot "bin"
$runtimePgLibDir = Join-Path $runtimePgRoot "lib"
$portablePsqlPath = Join-Path $runtimePgBinDir "psql.exe"
$runtimePgRootNormalized = [System.IO.Path]::GetFullPath($runtimePgRoot).TrimEnd('\\')

$portableLibEntry = $null
if (Test-Path $runtimePgLibDir) {
    $portableLibEntry = Get-ChildItem -Path $runtimePgLibDir -ErrorAction SilentlyContinue | Select-Object -First 1
}

$portableRuntimeUsable = (Test-Path $portablePsqlPath) -and ($null -ne $portableLibEntry)
if ((Test-Path $portablePsqlPath) -and -not $portableRuntimeUsable) {
    Write-WarnLine "Detected incomplete portable PostgreSQL runtime (missing lib). Falling back to system PostgreSQL binaries."
}

$psqlPath = $null
if ($portableRuntimeUsable) {
    $psqlPath = $portablePsqlPath
    Write-Step "Using portable PostgreSQL runtime: $runtimePgRoot"
} else {
    $psqlPath = Find-PostgresBinary -BinaryName "psql" -ExcludePathPrefix $runtimePgRoot
}
if (-not (Has-Text $psqlPath) -and $AutoInstall -and (Find-CommandOrNull "choco")) {
    Write-Step "PostgreSQL not found. Trying Chocolatey install..."
    try {
        & choco install postgresql --yes --no-progress | Out-Null
    } catch {
        Write-WarnLine "Chocolatey PostgreSQL install failed: $($_.Exception.Message)"
    }
    $psqlPath = Find-PostgresBinary -BinaryName "psql" -ExcludePathPrefix $runtimePgRoot
}
if (-not (Has-Text $psqlPath)) {
    throw "psql executable not found. Install PostgreSQL and retry."
}

# Cache system PostgreSQL runtime (bin/lib/share) for next runs.
if (-not $portableRuntimeUsable) {
    try {
        $sourceBinDir = Split-Path $psqlPath -Parent
        $sourcePgRoot = Split-Path $sourceBinDir -Parent

        if (Test-Path $sourcePgRoot) {
            $sourcePgRootNormalized = [System.IO.Path]::GetFullPath($sourcePgRoot).TrimEnd('\\')
            if ($sourcePgRootNormalized.Equals($runtimePgRootNormalized, [System.StringComparison]::OrdinalIgnoreCase)) {
                Write-WarnLine "PostgreSQL path resolved to runtime cache itself; skipping portable cache refresh."
            } else {
                foreach ($dirName in @("bin", "lib", "share")) {
                    $src = Join-Path $sourcePgRoot $dirName
                    if (-not (Test-Path $src)) { continue }
                    $dst = Join-Path $runtimePgRoot $dirName
                    New-Item -Path $dst -ItemType Directory -Force | Out-Null
                    Copy-Item -Path (Join-Path $src "*") -Destination $dst -Recurse -Force -ErrorAction Stop
                }
            }
        }

        $portableLibEntry = $null
        if (Test-Path $runtimePgLibDir) {
            $portableLibEntry = Get-ChildItem -Path $runtimePgLibDir -ErrorAction SilentlyContinue | Select-Object -First 1
        }

        if ((Test-Path $portablePsqlPath) -and ($null -ne $portableLibEntry)) {
            Write-Ok "PostgreSQL runtime cached to portable path: $runtimePgRoot"
            $psqlPath = $portablePsqlPath
            $portableRuntimeUsable = $true
        } else {
            Write-WarnLine "Portable PostgreSQL cache incomplete; continuing with system PostgreSQL binaries."
        }
    } catch {
        Write-WarnLine "Could not cache PostgreSQL runtime to portable path: $($_.Exception.Message)"
    }
}

$pgDumpPath = Join-Path (Split-Path $psqlPath -Parent) "pg_dump.exe"
$pgRestorePath = Join-Path (Split-Path $psqlPath -Parent) "pg_restore.exe"

$postgresServices = @()
if (-not $postgresRuntimeOnly) {
    $postgresServices = Get-ServiceNamesLike -Patterns @("postgresql*", "*PostgreSQL*")
    if ($postgresServices.Count -gt 0) {
        Start-ServicesIfNeeded -Names $postgresServices
    }
} else {
    Write-Step "POSTGRES_RUNTIME_ONLY is enabled. Skipping external PostgreSQL service probing."
}

$candidateHost = if ($postgresRuntimeOnly) { "127.0.0.1" } else { $dbInfo.Host }
$candidatePorts = if ($postgresRuntimeOnly) { @($runtimePort) } else { @($dbInfo.Port, $runtimePort, 5432) | Select-Object -Unique }
$chosenPort = $null
$chosenHost = $candidateHost
$reachableCandidatePorts = 0
$candidateProvisionFailed = $false
foreach ($port in $candidatePorts) {
    if (-not (Test-TcpPort -Address $candidateHost -Port $port)) {
        continue
    }
    $reachableCandidatePorts++
    if (Ensure-RoleAndDatabase -PsqlPath $psqlPath -DbHost $candidateHost -Port $port -DbUser $dbUser -DbPassword $dbPassword -DbName $dbName -SuperUser $superUser -SuperPassword $superPassword) {
        $chosenPort = $port
        $chosenHost = $candidateHost
        break
    }
    $candidateProvisionFailed = $true
}

if ($null -eq $chosenPort) {
    $allowInstallRetry = $true
    if ($reachableCandidatePorts -gt 0 -and $candidateProvisionFailed) {
        $allowInstallRetry = $false
        Write-WarnLine "Reachable PostgreSQL was found but login/provision failed. Skipping Chocolatey retry and attempting portable runtime."
    }

    if ($AutoInstall -and -not $postgresRuntimeOnly -and $allowInstallRetry -and (Find-CommandOrNull "choco")) {
        Write-Step "No reachable PostgreSQL instance found. Retrying install/start..."
        try {
            & choco install postgresql --yes --no-progress | Out-Null
        } catch {
            Write-WarnLine "Chocolatey PostgreSQL install retry failed: $($_.Exception.Message)"
        }
        $postgresServices = Get-ServiceNamesLike -Patterns @("postgresql*", "*PostgreSQL*")
        if ($postgresServices.Count -gt 0) {
            Start-ServicesIfNeeded -Names $postgresServices
        }
        foreach ($port in $candidatePorts) {
            if (-not (Test-TcpPort -Address $candidateHost -Port $port)) {
                continue
            }
            if (Ensure-RoleAndDatabase -PsqlPath $psqlPath -DbHost $candidateHost -Port $port -DbUser $dbUser -DbPassword $dbPassword -DbName $dbName -SuperUser $superUser -SuperPassword $superPassword) {
                $chosenPort = $port
                $chosenHost = $candidateHost
                break
            }
        }
    }
}

$usedPortableRuntime = $false
if ($null -eq $chosenPort) {
    Write-Step "Falling back to portable PostgreSQL runtime..."
    $portablePostgres = Ensure-PortablePostgres -ProjectRoot $projectRoot -PsqlPath $psqlPath -Port $runtimePort -DbUser $dbUser -DbPassword $dbPassword -DbName $dbName -SuperUser $superUser -SuperPassword $superPassword
    if ($portablePostgres.Success) {
        $chosenPort = [int]$portablePostgres.Port
        $chosenHost = "127.0.0.1"
        $usedPortableRuntime = $true
    }
}

if ($null -eq $chosenPort) {
    throw "PostgreSQL connection/provision failed. Check DB credentials in backend/.env (and POSTGRES_SUPER_PASSWORD), or verify local PostgreSQL installation."
}

if ($usedPortableRuntime -and $chosenPort -ne $runtimePort) {
    Set-EnvValue -Path $backendEnvPath -Key "POSTGRES_RUNTIME_PORT" -Value "$chosenPort"
    $runtimePort = $chosenPort
    Write-Step "POSTGRES_RUNTIME_PORT updated to active portable runtime port: $runtimePort"
}

if ($chosenPort -ne $dbInfo.Port -or $chosenHost -ne $dbInfo.Host) {
    $newJdbcUrl = "jdbc:postgresql://${chosenHost}:${chosenPort}/${dbName}"
    Set-EnvValue -Path $backendEnvPath -Key "DB_URL" -Value $newJdbcUrl
    $dbInfo = Parse-PostgresJdbc -JdbcUrl $newJdbcUrl
    Write-Step "DB_URL updated to active PostgreSQL port: $newJdbcUrl"
}
Write-Ok "PostgreSQL ready at $($dbInfo.Host):$($dbInfo.Port)/$dbName"
Apply-PostgresTextColumnCompatibility -PsqlPath $psqlPath -DbHost $dbInfo.Host -Port $dbInfo.Port -DbUser $dbUser -DbName $dbName -DbPassword $dbPassword

$envMap = Get-EnvMap -Path $backendEnvPath
$redisRuntimeOnly = Parse-BoolOrDefault -Value $envMap["REDIS_RUNTIME_ONLY"] -DefaultValue $true
if (-not $redisRuntimeOnly) {
    Set-EnvValue -Path $backendEnvPath -Key "REDIS_RUNTIME_ONLY" -Value "true"
    Write-Step "REDIS_RUNTIME_ONLY forced to true (workspace runtime-first mode)."
    $envMap = Get-EnvMap -Path $backendEnvPath
    $redisRuntimeOnly = $true
}
$redisRuntimePort = 6379
if (Has-Text $envMap["REDIS_RUNTIME_PORT"]) {
    [void][int]::TryParse($envMap["REDIS_RUNTIME_PORT"], [ref]$redisRuntimePort)
}
if ($redisRuntimePort -le 0) {
    $redisRuntimePort = 6379
}

if ($envMap["REDIS_HOST"] -ne "127.0.0.1") {
    Set-EnvValue -Path $backendEnvPath -Key "REDIS_HOST" -Value "127.0.0.1"
    Write-Step "REDIS_HOST forced to 127.0.0.1 (backend-local only)."
}

$redisPort = $redisRuntimePort
if (-not $redisRuntimeOnly -and (Has-Text $envMap["REDIS_PORT"])) {
    [void][int]::TryParse($envMap["REDIS_PORT"], [ref]$redisPort)
}
if ($redisPort -le 0) {
    $redisPort = $redisRuntimePort
}

if ($redisRuntimeOnly -and "$($envMap["REDIS_PORT"])" -ne "$redisPort") {
    Set-EnvValue -Path $backendEnvPath -Key "REDIS_PORT" -Value "$redisPort"
}

$redisPassword = if ($envMap.Contains("REDIS_PASSWORD")) { $envMap["REDIS_PASSWORD"] } else { "" }
$redisResult = Ensure-RedisReady `
    -AutoInstall $AutoInstall `
    -ProjectRoot $projectRoot `
    -Port $redisPort `
    -Password $redisPassword `
    -RuntimeOnly $redisRuntimeOnly `
    -BackendEnvPath $backendEnvPath

if ($redisResult -and $redisResult.Port -and $redisResult.Port -ne $redisPort) {
    $redisPort = [int]$redisResult.Port
    Set-EnvValue -Path $backendEnvPath -Key "REDIS_PORT" -Value "$redisPort"
}
if ($redisResult -and (Has-Text $redisResult.Password) -and $redisResult.Password -ne $redisPassword) {
    Set-EnvValue -Path $backendEnvPath -Key "REDIS_PASSWORD" -Value "$($redisResult.Password)"
}

$backupDirRaw = if (Has-Text $envMap["DB_BACKUP_DIR"]) { $envMap["DB_BACKUP_DIR"] } else { "./backups/postgres" }
$backupDir = Resolve-PathFromProject -ProjectRoot $projectRoot -PathValue $backupDirRaw
if (-not (Test-Path $backupDir)) {
    New-Item -Path $backupDir -ItemType Directory -Force | Out-Null
}

Restore-LatestBackupIfEmpty -RestoreIfEmpty $RestoreIfEmpty -PgRestorePath $pgRestorePath -BackupDir $backupDir `
    -DbHost $dbInfo.Host -Port $dbInfo.Port -DbName $dbName -DbUser $dbUser -DbPassword $dbPassword

if ($RegisterBackupTask) {
    Register-BackupTask -ProjectRoot $projectRoot -BackupTime $envMap["DB_BACKUP_TIME"]
} else {
    Write-Step "OS task registration skipped (default). Backup schedule is handled by backend internal scheduler."
}

$todayBackupExists = Get-ChildItem -Path $backupDir -Filter "*.dump" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime.Date -eq (Get-Date).Date } |
    Select-Object -First 1
if (-not $todayBackupExists) {
    $backupScript = Join-Path $projectRoot "scripts\db-backup.ps1"
    if (Test-Path $backupScript) {
        Write-Step "No backup created today. Running one-time backup now..."
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backupScript -NoBanner | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-WarnLine "One-time backup failed. Backend internal scheduler will retry on next cron window."
        } else {
            Write-Ok "One-time backup completed"
        }
    }
}

Write-Host ""
Write-Ok "Bootstrap complete"
Write-Host "  DB_URL:      $($dbInfo.Host):$($dbInfo.Port)/$dbName"
Write-Host "  Redis:       127.0.0.1:$redisPort"
Write-Host "  Backup dir:  $backupDir"
Write-Host ""

exit 0
