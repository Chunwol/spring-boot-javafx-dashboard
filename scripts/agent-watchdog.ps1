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
#>
param(
    [string]$InstallDir = "C:\Program Files\PCAgent",
    [string]$LauncherPath = ""
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
function Write-Log {
    param([string]$Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [Watchdog] $Message"
    try { Add-Content -Path $logFile -Value $line -ErrorAction SilentlyContinue } catch {}
}

function Trim-Log {
    try {
        if (Test-Path $logFile) {
            $lines = Get-Content $logFile -ErrorAction SilentlyContinue
            if ($lines -and $lines.Count -gt 1000) {
                $lines[-500..-1] | Set-Content $logFile -ErrorAction SilentlyContinue
            }
        }
    } catch {}
}

# ── java.exe 탐색 ─────────────────────────────────────────────────────────
function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        return "$env:JAVA_HOME\bin\java.exe"
    }
    $javaCmd = Get-Command "java" -ErrorAction SilentlyContinue
    if ($javaCmd) { return $javaCmd.Source }
    $searchRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\BellSoft",
        "C:\Program Files\Azul Systems",
        "C:\Program Files\Amazon Corretto"
    )
    foreach ($root in $searchRoots) {
        if (-not (Test-Path $root)) { continue }
        $jdkDirs = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue
        foreach ($dir in $jdkDirs) {
            $candidate = Join-Path $dir.FullName "bin\java.exe"
            if (Test-Path $candidate) { return $candidate }
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
    # 번들 JRE 탐색 (jpackage=runtime\, 수동설치=jre\), 없으면 시스템 java
    $javaExe = $null
    foreach ($cand in @((Join-Path $InstallDir "runtime\bin\java.exe"),
                        (Join-Path $InstallDir "jre\bin\java.exe"))) {
        if (Test-Path $cand) { $javaExe = $cand; break }
    }
    if (-not $javaExe) { $javaExe = Find-Java }
    if (-not $javaExe) {
        Write-Log "오류: java.exe를 찾을 수 없습니다. 번들 JRE 또는 JDK 17+ 필요."
        return $null
    }
    # config.dir = agent.jar가 있는 디렉터리 (config.json / heartbeat-ok 위치)
    $configDir = Split-Path $resolvedJar -Parent
    Write-Log "agent.jar 직접 실행... (java=$javaExe, config=$configDir)"
    $javaArgs = @("-Xmx128m", "-Dpcagent.config.dir=$configDir", "-jar", $resolvedJar)
    return Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $configDir `
        -NoNewWindow -PassThru -ErrorAction Stop
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

# ── 메인 워치독 루프 ─────────────────────────────────────────────────────
Write-Log "================================================================"
Write-Log "PCAgent 워치독 시작. InstallDir=$InstallDir LauncherPath=$LauncherPath"
Write-Log "================================================================"

# heartbeat-ok 경로를 에이전트 실제 기록 위치(agent.jar 옆 = config.dir)로 맞춘다.
# (에이전트는 configPath.getParent()\heartbeat-ok 에 기록한다)
$jarForHb = Resolve-Jar -InstallDir $InstallDir
if ($jarForHb) {
    $heartbeatOk = Join-Path (Split-Path $jarForHb -Parent) "heartbeat-ok"
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
