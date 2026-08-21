#Requires -Version 5.1
<#
.SYNOPSIS
    PCAgent 워치독 - 에이전트를 항상 살아있게 유지 + 무중단 업데이트/롤백 관리.

.DESCRIPTION
    Windows 작업 스케줄러(Task Scheduler)에 의해 SYSTEM 계정으로 부팅 시 자동 실행됩니다.
    에이전트가 충돌하거나 종료되면 자동으로 재시작합니다.

    무중단 업데이트(hot-swap):
      에이전트가 AGENT_UPDATE 수신 → 새 agent.jar를 staging + update-pending.json 마커 기록 후 self-exit.
      워치독은 에이전트 종료를 감지하면 staged jar를 실제 위치로 교체하고 새 버전을 실행한다.
      새 버전이 마감(기본 90초) 내에 heartbeat 성공(heartbeat-ok 마커 갱신)을 보이면 커밋,
      그렇지 않으면(크래시/무응답) 직전 버전(previous jar)으로 자동 롤백한다.
      OS 재부팅은 전혀 필요 없으며, 워치독은 에이전트를 임의로 재부팅하지 않는다.

.PARAMETER InstallDir
    에이전트 설치 폴더 경로. 기본값: C:\Program Files\PCAgent

.PARAMETER LauncherPath
    실행할 런처 경로. 기본값: InstallDir\PCAgent.exe

.PARAMETER ValidateRuntimeOnly
    자체 Java 런타임을 검사하고 선택된 경로를 출력한 뒤 종료합니다. 설치 패키지 검증용입니다.

.PARAMETER ValidateJavaExe
    지정한 자체 java.exe 하나를 검사하고 결과를 출력한 뒤 종료합니다. 안전 복사 검증용입니다.
#>
param(
    [string]$InstallDir = "C:\Program Files\PCAgent",
    [string]$LauncherPath = "",
    [switch]$ValidateRuntimeOnly,
    [string]$ValidateJavaExe = ""
)

$programDataDir = "C:\ProgramData\PCAgent"
$programDataLogDir = Join-Path $programDataDir "logs"
if (!(Test-Path $programDataLogDir)) {
    New-Item -ItemType Directory -Path $programDataLogDir -Force -ErrorAction SilentlyContinue | Out-Null
}
$logFile = Join-Path $programDataLogDir "watchdog.log"
$pendingMarker = Join-Path $programDataDir "update-pending.json"
$heartbeatOk   = Join-Path $programDataDir "heartbeat-ok"

# ── 로그 유틸 ──────────────────────────────────────────────────────────────
# 이전 구현은 Add-Content 실패를 -ErrorAction SilentlyContinue + try/catch 로 완전히 삼켰다.
# 그래서 로그 파일이 (다른 워치독 인스턴스/백업/AV 등에 의해) 잠기면 워치독은 계속 돌면서도
# 로그를 한 줄도 남기지 못해, "죽은 것"과 "살아있는데 로그만 못 쓰는 것"을 구분할 수 없었다.
# → 공용 로그가 막히면 인스턴스 전용 로그, 그것도 막히면 TEMP 로 자동 폴백한다.
$script:activeLogFile = $logFile
$script:logFallbacks = @(
    $logFile,
    (Join-Path $programDataLogDir "watchdog-pid$PID.log"),
    (Join-Path $env:TEMP "pcagent-watchdog-pid$PID.log")
)

