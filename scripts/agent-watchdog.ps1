#Requires -Version 5.1
<#
.SYNOPSIS
    PCAgent 워치독 - 에이전트를 항상 살아있게 유지합니다 (좀비 모드).

.DESCRIPTION
    Windows 작업 스케줄러(Task Scheduler)에 의해 SYSTEM 계정으로 부팅 시 자동 실행됩니다.
    에이전트가 충돌하거나 종료되면 5초 후 자동으로 재시작합니다.
    연속 실패 시 대기 시간이 점진적으로 증가합니다 (최대 60초).

.PARAMETER InstallDir
    에이전트 설치 폴더 경로. 기본값: C:\Program Files\PCAgent

.PARAMETER LauncherPath
    실행할 런처 경로. 기본값: InstallDir\PCAgent.exe
#>
param(
    [string]$InstallDir = "C:\Program Files\PCAgent",
    [string]$LauncherPath = ""
)

$programDataLogDir = "C:\ProgramData\PCAgent\logs"
if (!(Test-Path $programDataLogDir)) {
    New-Item -ItemType Directory -Path $programDataLogDir -Force -ErrorAction SilentlyContinue | Out-Null
}
$logFile = Join-Path $programDataLogDir "watchdog.log"

# ── 로그 유틸 ──────────────────────────────────────────────────────────────
function Write-Log {
    param([string]$Message)
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [Watchdog] $Message"
    try { Add-Content -Path $logFile -Value $line -ErrorAction SilentlyContinue } catch {}
    Write-Output $line
}

function Trim-Log {
    # 로그 파일이 1000줄 초과 시 최신 500줄만 유지
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
    # 1순위: JAVA_HOME 환경 변수
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        return "$env:JAVA_HOME\bin\java.exe"
    }
    # 2순위: PATH에서 탐색
    $javaCmd = Get-Command "java" -ErrorAction SilentlyContinue
    if ($javaCmd) { return $javaCmd.Source }
    # 3순위: 일반적인 JDK 설치 경로 스캔
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

function Resolve-Launcher {
    param([string]$InstallDir, [string]$LauncherPath)

    if ($LauncherPath -and (Test-Path $LauncherPath)) {
        return $LauncherPath
    }

    $candidates = @(
        (Join-Path $InstallDir "PCAgent.exe"),
        (Join-Path $InstallDir "agent.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
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
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    return $null
}

# ── 메인 워치독 루프 ─────────────────────────────────────────────────────
Write-Log "================================================================"
Write-Log "PCAgent 워치독 시작. InstallDir=$InstallDir LauncherPath=$LauncherPath"
Write-Log "================================================================"

$consecutiveFails = 0

while ($true) {
    Trim-Log

    try {
        $resolvedLauncher = Resolve-Launcher -InstallDir $InstallDir -LauncherPath $LauncherPath
        $resolvedJar = Resolve-Jar -InstallDir $InstallDir

        if ($resolvedLauncher) {
            # Native executable 모드
            Write-Log "런처 실행 중... ($resolvedLauncher)"
            $proc = Start-Process -FilePath $resolvedLauncher `
                -WorkingDirectory $InstallDir `
                -NoNewWindow -PassThru -ErrorAction Stop

        } elseif ($resolvedJar) {
            # JAR 모드 - 번들 JRE → JAVA_HOME → PATH → 설치 경로 순으로 탐색
            $bundledJava = Join-Path $InstallDir "jre\bin\java.exe"
            $javaExe = if (Test-Path $bundledJava) { $bundledJava } else { Find-Java }
            if (-not $javaExe) {
                Write-Log "오류: java.exe를 찾을 수 없습니다. 번들 JRE(jre\bin\java.exe) 또는 JDK 17+ 설치 필요. 60초 후 재시도..."
                Start-Sleep -Seconds 60
                continue
            }
            Write-Log "agent.jar 실행 중... (java=$javaExe)"
            $javaArgs = @(
                "-Xmx128m",
                "-Dpcagent.config.dir=$InstallDir",
                "-jar",
                $resolvedJar
            )
            $proc = Start-Process -FilePath $javaExe `
                -ArgumentList $javaArgs `
                -WorkingDirectory $InstallDir `
                -NoNewWindow -PassThru -ErrorAction Stop

        } else {
            Write-Log "오류: 실행 파일(PCAgent.exe/agent.exe) 또는 agent.jar를 찾을 수 없습니다. 60초 후 재시도..."
            Start-Sleep -Seconds 60
            $consecutiveFails++
            continue
        }

        Write-Log "에이전트 시작됨 (PID=$($proc.Id)). 종료 대기 중..."
        $consecutiveFails = 0
        $proc.WaitForExit()
        Write-Log "에이전트 종료됨 (ExitCode=$($proc.ExitCode))"

    } catch {
        Write-Log "에이전트 실행 오류: $_"
        $consecutiveFails++
    }

    # 연속 실패 횟수에 따라 대기 시간 지수 증가 (최대 60초)
    if ($consecutiveFails -gt 0) {
        $delay = [Math]::Min(5 * [Math]::Pow(2, ($consecutiveFails - 1)), 60)
        Write-Log "$consecutiveFails 번 연속 실패. ${delay}초 후 재시작..."
        Start-Sleep -Seconds $delay
    } else {
        Write-Log "5초 후 재시작..."
        Start-Sleep -Seconds 5
    }
}