function Write-Log {
    param([string]$Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [Watchdog] $Message"
    # 수동 실행(관리자 PowerShell 등) 시 화면으로도 진단할 수 있게 한다.
    try { Write-Host $line } catch {}

    # 현재 사용 중인 대상을 먼저 시도하고, 실패하면 다음 폴백으로 승격한다.
    $targets = @($script:activeLogFile) + ($script:logFallbacks | Where-Object { $_ -ne $script:activeLogFile })
    foreach ($target in $targets) {
        if (-not $target) { continue }
        try {
            [IO.File]::AppendAllText($target, $line + [Environment]::NewLine, [Text.Encoding]::UTF8)
            if ($script:activeLogFile -ne $target) {
                $script:activeLogFile = $target
                try {
                    [IO.File]::AppendAllText($target,
                        "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [Watchdog] (알림) 이전 로그 대상이 잠겨 있어 이 파일로 전환했습니다.$([Environment]::NewLine)",
                        [Text.Encoding]::UTF8)
                } catch {}
            }
            return
        } catch { continue }
    }
    # 모든 대상 실패 — 화면 출력만으로 남긴다(여기서 예외를 던지면 워치독 루프가 죽는다).
}

function Trim-Log {
    # 이전 구현은 Get-Content 로 전체를 읽고 Set-Content 로 전체를 다시 써서, 트림 도중 파일이
    # 배타적으로 잠기고 다른 인스턴스의 로그 기록이 실패하는 원인이 됐다.
    # 지금은 이 인스턴스가 실제로 쓰는 파일만, 배타 접근을 얻을 수 있을 때만 트림한다.
    $target = $script:activeLogFile
    if (-not $target -or -not (Test-Path $target)) { return }
    try {
        if ((Get-Item $target -ErrorAction Stop).Length -lt 1MB) { return }
    } catch { return }
    try {
        $stream = [IO.File]::Open($target, 'Open', 'ReadWrite', 'None')
        try {
            $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
            $all = $reader.ReadToEnd()
            $lines = $all -split "`r?`n" | Where-Object { $_ -ne '' }
            if ($lines.Count -gt 1000) {
                $kept = ($lines | Select-Object -Last 500) -join [Environment]::NewLine
                $stream.SetLength(0)
                $writer = New-Object IO.StreamWriter($stream, [Text.Encoding]::UTF8)
                $writer.Write($kept + [Environment]::NewLine)
                $writer.Flush()
            }
        } finally { $stream.Close() }
    } catch {
        # 잠겨 있으면 이번 주기는 건너뛴다 — 트림 실패는 워치독 동작에 영향이 없어야 한다.
    }
}

# ── PCAgent 자체 Java 런타임 검사 ──────────────────────────────────────────
function Invoke-JavaProbe {
    param([string]$JavaExe, [string]$Arguments)
    $process = $null
    try {
        $startInfo = New-Object Diagnostics.ProcessStartInfo
        $startInfo.FileName = $JavaExe
        $startInfo.Arguments = $Arguments
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = New-Object Diagnostics.Process
        $process.StartInfo = $startInfo
        if (-not $process.Start()) {
            return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = 'Java 검사 프로세스 시작 실패' }
        }
        if (-not $process.WaitForExit(15000)) {
            try { $process.Kill() } catch {}
            try { $process.WaitForExit(5000) | Out-Null } catch {}
            return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = 'Java 검사 15초 시간 초과' }
        }

        $output = ($process.StandardOutput.ReadToEnd() + "`n" + $process.StandardError.ReadToEnd()).Trim()
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $output; Error = '' }
    } catch {
        return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = $_.Exception.Message }
    } finally {
        if ($process) { $process.Dispose() }
    }
}

function Test-JavaRuntime {
    param([string]$JavaExe)

    if (-not (Test-Path -LiteralPath $JavaExe -PathType Leaf)) {
        return [pscustomobject]@{ IsValid = $false; Reason = 'java.exe 없음' }
    }

    $runtimeRoot = Split-Path (Split-Path $JavaExe -Parent) -Parent
    $modulesFile = Join-Path $runtimeRoot 'lib\modules'
    if (-not (Test-Path -LiteralPath $modulesFile -PathType Leaf)) {
        return [pscustomobject]@{ IsValid = $false; Reason = 'lib\modules 없음' }
    }
    try {
        if ((Get-Item -LiteralPath $modulesFile -ErrorAction Stop).Length -le 0) {
            return [pscustomobject]@{ IsValid = $false; Reason = 'lib\modules가 비어 있음' }
        }

        $versionProbe = Invoke-JavaProbe -JavaExe $JavaExe -Arguments '-version'
        if ($versionProbe.ExitCode -ne 0) {
            $summary = if ($versionProbe.Error) { $versionProbe.Error } else { $versionProbe.Output }
            $summary = ($summary -replace '\s+', ' ').Trim()
            if ($summary.Length -gt 240) { $summary = $summary.Substring(0, 240) }
            if (-not $summary) { $summary = "java -version 종료 코드 $($versionProbe.ExitCode)" }
            return [pscustomobject]@{ IsValid = $false; Reason = $summary }
        }

        $versionMatch = [regex]::Match($versionProbe.Output, '(?im)(?:openjdk|java)\s+version\s+"(?<major>\d+)')
        if (-not $versionMatch.Success) {
            return [pscustomobject]@{ IsValid = $false; Reason = 'Java 버전을 확인할 수 없음' }
        }
        $majorVersion = [int]$versionMatch.Groups['major'].Value
        if ($majorVersion -lt 25) {
            return [pscustomobject]@{ IsValid = $false; Reason = "Java 25 미만 감지: $majorVersion" }
        }

        $moduleProbe = Invoke-JavaProbe -JavaExe $JavaExe -Arguments '--list-modules'
        if ($moduleProbe.ExitCode -ne 0) {
            $summary = if ($moduleProbe.Error) { $moduleProbe.Error } else { $moduleProbe.Output }
            $summary = ($summary -replace '\s+', ' ').Trim()
            if ($summary.Length -gt 240) { $summary = $summary.Substring(0, 240) }
            return [pscustomobject]@{ IsValid = $false; Reason = "Java 모듈 검사 실패: $summary" }
        }

        $requiredModules = @(
            'java.base', 'java.sql', 'java.management', 'java.naming', 'jdk.unsupported',
            'java.security.jgss', 'java.xml', 'java.desktop', 'jdk.httpserver', 'jdk.crypto.ec'
        )
        foreach ($requiredModule in $requiredModules) {
            if ($moduleProbe.Output -notmatch "(?m)^$([regex]::Escape($requiredModule))@") {
                return [pscustomobject]@{ IsValid = $false; Reason = "필수 Java 모듈 없음: $requiredModule" }
            }
        }

        return [pscustomobject]@{ IsValid = $true; Reason = "Java $majorVersion 정상" }
    } catch {
        return [pscustomobject]@{ IsValid = $false; Reason = $_.Exception.Message }
    }
}

function Find-BundledJava {
    param([string]$InstallDir)

    # 수동 설치본의 검증된 jre를 우선 사용한다. 기존 MSI runtime은 정상일 때만 차선으로 사용한다.
    $candidates = @(
        (Join-Path $InstallDir 'jre\bin\java.exe'),
        (Join-Path $InstallDir 'runtime\bin\java.exe')
    )
    foreach ($candidate in $candidates) {
        $result = Test-JavaRuntime -JavaExe $candidate
        if ($result.IsValid) {
            Write-Log "자체 Java 런타임 검증 성공: $candidate ($($result.Reason))"
            return $candidate
        }
        if (Test-Path -LiteralPath (Split-Path (Split-Path $candidate -Parent) -Parent)) {
            Write-Log "자체 Java 런타임 건너뜀: $candidate ($($result.Reason))"
        }
    }
    return $null
}

function Resolve-Jar {
    param([string]$InstallDir)
    $candidates = @(
        (Join-Path $InstallDir "app\agent.jar"),
        (Join-Path $InstallDir "agent.jar")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Resolve-ConfigDir {
    param([string]$ResolvedJar)
    $jarDir = Split-Path $ResolvedJar -Parent
    $programDataConfig = Join-Path $programDataDir "config.json"
    $jarConfig = Join-Path $jarDir "config.json"

    if ((-not (Test-Path $programDataConfig)) -and (Test-Path $jarConfig)) {
        try {
            Copy-Item -LiteralPath $jarConfig -Destination $programDataConfig -Force -ErrorAction Stop
            Write-Log "ProgramData config.json 초기화: $jarConfig -> $programDataConfig"
        } catch {
            Write-Log "ProgramData config.json 초기화 실패: $_"
        }
    }

    if (Test-Path $programDataConfig) {
        return $programDataDir
    }
    if (Test-Path $jarConfig) {
        return $jarDir
    }
    return $programDataDir
}

function Quote-ProcessArgument {
    param([string]$Value)
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Trim-AgentOutputLogs {
    try {
        if (-not (Test-Path $programDataLogDir)) { return }
        Get-ChildItem -Path $programDataLogDir -Filter "agent-std*.log" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -Skip 20 |
            Remove-Item -Force -ErrorAction SilentlyContinue
    } catch {}
}

# ── 무중단 업데이트 마커 유틸 ────────────────────────────────────────────────
function Get-Pending {
    if (-not (Test-Path $pendingMarker)) { return $null }
    try {
        $json = Get-Content $pendingMarker -Raw -ErrorAction Stop
        if (-not $json -or $json.Trim() -eq "") { return $null }
        return ($json | ConvertFrom-Json)
    } catch {
        Write-Log "update-pending.json 파싱 실패: $_"
        return $null
    }
}

function Save-Pending {
    param($obj)
    try {
        ($obj | ConvertTo-Json -Compress) | Set-Content -LiteralPath $pendingMarker -Encoding UTF8 -ErrorAction Stop
    } catch {
        Write-Log "update-pending.json 저장 실패: $_"
    }
}

function Remove-Pending {
    try { Remove-Item -LiteralPath $pendingMarker -Force -ErrorAction SilentlyContinue } catch {}
}

function Now-UnixMs {
    return [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
}

function Get-HeartbeatAgeMs {
    if (-not (Test-Path $heartbeatOk)) { return $null }
    try {
        $content = Get-Content $heartbeatOk -Raw -ErrorAction Stop
        if (-not $content) { return $null }
        $heartbeatMs = [long]0
        if ([long]::TryParse($content.Trim(), [ref]$heartbeatMs)) {
            return (Now-UnixMs) - $heartbeatMs
        }
    } catch {}
    return $null
}

# 에이전트 종료 후, staged jar를 실제 위치로 교체한다 (applied=true 표시). 성공 시 $true.
function Apply-Staged {
    param($pending)
    try {
        if (-not (Test-Path $pending.stagedJar)) {
            Write-Log "경고: staged jar 없음 ($($pending.stagedJar)) — 업데이트 취소, 기존 버전 유지"
            Remove-Pending
            return $false
        }
        Copy-Item -LiteralPath $pending.stagedJar -Destination $pending.targetJar -Force -ErrorAction Stop
        Write-Log "새 jar 적용: -> $($pending.targetJar) (v$($pending.targetVersion))"
        $pending.applied = $true
        $pending.verifyDeadlineEpochMs = (Now-UnixMs) + 90000
        Save-Pending $pending
        return $true
    } catch {
        Write-Log "jar 교체 실패: $_ — 업데이트 취소, 기존 버전 유지"
        Remove-Pending
        return $false
    }
}

# 새 버전 정상 동작(heartbeat 성공) 확정 시 staging/previous/마커 정리.
function Commit-Update {
    param($pending)
    Write-Log "새 버전 커밋 (v$($pending.targetVersion)). 업데이트 성공."
    try { Remove-Item -LiteralPath $pending.previousJar -Force -ErrorAction SilentlyContinue } catch {}
    try { Remove-Item -LiteralPath $pending.stagedJar   -Force -ErrorAction SilentlyContinue } catch {}
    Remove-Pending
}

# 새 버전 실패 시 직전 버전(previous jar)으로 되돌린다.
function Rollback-Update {
    param($pending)
    try {
        if (Test-Path $pending.previousJar) {
            Copy-Item -LiteralPath $pending.previousJar -Destination $pending.targetJar -Force -ErrorAction Stop
            Write-Log "롤백 완료: 직전 버전 jar 복원 -> $($pending.targetJar)"
        } else {
            Write-Log "경고: previous jar 없음 ($($pending.previousJar)) — 롤백 불가, 현재 jar 유지"
        }
    } catch {
        Write-Log "롤백 중 오류: $_"
    }
    try { Remove-Item -LiteralPath $pending.stagedJar -Force -ErrorAction SilentlyContinue } catch {}
    Remove-Pending
}

# 에이전트(agent.jar)를 java로 직접 실행하고 Process 객체를 반환한다 (실패 시 $null).
# PCAgent.exe(런처)를 거치지 않는다 — 런처는 자식 java가 죽어도 살아있어 워치독이
# 워커 종료를 감지하지 못하고 좀비 런처가 누적되기 때문. java를 직접 감시한다.
function Start-Agent {
    param([string]$InstallDir, [string]$LauncherPath)
    $resolvedJar = Resolve-Jar -InstallDir $InstallDir
    if (-not $resolvedJar) {
        Write-Log "오류: agent.jar를 찾을 수 없습니다."
        return $null
    }
    # PCAgent가 함께 설치한 Java만 사용한다. 시스템 JAVA_HOME/PATH는 의도적으로 무시한다.
    $javaExe = Find-BundledJava -InstallDir $InstallDir
    if (-not $javaExe) {
        Write-Log "오류: 정상 동작하는 PCAgent 자체 Java 25 런타임을 찾을 수 없습니다. 재설치가 필요합니다."
        return $null
    }
    # 설치형 에이전트는 ProgramData의 config.json / heartbeat-ok를 기준으로 동작한다.
    $configDir = Resolve-ConfigDir -ResolvedJar $resolvedJar
    $runStamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
    Trim-AgentOutputLogs
    $javaArgs = "-Xmx128m -Dpcagent.config.dir=$(Quote-ProcessArgument $configDir) -jar $(Quote-ProcessArgument $resolvedJar)"

    # stdout/stderr 리다이렉트는 "있으면 좋은" 진단 수단일 뿐이다. 그런데 Start-Process 는 리다이렉트
    # 대상 파일을 만들지 못하면 예외를 던지므로, 로그 폴더가 쓰기 불가 상태(BSOD 후 파일시스템/권한 손상,
    # AV 필터 이상 등)이면 에이전트가 아예 기동되지 않는다. 실제로 "워치독을 수동 실행해도 에이전트가
    # 안 뜨는데 PCAgent.exe 를 직접 누르면 뜨는"(= 리다이렉트를 쓰지 않는 경로만 동작) 장애가 있었다.
    # → 리다이렉트 가능 여부를 먼저 확인하고, 불가능하면 리다이렉트 없이라도 반드시 띄운다.
    $stdoutFile = Join-Path $programDataLogDir "agent-stdout-$runStamp.log"
    $stderrFile = Join-Path $programDataLogDir "agent-stderr-$runStamp.log"
    $canRedirect = $false
    try {
        if (-not (Test-Path $programDataLogDir)) {
            New-Item -ItemType Directory -Path $programDataLogDir -Force -ErrorAction Stop | Out-Null
        }
        # 실제로 만들어 봐야 권한/필터 문제를 확인할 수 있다.
        [IO.File]::WriteAllText($stdoutFile, '', [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText($stderrFile, '', [Text.Encoding]::UTF8)
        $canRedirect = $true
    } catch {
        Write-Log "경고: 에이전트 출력 로그를 만들 수 없습니다 ($($_.Exception.Message)). 리다이렉트 없이 기동합니다."
    }

    if ($canRedirect) {
        Write-Log "agent.jar 직접 실행... (java=$javaExe, config=$configDir, stdout=$stdoutFile, stderr=$stderrFile)"
        try {
            return Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $configDir `
                -WindowStyle Hidden -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile `
                -PassThru -ErrorAction Stop
        } catch {
            Write-Log "리다이렉트 기동 실패 ($($_.Exception.Message)) → 리다이렉트 없이 재시도합니다."
        }
    }

    Write-Log "agent.jar 직접 실행(출력 리다이렉트 없음)... (java=$javaExe, config=$configDir)"
    return Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $configDir `
        -WindowStyle Hidden -PassThru -ErrorAction Stop
}

# 새 버전 검증: 마감까지 heartbeat-ok 갱신을 감시. 성공 커밋 시 $true, 실패(롤백 필요) 시 $false.
function Verify-NewVersion {
    param($proc, $pending)
    $createdMs = [long]$pending.createdEpochMs
    Write-Log "새 버전 검증 시작 (마감까지 heartbeat 성공 대기)..."
    while ((Now-UnixMs) -lt [long]$pending.verifyDeadlineEpochMs) {
        if ($proc.HasExited) {
            Write-Log "검증 중 새 버전 프로세스 종료됨 (ExitCode=$($proc.ExitCode)) → 롤백"
            return $false
        }
        if (Test-Path $heartbeatOk) {
            $c = Get-Content $heartbeatOk -Raw -ErrorAction SilentlyContinue
            if ($c) {
                $okMs = [long]0
                if ([long]::TryParse($c.Trim(), [ref]$okMs)) {
                    if ($okMs -gt $createdMs) {
                        Commit-Update $pending
                        return $true
                    }
                }
            }
        }
        Start-Sleep -Seconds 3
    }
    Write-Log "검증 마감 초과 (heartbeat 성공 없음) → 롤백"
    return $false
}

# ── 자체 Java 런타임 단독 검증 ───────────────────────────────────────────
if ($ValidateJavaExe) {
    $runtimeResult = Test-JavaRuntime -JavaExe $ValidateJavaExe
    if ($runtimeResult.IsValid) {
        Write-Host "[OK] PCAgent bundled Java verified: $ValidateJavaExe ($($runtimeResult.Reason))"
        exit 0
    }
    Write-Host "[ERROR] PCAgent bundled Java validation failed: $ValidateJavaExe ($($runtimeResult.Reason))"
    exit 1
}

if ($ValidateRuntimeOnly) {
    $validatedJava = Find-BundledJava -InstallDir $InstallDir
    if ($validatedJava) {
        Write-Host "[OK] PCAgent bundled Java verified: $validatedJava"
        exit 0
    }
    Write-Host "[ERROR] No healthy PCAgent bundled Java 25 runtime found in: $InstallDir"
    exit 1
}

# ── 메인 워치독 루프 ─────────────────────────────────────────────────────
Write-Log "================================================================"
Write-Log "PCAgent 워치독 시작. InstallDir=$InstallDir LauncherPath=$LauncherPath"
Write-Log "================================================================"

# heartbeat-ok 경로를 에이전트 실제 기록 위치(config.dir)로 맞춘다.
# (에이전트는 configPath.getParent()\heartbeat-ok 에 기록한다)
$jarForHb = Resolve-Jar -InstallDir $InstallDir
if ($jarForHb) {
    $heartbeatOk = Join-Path (Resolve-ConfigDir -ResolvedJar $jarForHb) "heartbeat-ok"
    Write-Log "heartbeat-ok 감시 경로: $heartbeatOk"
}

$consecutiveFails = 0

while ($true) {
    Trim-Log

    try {
        # 1) 보류 중인 업데이트가 있고 아직 교체 전이면(에이전트 종료 상태) jar 교체
        $pending = Get-Pending
        if ($pending -and -not $pending.applied) {
            Apply-Staged $pending | Out-Null
            $pending = Get-Pending  # applied/deadline 갱신 반영
        }

        # If another healthy agent instance is already alive, do not keep spawning
        # short-lived duplicates just to hit the single-instance lock.
        if (-not $pending) {
            $heartbeatAgeMs = Get-HeartbeatAgeMs
            if ($null -ne $heartbeatAgeMs -and $heartbeatAgeMs -lt 30000) {
                Write-Log "기존 에이전트 heartbeat 확인 (${heartbeatAgeMs}ms 전) → 30초 후 재확인..."
                $consecutiveFails = 0
                Start-Sleep -Seconds 30
                continue
            }
        }

        # 2) 에이전트 실행
        $proc = Start-Agent -InstallDir $InstallDir -LauncherPath $LauncherPath
        if (-not $proc) {
            $consecutiveFails++
            $delay = [Math]::Min(5 * [Math]::Pow(2, ($consecutiveFails - 1)), 60)
            Write-Log "$consecutiveFails 번 연속 실행 실패. ${delay}초 후 재시도..."
            Start-Sleep -Seconds $delay
            continue
        }
        Write-Log "에이전트 시작됨 (PID=$($proc.Id))."
        $consecutiveFails = 0

        # 3) 방금 업데이트를 적용했다면 새 버전 검증 (실패 시 롤백)
        if ($pending -and $pending.applied) {
            $committed = Verify-NewVersion -proc $proc -pending $pending
            if (-not $committed) {
                if (-not $proc.HasExited) {
                    try { $proc.Kill() } catch {}
                    try { $proc.WaitForExit(10000) | Out-Null } catch {}
                }
                Rollback-Update $pending
                # 다음 루프에서 복원된 직전 버전으로 재시작
                Start-Sleep -Seconds 3
                continue
            }
            # 커밋됨 → 정상 운영으로 이어짐
        }

        # 4) 정상 운영: 에이전트 종료까지 대기 후 재시작
        $startTime = Get-Date
        $proc.WaitForExit()
        $elapsed = (Get-Date) - $startTime
        Write-Log "에이전트 종료됨 (ExitCode=$($proc.ExitCode), runtime=$([int]$elapsed.TotalSeconds)s). 재시작 준비..."

        # fast-exit 감지: 5초 미만 종료 + 최근 heartbeat → 에이전트 이미 실행 중 (중복 기동 방지)
        if ($elapsed.TotalSeconds -lt 5) {
            if (Test-Path $heartbeatOk) {
                $hbContent = Get-Content $heartbeatOk -Raw -ErrorAction SilentlyContinue
                $hbMs = [long]0
                if ($hbContent -and [long]::TryParse($hbContent.Trim(), [ref]$hbMs)) {
                    $ageMs = (Now-UnixMs) - $hbMs
                    if ($ageMs -lt 30000) {
                        Write-Log "에이전트 이미 실행 중 (heartbeat ${ageMs}ms 전) → 중복 기동 방지. 30초 후 재확인..."
                        $consecutiveFails = 0
                        Start-Sleep -Seconds 30
                        continue
                    }
                }
            }
        }

    } catch {
        Write-Log "워치독 루프 오류: $_"
        $consecutiveFails++
    }

    if ($consecutiveFails -gt 0) {
        $delay = [Math]::Min(5 * [Math]::Pow(2, ($consecutiveFails - 1)), 60)
        Write-Log "$consecutiveFails 번 연속 실패. ${delay}초 후 재시작..."
        Start-Sleep -Seconds $delay
    } else {
        Start-Sleep -Seconds 5
    }
}
