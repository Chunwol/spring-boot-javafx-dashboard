@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PCAGENT_REPAIR_BOOTSTRAP_LOG=%ProgramData%\PCAgent\logs\watchdog-repair-bootstrap.log"
if not exist "%ProgramData%\PCAgent\logs" mkdir "%ProgramData%\PCAgent\logs" >nul 2>&1
if not exist "%ProgramData%\PCAgent\logs" set "PCAGENT_REPAIR_BOOTSTRAP_LOG=%TEMP%\watchdog-repair-bootstrap.log"
set "PCAGENT_POWERSHELL=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PCAGENT_POWERSHELL%" set "PCAGENT_POWERSHELL=powershell.exe"

set "PCAGENT_REPAIR_CMD=%~f0"
set "PCAGENT_REPAIR_SOURCE_DIR=%~dp0"
if "%PCAGENT_REPAIR_SOURCE_DIR:~-1%"=="\" set "PCAGENT_REPAIR_SOURCE_DIR=%PCAGENT_REPAIR_SOURCE_DIR:~0,-1%"

>>"%PCAGENT_REPAIR_BOOTSTRAP_LOG%" echo %DATE% %TIME% [Bootstrap] Started: "%PCAGENT_REPAIR_CMD%"

fltmc >nul 2>&1
if errorlevel 1 (
    echo [INFO] Requesting administrator privileges...
    set "PCAGENT_REPAIR_ELEVATED_COPY=%TEMP%\PCAgentWatchdogRepair-%RANDOM%-%RANDOM%.cmd"
    copy /Y "%~f0" "!PCAGENT_REPAIR_ELEVATED_COPY!" >nul 2>&1
    if errorlevel 1 (
        >>"%PCAGENT_REPAIR_BOOTSTRAP_LOG%" echo %DATE% %TIME% [Bootstrap] Temp copy failed. Using original file.
        set "PCAGENT_REPAIR_ELEVATED_COPY=%~f0"
    ) else (
        >>"%PCAGENT_REPAIR_BOOTSTRAP_LOG%" echo %DATE% %TIME% [Bootstrap] Temp copy created: "!PCAGENT_REPAIR_ELEVATED_COPY!"
    )
    "%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $target=$env:PCAGENT_REPAIR_ELEVATED_COPY; if(-not (Test-Path -LiteralPath $target)){ throw ('Repair CMD not found: ' + $target) }; Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c',$target -Verb RunAs"
    if errorlevel 1 (
        >>"%PCAGENT_REPAIR_BOOTSTRAP_LOG%" echo %DATE% %TIME% [Bootstrap] Elevation failed. ErrorLevel=!ERRORLEVEL!
        echo [ERROR] Failed to start elevated repair. See: "%PCAGENT_REPAIR_BOOTSTRAP_LOG%"
        timeout /t 30 >nul
        exit /b 1
    )
    exit /b 0
)

"%PCAGENT_POWERSHELL%" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $log=$env:PCAGENT_REPAIR_BOOTSTRAP_LOG; try { $path=$env:PCAGENT_REPAIR_CMD; if(-not (Test-Path -LiteralPath $path)){ throw ('Repair CMD not found: ' + $path) }; $marker='### POWERSHELL ###'; $payload='### WATCHDOG_PS1_BASE64 ###'; $text=[IO.File]::ReadAllText($path); $idx=$text.LastIndexOf($marker); if($idx -lt 0){ throw 'PowerShell payload marker not found.' }; $end=$text.LastIndexOf($payload); if($end -lt $idx){$end=$text.Length}; $script=$text.Substring($idx + $marker.Length, $end - ($idx + $marker.Length)); $tempPs1=Join-Path $env:TEMP ('PCAgentRepair-' + [Guid]::NewGuid().ToString('N') + '.ps1'); [IO.File]::WriteAllText($tempPs1, $script, (New-Object Text.UTF8Encoding($true))); try { & $tempPs1 } finally { Remove-Item -LiteralPath $tempPs1 -Force -ErrorAction SilentlyContinue } } catch { $msg='Bootstrap ERROR: ' + $_.Exception.Message; try { Add-Content -LiteralPath $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' [Bootstrap] ' + $msg) -Encoding UTF8 } catch {}; Write-Error $msg; exit 1 }"
set "PCAGENT_REPAIR_EXIT=%ERRORLEVEL%"
echo.
if "%PCAGENT_REPAIR_EXIT%"=="0" (
    echo ================================================
    echo  [OK] 복구 완료 - 에이전트 + Compass 차단 + SQL/SSMS 이름 동기화
    echo ================================================
) else if "%PCAGENT_REPAIR_EXIT%"=="2" (
    echo ================================================
    echo  [주의] 작업은 등록됐으나 5분 내 정상 동작 미확인
    echo  로그: "%PCAGENT_REPAIR_BOOTSTRAP_LOG%"
    echo ================================================
) else (
    echo ================================================
    echo  [실패] 복구 실패
    echo  로그: "%PCAGENT_REPAIR_BOOTSTRAP_LOG%"
    echo ================================================
)
echo.
pause
exit /b %PCAGENT_REPAIR_EXIT%

### POWERSHELL ###
$ErrorActionPreference = 'Stop'

$taskName = 'PCAgentWatchdog'
$dataDir = 'C:\ProgramData\PCAgent'
$logDir = Join-Path $dataDir 'logs'
$logFile = Join-Path $logDir 'watchdog-repair.log'
$sourceDir = $env:PCAGENT_REPAIR_SOURCE_DIR

function Write-RepairLog {
    param([string]$Message, [switch]$Detail)
    $line = '{0} [Repair] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    # -Detail: 파일 로그에만 남기고 화면에는 출력하지 않는다(정상 상황인 "없는 작업 삭제 시도" 등의
    # 장문 CIM/schtasks 메시지가 화면을 덮어 진짜 결과를 가리는 것을 막는다).
    if (-not $Detail) { Write-Host $line }
    try { Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8 -ErrorAction SilentlyContinue } catch {}
}

function Quote-PsLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

# BSOD/비정상 종료 후 Task Scheduler(Schedule) 서비스 자체가 시작되지 않는 경우가 있다.
# 그러면 BootTrigger 가 걸리지 않아 워치독이 아예 실행되지 않고(로그 파일조차 안 생김),
# 수동 실행만 동작한다. 작업만 등록하고 끝내면 재부팅 후에도 계속 죽어있으므로 먼저 확인/복구한다.
# 원인/해결 근거: MS 문서 "작업 스케줄러 서비스 시작 실패 문제 해결"
function Ensure-TaskSchedulerService {
    # 1) 의존 서비스 TimeBrokerSvc — 중지/비활성이면 Schedule 이 시작 직후 스스로 멈춘다.
    try {
        $tb = Get-Service TimeBrokerSvc -ErrorAction Stop
        $tbStart = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\TimeBrokerSvc' -Name Start -ErrorAction SilentlyContinue).Start
        if ($tbStart -eq 4) {
            Set-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\TimeBrokerSvc' -Name Start -Value 3 -ErrorAction Stop
            Write-RepairLog 'TimeBrokerSvc 가 사용 안 함(4) 상태여서 기본값(3)으로 되돌렸습니다. 재부팅 후 적용됩니다.'
        }
        if ($tb.Status -ne 'Running') {
            Start-Service TimeBrokerSvc -ErrorAction SilentlyContinue
            Write-RepairLog "TimeBrokerSvc 가 중지 상태여서 시작했습니다 (현재: $((Get-Service TimeBrokerSvc).Status))."
        }
    } catch {
        Write-RepairLog "TimeBrokerSvc 확인 실패: $($_.Exception.Message)" -Detail
    }

    # 2) Schedule 서비스 상태 확인 및 시작
    try {
        $svc = Get-Service Schedule -ErrorAction Stop
        if ($svc.StartType -ne 'Automatic') {
            Set-Service Schedule -StartupType Automatic -ErrorAction SilentlyContinue
            Write-RepairLog "Schedule 서비스 시작 유형을 Automatic 으로 변경했습니다 (이전: $($svc.StartType))."
        }
        if ($svc.Status -ne 'Running') {
            Write-RepairLog "경고: Task Scheduler(Schedule) 서비스가 중지 상태입니다 ($($svc.Status)). 시작을 시도합니다..."
            try {
                Start-Service Schedule -ErrorAction Stop
                Write-RepairLog 'Schedule 서비스를 시작했습니다.'
            } catch {
                # 시작 실패 시 MS 문서의 원인 3가지를 짚어 구체적으로 안내한다.
                Write-RepairLog "Schedule 서비스 시작 실패: $($_.Exception.Message)"
                $dll = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\Schedule\Parameters' -Name ServiceDll -ErrorAction SilentlyContinue).ServiceDll
                $dllPath = [Environment]::ExpandEnvironmentVariables(($dll | ForEach-Object { $_ }))
                if (-not $dll -or -not (Test-Path $dllPath)) {
                    Write-RepairLog "  원인 후보: schedsvc.dll 누락/잘못된 경로 (ServiceDll=$dll). 관리자 권한에서 'sfc /scannow' 로 복구하세요."
                }
                $sddl = (wevtutil get-log SYSTEM 2>$null | Select-String 'channelAccess')
                if ($sddl -and ($sddl.ToString() -notmatch '\(A;;0xf0007;;;SY\)')) {
                    Write-RepairLog '  원인 후보: 시스템 이벤트 로그에 SYSTEM 쓰기 권한 없음(오류 5). EventLog\System\CustomSD 확인 필요.'
                }
                Write-RepairLog '  → 서비스가 시작되지 않으면 부팅 시 자동 실행이 불가능합니다. 재부팅 후에도 같으면 sfc /scannow 또는 OS 복구가 필요합니다.'
                return $false
            }
        }
    } catch {
        Write-RepairLog "Schedule 서비스 확인 실패: $($_.Exception.Message)"
        return $false
    }

    # 3) 서비스가 실패해도 스스로 되살아나도록 복구 옵션 설정 (BSOD 후 재발 방지)
    try {
        & sc.exe failure Schedule reset= 86400 actions= restart/60000/restart/60000/restart/60000 | Out-Null
        Write-RepairLog 'Schedule 서비스 복구 옵션 설정 완료 (실패 시 1분 후 자동 재시작).' -Detail
    } catch {
        Write-RepairLog "Schedule 서비스 복구 옵션 설정 실패: $($_.Exception.Message)" -Detail
    }
    return $true
}

# 로그 폴더가 쓰기 불가가 되면(BSOD 후 파일시스템/권한 손상 등) 워치독이 자기 로그도 못 남기고,
# 에이전트 stdout/stderr 리다이렉트도 실패해 에이전트 기동 자체가 막힌다. 실제 장애 사례가 있었다.
function Ensure-LogDirWritable {
    $probe = Join-Path $logDir ('.write-probe-{0}.tmp' -f ([Guid]::NewGuid().ToString('N')))
    try {
        if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force -ErrorAction Stop | Out-Null }
        [IO.File]::WriteAllText($probe, 'ok')
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        Write-RepairLog 'Log directory is writable.' -Detail
        return $true
    } catch {
        Write-RepairLog "경고: 로그 폴더에 쓸 수 없습니다 ($logDir): $($_.Exception.Message)"
        # SYSTEM/Administrators/Users 쓰기 권한 복구 시도
        try {
            & icacls.exe $logDir /grant 'SYSTEM:(OI)(CI)F' 'Administrators:(OI)(CI)F' 'Users:(OI)(CI)M' /T /C | Out-Null
            [IO.File]::WriteAllText($probe, 'ok')
            Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
            Write-RepairLog '  → 권한 복구 후 쓰기 가능해졌습니다.'
            return $true
        } catch {
            Write-RepairLog "  → 권한 복구 실패: $($_.Exception.Message)"
            Write-RepairLog '  → 재부팅하거나 chkdsk 로 파일시스템을 점검하세요. (워치독은 리다이렉트 없이 기동을 계속 시도합니다)'
            return $false
        }
    }
}

# 잠긴 watchdog.log 때문에 로그가 한 줄도 안 남는 경우를 정리한다.
# (워치독 인스턴스가 중복 실행되면 서로 같은 파일을 두고 경쟁하다 배타 잠금이 걸릴 수 있다)
function Reset-LockedWatchdogLog {
    $wlog = Join-Path $logDir 'watchdog.log'
    if (-not (Test-Path $wlog)) { return }
    try {
        $fs = [IO.File]::Open($wlog, 'Open', 'ReadWrite', 'None')
        $fs.Close()
        Write-RepairLog 'watchdog.log is not locked.' -Detail
    } catch {
        $archived = Join-Path $logDir ('watchdog.locked-{0}.log' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        try {
            Rename-Item -LiteralPath $wlog -NewName (Split-Path $archived -Leaf) -ErrorAction Stop
            Write-RepairLog "잠겨 있던 watchdog.log 를 보관하고 새로 시작합니다: $archived"
        } catch {
            Write-RepairLog "경고: watchdog.log 가 잠겨 있으나 정리하지 못했습니다 ($($_.Exception.Message)). 워치독은 대체 로그 파일로 기록합니다."
        }
    }
}

# ── MongoDB Compass 자동 업데이트 차단 ──────────────────────────────────────
# Compass 는 실행 파일 기준 위치의 mongodb-compass.cfg 를 읽는다.
#   .msi 설치 : %ProgramFiles%\mongodb-compass.cfg              (설치 폴더의 부모)
#   .exe 설치 : %LOCALAPPDATA%\MongoDBCompass\app-<버전>\mongodb-compass.cfg
# .exe 설치는 사용자별 + 버전별 폴더라 모든 프로필의 app-* 를 훑는다.
function Get-CompassConfigTargets {
    $targets = New-Object System.Collections.Generic.List[string]

    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (-not $base) { continue }
        if (Test-Path (Join-Path $base 'MongoDB Compass\MongoDBCompass.exe')) {
            $targets.Add((Join-Path $base 'mongodb-compass.cfg'))
        }
    }

    $usersRoot = Join-Path $env:SystemDrive 'Users'
    if (Test-Path $usersRoot) {
        Get-ChildItem $usersRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $appRoot = Join-Path $_.FullName 'AppData\Local\MongoDBCompass'
            if (Test-Path $appRoot) {
                Get-ChildItem $appRoot -Directory -Filter 'app-*' -ErrorAction SilentlyContinue | ForEach-Object {
                    if (Test-Path (Join-Path $_.FullName 'MongoDBCompass.exe')) {
                        $targets.Add((Join-Path $_.FullName 'mongodb-compass.cfg'))
                    }
                }
            }
        }
    }
    return ($targets | Select-Object -Unique)
}

# 기존 설정은 보존하고 autoUpdates 만 false 로. JSON 으로 못 읽으면(YAML/손상) 백업 후 새로 만든다.
function Set-CompassAutoUpdatesFalse {
    param([string]$CfgPath)
    $settings = [ordered]@{}
    if (Test-Path $CfgPath) {
        $raw = ''
        try { $raw = Get-Content -LiteralPath $CfgPath -Raw -ErrorAction Stop } catch { $raw = '' }
        if ($raw -and $raw.Trim() -ne '') {
            try {
                $parsed = $raw | ConvertFrom-Json -ErrorAction Stop
                foreach ($p in $parsed.PSObject.Properties) { $settings[$p.Name] = $p.Value }
            } catch {
                $backup = "$CfgPath.backup-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmmss')
                Copy-Item -LiteralPath $CfgPath -Destination $backup -Force -ErrorAction SilentlyContinue
                Write-RepairLog "  기존 설정을 JSON 으로 읽지 못해 백업 후 새로 만듭니다: $backup" -Detail
            }
        }
    }
    if ($settings.Contains('autoUpdates') -and $settings['autoUpdates'] -eq $false) {
        Write-RepairLog "  이미 차단됨: $CfgPath" -Detail
        return 'already'
    }
    $settings['autoUpdates'] = $false
    [IO.File]::WriteAllText($CfgPath, ($settings | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
    Write-RepairLog "  차단 적용: $CfgPath" -Detail
    return 'applied'
}

# 반환: 요약 문자열 (최종 결과 출력용)
function Invoke-CompassAutoUpdateBlock {
    try {
        $targets = Get-CompassConfigTargets
        if (-not $targets -or $targets.Count -eq 0) {
            Write-RepairLog 'MongoDB Compass: 설치를 찾지 못해 건너뜁니다.'
            return 'Compass: 설치 없음(건너뜀)'
        }
        $running = Get-Process -Name 'MongoDBCompass' -ErrorAction SilentlyContinue
        if ($running) {
            Write-RepairLog "MongoDB Compass: 실행 중이라 다음 실행부터 적용됩니다 (PID: $($running.Id -join ', '))."
        }
        $applied = 0; $already = 0; $failed = 0
        foreach ($t in $targets) {
            try {
                if ((Set-CompassAutoUpdatesFalse -CfgPath $t) -eq 'applied') { $applied++ } else { $already++ }
            } catch {
                $failed++
                Write-RepairLog "  실패: $t ($($_.Exception.Message))" -Detail
            }
        }
        $summary = "Compass: 자동 업데이트 차단 (신규 $applied / 기존 $already / 실패 $failed)"
        Write-RepairLog "MongoDB Compass: 대상 $($targets.Count)개 — 신규 적용 $applied, 이미 적용됨 $already, 실패 $failed"
        return $summary
    } catch {
        Write-RepairLog "MongoDB Compass 처리 중 오류: $($_.Exception.Message)"
        return 'Compass: 처리 오류(로그 확인)'
    }
}

# ── SQL Server @@SERVERNAME / SSMS 최근 연결 동기화 ────────────────────────
# 이미지 복제 후 PC 이름이 바뀌면 @@SERVERNAME 은 옛 이름을 계속 들고 있어서
# SSMS 접속 시 서버 이름 불일치로 혼선이 생긴다. 아래 함수들은 PC-Setup-Final.cmd 의
# 검증된 로직을 그대로 가져오고, 로깅/백업만 이 스크립트 환경에 맞춰 얇게 감쌌다.
# (PC-Setup-Final 의 롤백 상태머신 Capture-Once 까지는 가져오지 않는다)
$DryRun = $false
$script:changes = New-Object System.Collections.ArrayList
function Log { param([string]$m) Write-RepairLog $m }
function Section { param([string]$t) Write-RepairLog ('==== ' + $t + ' ====') }
function Backup-File-Once {
  param([string]$File)
  try {
    if (Test-Path $File) {
      $bak = $File + '.autoupdates-bak'
      if (-not (Test-Path $bak)) { Copy-Item -LiteralPath $File -Destination $bak -Force -ErrorAction SilentlyContinue }
    }
  } catch {}
}

function Fix-SsmsMruConnections {
  param([string]$OldName, [string]$NewName)
  # SSMS 의 "연결" 대화상자 History 탭(MruSqlConnectionsGroup)은 RegSrvr17.xml 에
  # 평문 XML 로 저장되며, 컴퓨터 이름이 바뀌어도 예전 이름을 그대로 들고 있다.
  # 여기서는 SQL Server 자체에서 확인된 정확한 옛 이름(OldName)만 치환하므로,
  # 사용자가 등록해 둔 다른 진짜 원격 서버 항목은 건드리지 않는다.
  if([string]::IsNullOrWhiteSpace($OldName) -or [string]::IsNullOrWhiteSpace($NewName) -or ($OldName -ieq $NewName)){ return }
  Get-ChildItem 'C:\Users' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $regFile = Join-Path $_.FullName 'AppData\Roaming\Microsoft\SQL Server Management Studio\RegSrvr17.xml'
    if(-not (Test-Path $regFile)){ return }
    try {
      $text = [IO.File]::ReadAllText($regFile)
      if($text -notmatch [regex]::Escape($OldName)){ return }
      $hitCount = ([regex]::Matches($text, [regex]::Escape($OldName))).Count
      Log ('SSMS 최근 연결(History) 목록: ' + $regFile + '  (' + $OldName + ' 발견 ' + $hitCount + '건)')
      if($DryRun){ Log ('   [DRYRUN] would replace ' + $OldName + ' -> ' + $NewName); return }
      Backup-File-Once $regFile
      $newText = $text -replace [regex]::Escape($OldName), $NewName
      [IO.File]::WriteAllText($regFile, $newText, (New-Object System.Text.UTF8Encoding($false)))
      Log ('   -> 갱신됨: ' + $OldName + ' -> ' + $NewName)
      [void]$script:changes.Add('ssms mru fixed: ' + $regFile)
    } catch { Log ('   SSMS MRU 갱신 실패: ' + $_.Exception.Message) }
  }
}

# sqlcmd.exe 가 없는 PC 에서도 동작하도록, sqlcmd 가 있으면 sqlcmd 를,
# 없으면 .NET SqlClient(Windows 에 기본 포함)를 사용해 질의한다.
function Invoke-SqlQueryCompat {
  param([string]$ServerConn, [string]$Query, [string]$SqlcmdPath)
  if($SqlcmdPath){
    $out = & $SqlcmdPath -S $ServerConn -E -C -h -1 -Q ('SET NOCOUNT ON; ' + $Query) 2>&1
    $v = (@($out | Where-Object { $_ -and ([string]$_).Trim() -ne '' }) | Select-Object -First 1)
    if($v){ return ([string]$v).Trim() }
    return $null
  }
  $conn = New-Object System.Data.SqlClient.SqlConnection ('Server=' + $ServerConn + ';Integrated Security=True;Connect Timeout=15;TrustServerCertificate=True')
  try {
    $conn.Open()
    $cmd = $conn.CreateCommand(); $cmd.CommandText = $Query; $cmd.CommandTimeout = 30
    $r = $cmd.ExecuteScalar()
    if($r){ return ([string]$r).Trim() }
    return $null
  } finally { $conn.Close() }
}

function Invoke-SqlExecCompat {
  param([string]$ServerConn, [string]$Query, [string]$SqlcmdPath)
  if($SqlcmdPath){ return (& $SqlcmdPath -S $ServerConn -E -C -Q $Query 2>&1) }
  $conn = New-Object System.Data.SqlClient.SqlConnection ('Server=' + $ServerConn + ';Integrated Security=True;Connect Timeout=15;TrustServerCertificate=True')
  try {
    $conn.Open()
    $cmd = $conn.CreateCommand(); $cmd.CommandText = $Query; $cmd.CommandTimeout = 60
    [void]$cmd.ExecuteNonQuery()
    return '   (SqlClient) 실행 완료'
  } finally { $conn.Close() }
}

function Fix-SqlServerName {
  Section 'SQL Server @@SERVERNAME / SSMS 최근 연결 - 컴퓨터 이름 동기화'
  # 컴퓨터 이름이 SQL Server 설치 이후 바뀌면 @@SERVERNAME 은 옛날 이름을 계속
  # 들고 있어서, SSMS 최근 연결 등에서 서버 이름 불일치로 접속에 혼선이 생긴다.
  # 공식 해결법: sp_dropserver(옛 이름) + sp_addserver(현재 이름, 'local') 후
  # 서비스 재시작. 이미 일치하면 아무것도 하지 않는다 (여러 PC에 재사용 가능).
  # 32비트 PowerShell 로 실행되면 HKLM\SOFTWARE 가 WOW6432Node 로 리디렉션되어,
  # 실제로 설치돼 있는데도 '미설치'로 오판하는 PC 가 있었다. 두 경로를 모두 확인한다.
  $instKeys = @(
    'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Microsoft SQL Server\Instance Names\SQL'
  )
  $names = @()
  foreach($k in $instKeys){
    if(-not (Test-Path $k)){ continue }
    $p = Get-ItemProperty $k -ErrorAction SilentlyContinue
    if(-not $p){ continue }
    $names += @($p.PSObject.Properties | Where-Object { $_.Name -notmatch '^PS' } | Select-Object -ExpandProperty Name)
  }
  $names = @($names | Select-Object -Unique)
  if($names.Count -eq 0){ Log 'SQL Server 인스턴스를 찾지 못했습니다; 건너뜁니다.'; return }
  Log ('감지된 SQL Server 인스턴스: ' + ($names -join ', '))

  # sqlcmd.exe 는 Program Files 외에도 여러 곳에 설치된다(32비트 도구, Client SDK, SSMS 동봉, 단독 배포).
  # 예전에는 'C:\Program Files\Microsoft SQL Server' 만 뒤져서, 실제로는 SQL 이 설치돼 있는데도
  # 'sqlcmd.exe 를 찾을 수 없어 건너뜁니다' 로 스킵되는 PC 가 있었다.
  # 이제 못 찾아도 중단하지 않고 .NET SqlClient 로 직접 연결한다.
  $sqlcmdPath = (Get-Command sqlcmd.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
  if(-not $sqlcmdPath){
    $sqlcmdRoots = @(
      (Join-Path $env:ProgramFiles 'Microsoft SQL Server'),
      (Join-Path ${env:ProgramFiles(x86)} 'Microsoft SQL Server'),
      (Join-Path $env:ProgramFiles 'sqlcmd'),
      (Join-Path $env:ProgramFiles 'Microsoft SQL Server Management Studio 21'),
      (Join-Path ${env:ProgramFiles(x86)} 'Microsoft SQL Server Management Studio 20'),
      (Join-Path ${env:ProgramFiles(x86)} 'Microsoft SQL Server Management Studio 19'),
      (Join-Path ${env:ProgramFiles(x86)} 'Microsoft SQL Server Management Studio 18')
    ) | Where-Object { $_ -and (Test-Path $_) }
    foreach($r in $sqlcmdRoots){
      $hit = @(Get-ChildItem $r -Recurse -Filter 'sqlcmd.exe' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
      if($hit){ $sqlcmdPath = $hit; break }
    }
  }
  if($sqlcmdPath){ Log ('sqlcmd 사용: ' + $sqlcmdPath) }
  else { Log 'sqlcmd.exe 없음 -> .NET SqlClient 로 직접 연결합니다.' }

  $computerName = $env:COMPUTERNAME

  foreach($inst in $names){
    $serverConn = if($inst -eq 'MSSQLSERVER'){ $computerName } else { $computerName + '\' + $inst }
    $svcName = if($inst -eq 'MSSQLSERVER'){ 'MSSQLSERVER' } else { 'MSSQL$' + $inst }
    $expected = $serverConn

    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    if(-not $svc){ Log ('인스턴스 ' + $inst + ' 서비스(' + $svcName + ')를 찾을 수 없습니다; 건너뜁니다.'); continue }
    if($svc.Status -ne 'Running'){
      # SQL Express 등은 시작 유형이 '수동'이라 꺼져 있는 경우가 많다. 예전에는 그대로 스킵해서
      # 실제로는 설치돼 있는데도 이름 동기화가 안 되고 넘어갔다. 이제 켜고 진행한다.
      Log ('인스턴스 ' + $inst + ' 서비스가 중지 상태(' + $svc.Status + ') -> 시작을 시도합니다.')
      if($DryRun){ Log '   [DRYRUN] would start the service and continue'; continue }
      try { Start-Service -Name $svcName -ErrorAction Stop; Start-Sleep -Seconds 5 }
      catch { Log ('   서비스 시작 실패: ' + $_.Exception.Message + '; 건너뜁니다.'); continue }
      $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
      if(-not $svc -or $svc.Status -ne 'Running'){ Log '   서비스가 여전히 실행 중이 아닙니다; 건너뜁니다.'; continue }
      Log '   서비스 시작됨.'
    }

    $currentServerName = $null
    try {
      $currentServerName = Invoke-SqlQueryCompat -ServerConn $serverConn -Query 'SELECT @@SERVERNAME' -SqlcmdPath $sqlcmdPath
    } catch { Log ('   조회 실패: ' + $_.Exception.Message); continue }

    if(-not $currentServerName){ Log ('   인스턴스 ' + $inst + ': @@SERVERNAME 조회 실패; 건너뜁니다.'); continue }
    Log ('인스턴스 ' + $inst + ' : 현재 @@SERVERNAME=' + $currentServerName + ' / 실제 컴퓨터이름=' + $expected)

    if($currentServerName -ieq $expected){ Log '   이미 일치합니다.'; continue }

    Log ('   불일치 감지: ' + $currentServerName + ' -> ' + $expected)

    # SSMS 클라이언트 쪽 History 항목은 서버 내부 이름표와 무관하게 안전하게 고칠 수 있으므로
    # DB 쪽 수정 성공 여부와 상관없이 먼저/항상 시도한다 (DryRun 이면 이 함수도 미리보기만 함).
    Fix-SsmsMruConnections -OldName $currentServerName -NewName $expected
    Fix-SsmsPrivateHiveMru -OldName $currentServerName -NewName $expected

    if($DryRun){ Log '   [DRYRUN] would run sp_dropserver / sp_addserver and restart the service'; continue }

    $sql = "EXEC sp_dropserver '" + $currentServerName + "', 'droplogins'; EXEC sp_addserver '" + $expected + "', 'local';"
    $result = Invoke-SqlExecCompat -ServerConn $serverConn -Query $sql -SqlcmdPath $sqlcmdPath
    $result | ForEach-Object { Log ('   ' + $_) }

    Log '   서비스 재시작 중 (변경 적용에 필요)...'
    try {
      Restart-Service -Name $svcName -Force -ErrorAction Stop
      Start-Sleep -Seconds 5
      $svc2 = Get-Service -Name $svcName -ErrorAction SilentlyContinue
      if($svc2 -and $svc2.Status -eq 'Running'){
        $verifyName = Invoke-SqlQueryCompat -ServerConn $serverConn -Query 'SELECT @@SERVERNAME' -SqlcmdPath $sqlcmdPath
        if($verifyName -ieq $expected){ Log ('   -> 확인됨: @@SERVERNAME = ' + $verifyName); [void]$script:changes.Add('sql servername fixed: ' + $inst + ' -> ' + $expected) }
        else { Log ('   -> 재시작 후에도 불일치: ' + $verifyName) }
      } else { Log '   -> 서비스가 재시작 후 실행 중이 아닙니다.' }
    } catch { Log ('   -> 서비스 재시작 실패: ' + $_.Exception.Message) }
  }
}

function Fix-SsmsPrivateHiveMru {
  param([string]$OldName, [string]$NewName)
  # SSMS 의 "연결" 대화상자 History 탭은 RegSrvr17.xml (평문, 참고용 트리 표시)이
  # 아니라 사용자별 격리 레지스트리 하이브(privateregistry.bin) 안의
  # Software\Microsoft\SSMS\<버전>\ConnectionMruList 키에서 읽는다.
  # 그 안의 Connection<N> 값은 DPAPI(현재 사용자 키)로 암호화된 연결 문자열이라
  # 단순 텍스트 치환이 불가능하므로, 복호화 -> 치환 -> 재암호화 후 다시 쓴다.
  # DPAPI 특성상 "현재 로그인한 사용자" 소유 하이브에서만 동작한다 (다른 사용자
  # 계정 하이브는 그 계정으로 로그인한 상태가 아니면 복호화할 수 없다).
  if([string]::IsNullOrWhiteSpace($OldName) -or [string]::IsNullOrWhiteSpace($NewName) -or ($OldName -ieq $NewName)){ return }
  Add-Type -AssemblyName System.Security -ErrorAction SilentlyContinue
  $ssmsRoot = Join-Path $env:USERPROFILE 'AppData\Local\Microsoft\SSMS'
  if(-not (Test-Path $ssmsRoot)){ return }
  $hives = @(Get-ChildItem $ssmsRoot -Recurse -Filter 'privateregistry.bin' -ErrorAction SilentlyContinue)
  if($hives.Count -eq 0){ return }

  foreach($hiveFile in $hives){
    Log ('SSMS History (ConnectionMruList): ' + $hiveFile.FullName)
    if($DryRun){ Log ('   [DRYRUN] would decrypt/replace ' + $OldName + ' -> ' + $NewName + ' in DPAPI-protected connection history'); continue }
    Backup-File-Once $hiveFile.FullName
    $mountName = 'SSMSFix_' + [guid]::NewGuid().ToString('N').Substring(0,8)
    $loaded = $false
    try {
      $loadOut = & reg.exe load ('HKU\' + $mountName) $hiveFile.FullName 2>&1
      if($LASTEXITCODE -ne 0){ Log ('   reg load 실패 (SSMS 가 실행 중이면 닫고 다시 실행하세요): ' + ($loadOut -join ' ')); continue }
      $loaded = $true
      $ssmsKeyPath = "Registry::HKEY_USERS\$mountName\Software\Microsoft\SSMS"
      $verDirs = @(Get-ChildItem -Path $ssmsKeyPath -ErrorAction SilentlyContinue)
      $anyFixed = $false
      foreach($verDir in $verDirs){
        $mruPath = Join-Path $verDir.PSPath 'ConnectionMruList'
        if(-not (Test-Path $mruPath)){ continue }
        $props = Get-ItemProperty -Path $mruPath -ErrorAction SilentlyContinue
        if(-not $props){ continue }
        $dispNames = @($props.PSObject.Properties | Where-Object { $_.Name -match '^DisplayName\d+$' })
        foreach($dn in $dispNames){
          $idx = $dn.Name -replace '^DisplayName',''
          $connName = 'Connection' + $idx
          $connHex = $props.$connName
          if(-not $connHex){ continue }
          try {
            $rawBytes = for($i=0; $i -lt $connHex.Length; $i+=2){ [Convert]::ToByte($connHex.Substring($i,2),16) }
            $connBytes = [byte[]]$rawBytes
            $dec = [System.Security.Cryptography.ProtectedData]::Unprotect($connBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
            $connText = [System.Text.Encoding]::Unicode.GetString($dec)
          } catch { continue }
          if($connText -notmatch [regex]::Escape($OldName)){ continue }
          $newConnText = $connText -replace [regex]::Escape($OldName), $NewName
          $newBytes = [System.Text.Encoding]::Unicode.GetBytes($newConnText)
          $newEnc = [System.Security.Cryptography.ProtectedData]::Protect($newBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
          $hexParts = $newEnc | ForEach-Object { $_.ToString('X2') }
          $newHex = $hexParts -join ''
          Set-ItemProperty -Path $mruPath -Name $connName -Value $newHex
          if(([string]$dn.Value) -ieq $OldName){ Set-ItemProperty -Path $mruPath -Name $dn.Name -Value $NewName }
          $anyFixed = $true
          Log ('   -> ' + $dn.Name + ' / ' + $connName + ' 갱신됨')
        }
      }
      if($anyFixed){ [void]$script:changes.Add('ssms history fixed: ' + $hiveFile.FullName) }
      else { Log '   일치하는 History 항목 없음.' }
    } catch { Log ('   SSMS History 갱신 실패: ' + $_.Exception.Message) }
    finally {
      if($loaded){
        [gc]::Collect(); [gc]::WaitForPendingFinalizers()
        Start-Sleep -Milliseconds 500
        & reg.exe unload ('HKU\' + $mountName) 2>&1 | Out-Null
      }
    }
  }
}


# 반환: 요약 문자열 (최종 결과 출력용)
function Invoke-SqlServerNameSync {
  try {
    $before = $script:changes.Count
    Fix-SqlServerName
    $delta = $script:changes.Count - $before
    if ($delta -gt 0) { return ('SQL/SSMS: ' + $delta + '건 동기화') }
    return 'SQL/SSMS: 변경 없음'
  } catch {
    Write-RepairLog ('SQL/SSMS 처리 중 오류: ' + $_.Exception.Message)
    return 'SQL/SSMS: 처리 오류(로그 확인)'
  }
}

function Resolve-InstallDir {
    $candidates = @(
        $env:PCAGENT_INSTALL_DIR,
        'C:\Program Files\PCAgent',
        'C:\Program Files (x86)\PCAgent'
    ) | Where-Object { $_ -and $_.Trim() -ne '' }

    foreach ($candidate in $candidates) {
        $path = [IO.Path]::GetFullPath($candidate)
        if ((Test-Path (Join-Path $path 'PCAgent.exe')) -or
            (Test-Path (Join-Path $path 'agent.exe')) -or
            (Test-Path (Join-Path $path 'app\agent.jar')) -or
            (Test-Path (Join-Path $path 'agent.jar'))) {
            return $path
        }
    }
    throw 'PCAgent install directory was not found.'
}

function Stop-AndRemove-Task {
    param([string]$Path, [string]$Name)
    $fullName = "${Path}${Name}"
    try {
        Stop-ScheduledTask -TaskPath $Path -TaskName $Name -ErrorAction Stop | Out-Null
        Write-RepairLog "Stopped scheduled task: $fullName"
    } catch {
        Write-RepairLog "Scheduled task stop skipped or failed: $fullName ($($_.Exception.Message))" -Detail
    }
    try {
        Unregister-ScheduledTask -TaskPath $Path -TaskName $Name -Confirm:$false -ErrorAction Stop | Out-Null
        Write-RepairLog "Removed scheduled task: $fullName"
    } catch {
        Write-RepairLog "PowerShell task removal skipped or failed: $fullName ($($_.Exception.Message))" -Detail
        # schtasks.exe 는 대상 작업이 없거나(레거시 폴더가 자식 작업을 갖고 있어) 삭제에 실패하면
        # stderr 에 메시지를 쓴다. $ErrorActionPreference='Stop' 상태에서 2>&1 로 받으면 PS 5.1 이
        # 이를 '종료 예외'로 승격시켜 repair 전체가 죽는다(없는 작업/비어있지 않은 폴더는 정상 상황이므로
        # 종료해선 안 됨). 네이티브 호출 동안만 EAP 를 Continue 로 낮추고 exit code 로 판정한다.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            schtasks.exe /End /TN $fullName 2>&1 | Out-Null
            $deleteOutput = @(schtasks.exe /Delete /TN $fullName /F 2>&1)
            if ($LASTEXITCODE -eq 0) {
                Write-RepairLog "Removed scheduled task via schtasks: $fullName"
            } else {
                Write-RepairLog "Scheduled task not removed or not present: $fullName (schtasks: $($deleteOutput -join ' '))" -Detail
            }
        } catch {
            Write-RepairLog "Scheduled task not removed or not present: $fullName ($($_.Exception.Message))" -Detail
        } finally {
            $ErrorActionPreference = $prevEap
        }
    }
}

function Get-EncodedCommandText {
    param([string]$CommandLine)
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return '' }
    $match = [regex]::Match($CommandLine, '(?i)(?:-|/)(?:EncodedCommand|enc|e)\s+("([^"]+)"|''([^'']+)''|(\S+))')
    if (-not $match.Success) { return '' }
    $encoded = @($match.Groups[2].Value, $match.Groups[3].Value, $match.Groups[4].Value) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -First 1
    if (-not $encoded) { return '' }
    try {
        return [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encoded))
    } catch {
        return ''
    }
}

function Stop-PCAgentProcesses {
    param([string]$InstallDir)
    $programFilesPath = '*Program Files\PCAgent*'
    $programFilesX86Path = '*Program Files (x86)\PCAgent*'
    $escapedInstallDir = '*' + ($InstallDir -replace '[\[\]]', '?') + '*'
    try {
        $all = @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'java.exe' OR Name = 'PCAgent.exe' OR Name = 'agent.exe'" -ErrorAction SilentlyContinue)
        $targetIds = @{}
        foreach ($proc in $all) {
            if ([int]$proc.ProcessId -eq [int]$PID) { continue }
            $commandLine = [string]$proc.CommandLine
            $executablePath = [string]$proc.ExecutablePath
            $decodedCommand = Get-EncodedCommandText -CommandLine $commandLine

            $isPlainAgentProcess =
                $commandLine -like $programFilesPath -or $executablePath -like $programFilesPath -or
                $commandLine -like $programFilesX86Path -or $executablePath -like $programFilesX86Path -or
                $commandLine -like $escapedInstallDir -or $executablePath -like $escapedInstallDir

            $isWatchdogProcess =
                $proc.Name -ieq 'powershell.exe' -and (
                    $commandLine -like '*agent-watchdog.ps1*' -or
                    $commandLine -like '*PCAgentWatchdog*' -or
                    $decodedCommand -like '*agent-watchdog.ps1*' -or
                    $decodedCommand -like '*PCAgentWatchdog*' -or
                    $decodedCommand -like $escapedInstallDir
                )

            if ($isPlainAgentProcess -or $isWatchdogProcess) {
                $targetIds[[int]$proc.ProcessId] = $true
            }
        }

        do {
            $added = $false
            foreach ($proc in $all) {
                if (-not $targetIds.ContainsKey([int]$proc.ProcessId) -and $targetIds.ContainsKey([int]$proc.ParentProcessId)) {
                    $targetIds[[int]$proc.ProcessId] = $true
                    $added = $true
                }
            }
        } while ($added)

        $targets = $all |
            Where-Object { $targetIds.ContainsKey([int]$_.ProcessId) -and [int]$_.ProcessId -ne [int]$PID } |
            Sort-Object @{Expression = { if ($_.Name -ieq 'powershell.exe') { 0 } else { 1 } }}, ProcessId

        foreach ($proc in $targets) {
            Write-RepairLog "Stopping process: pid=$($proc.ProcessId), name=$($proc.Name)"
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        }
    } catch {
        Write-RepairLog "Process cleanup warning: $($_.Exception.Message)"
    }
}

function Ensure-ProgramDataConfig {
    param([string]$InstallDir, [string]$AppDir)
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    $dst = Join-Path $dataDir 'config.json'
    if (Test-Path $dst) {
        Write-RepairLog "ProgramData config exists: $dst"
        return
    }

    foreach ($src in @((Join-Path $AppDir 'config.json'), (Join-Path $InstallDir 'config.json'))) {
        if (Test-Path $src) {
            Copy-Item -LiteralPath $src -Destination $dst -Force
            Write-RepairLog "ProgramData config initialized from: $src"
            return
        }
    }
    Write-RepairLog 'ProgramData config was not found and no install config source exists.'
}

function Get-EmbeddedWatchdogBytes {
    $marker = '### WATCHDOG_PS1_BASE64 ###'
    try {
        $selfPath = $env:PCAGENT_REPAIR_CMD
        if (-not $selfPath -or -not (Test-Path $selfPath)) { return $null }
        $text = [IO.File]::ReadAllText($selfPath)
        $idx = $text.LastIndexOf($marker)
        if ($idx -lt 0) { return $null }
        $payload = $text.Substring($idx + $marker.Length)
        $payload = [regex]::Replace($payload, '\s+', '')
        if ([string]::IsNullOrWhiteSpace($payload)) { return $null }
        return [Convert]::FromBase64String($payload)
    } catch {
        Write-RepairLog "Embedded watchdog payload read warning: $($_.Exception.Message)"
        return $null
    }
}

function Install-WatchdogScript {
    param([string]$InstallDir, [string]$AppDir)
    $sidecar = Join-Path $sourceDir 'agent-watchdog.ps1'
    $destDir = if (Test-Path $AppDir) { $AppDir } else { $InstallDir }
    $dest = Join-Path $destDir 'agent-watchdog.ps1'

    # PowerShell 5.1 은 BOM 없는 .ps1 을 시스템 ANSI(한국어 Windows는 CP949)로 읽어, UTF-8 한글 주석이
    # 깨지면서 파싱 에러가 나고 워치독이 로그 한 줄 쓰기 전에 즉시 종료(exit)될 수 있다.
    # UTF-8 BOM 을 붙여 PS 5.1 이 UTF-8 로 읽도록 강제한다.
    function Add-Utf8BomIfMissing {
        param([byte[]]$Bytes)
        if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
            return $Bytes
        }
        return [byte[]](0xEF, 0xBB, 0xBF) + $Bytes
    }

    $embeddedBytes = Get-EmbeddedWatchdogBytes
    if ($embeddedBytes) {
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        if (Test-Path $dest) {
            $backup = Join-Path $destDir ('agent-watchdog.backup-{0}.ps1' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
            Copy-Item -LiteralPath $dest -Destination $backup -Force
            Write-RepairLog "Existing watchdog backed up: $backup"
        }
        [IO.File]::WriteAllBytes($dest, (Add-Utf8BomIfMissing $embeddedBytes))
        Write-RepairLog 'Watchdog script updated from embedded payload (UTF-8 BOM ensured).'
    } elseif (Test-Path $sidecar) {
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        if (Test-Path $dest) {
            $backup = Join-Path $destDir ('agent-watchdog.backup-{0}.ps1' -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
            Copy-Item -LiteralPath $dest -Destination $backup -Force
            Write-RepairLog "Existing watchdog backed up: $backup"
        }
        [IO.File]::WriteAllBytes($dest, (Add-Utf8BomIfMissing ([IO.File]::ReadAllBytes($sidecar))))
        Write-RepairLog "Watchdog script updated from sidecar (UTF-8 BOM ensured): $sidecar"
    } elseif (-not (Test-Path $dest)) {
        foreach ($candidate in @((Join-Path $InstallDir 'agent-watchdog.ps1'), (Join-Path $AppDir 'agent-watchdog.ps1'))) {
            if (Test-Path $candidate) {
                $dest = $candidate
                break
            }
        }
    }

    if (-not (Test-Path $dest)) {
        throw 'agent-watchdog.ps1 was not found. Put the fixed agent-watchdog.ps1 next to this CMD and rerun.'
    }

    $content = Get-Content -LiteralPath $dest -Raw
    $looksFixed = $content -match 'Resolve-ConfigDir' -and
        $content -match 'Quote-ProcessArgument' -and
        $content -match 'RedirectStandardOutput'
    if (-not $looksFixed) {
        throw 'Installed agent-watchdog.ps1 looks old. Put the fixed agent-watchdog.ps1 next to this CMD and rerun.'
    }
    return [IO.Path]::GetFullPath($dest)
}

function Resolve-Launcher {
    param([string]$InstallDir)
    foreach ($candidate in @((Join-Path $InstallDir 'PCAgent.exe'), (Join-Path $InstallDir 'agent.exe'))) {
        if (Test-Path $candidate) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    return ''
}

function Register-WatchdogTask {
    param([string]$InstallDir, [string]$WatchdogScript, [string]$LauncherPath)

    $watchdogCommand = '& ' + (Quote-PsLiteral $WatchdogScript) + ' -InstallDir ' + (Quote-PsLiteral $InstallDir)
    if ($LauncherPath -and $LauncherPath.Trim() -ne '') {
        $watchdogCommand += ' -LauncherPath ' + (Quote-PsLiteral $LauncherPath)
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogCommand))
    $actionArgs = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand $encoded"

    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $actionArgs -WorkingDirectory $InstallDir
    $trigger = New-ScheduledTaskTrigger -AtStartup
    # -DisallowStartIfOnBatteries/-StopIfGoingOnBatteries 는 미지정 시 New-ScheduledTaskSettingsSet 기본값이
    # $true 로 잡힌다. 배터리/UPS 순간전환 시 Windows 가 이 작업을 "정책 정지"시키는데, 이는 실패가 아니므로
    # RestartCount/RestartInterval 이 발동하지 않고 다음 재부팅 전까지 절대 복구되지 않는다. 명시적으로 끈다.
    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -MultipleInstances IgnoreNew `
        -Hidden `
        -RestartCount 999 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -StartWhenAvailable `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest

    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Write-RepairLog "Registered scheduled task: $taskName"
}

function Assert-WatchdogTask {
    $task = @(Get-ScheduledTask -TaskName $taskName -ErrorAction Stop |
        Where-Object { $_.TaskPath -eq '\' } |
        Select-Object -First 1)[0]
    if (-not $task) { throw 'PCAgentWatchdog task was not found at the root task path.' }
    $action = @($task.Actions | Select-Object -First 1)[0]
    if (-not $action) { throw 'PCAgentWatchdog task action is missing.' }

    $execute = [string]$action.Execute
    $arguments = [string]$action.Arguments
    $principal = [string]$task.Principal.UserId
    if ($execute -notmatch '(?i)powershell(\.exe)?$') {
        throw "PCAgentWatchdog action executable is not powershell.exe: $execute"
    }
    if ($arguments -notmatch '(?i)-WindowStyle\s+Hidden' -or $arguments -notmatch '(?i)-EncodedCommand') {
        throw 'PCAgentWatchdog action arguments are not the hidden encoded PowerShell command.'
    }
    if ($principal -notmatch '^(SYSTEM|S-1-5-18)$') {
        throw "PCAgentWatchdog principal is not SYSTEM: $principal"
    }
    Write-RepairLog 'Scheduled task verification passed.'
}

function Resolve-AgentJarForVerify {
    param([string]$InstallDir)
    foreach ($candidate in @((Join-Path $InstallDir 'app\agent.jar'), (Join-Path $InstallDir 'agent.jar'))) {
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Get-HeartbeatOkEpochMs {
    param([string]$InstallDir)
    $jar = Resolve-AgentJarForVerify -InstallDir $InstallDir
    $candidates = @((Join-Path $dataDir 'heartbeat-ok'))
    if ($jar) { $candidates += (Join-Path (Split-Path $jar -Parent) 'heartbeat-ok') }
    foreach ($hb in $candidates) {
        if (Test-Path $hb) {
            try {
                $content = (Get-Content -LiteralPath $hb -Raw -ErrorAction Stop).Trim()
                $ms = [long]0
                if ([long]::TryParse($content, [ref]$ms)) { return $ms }
            } catch {}
        }
    }
    return $null
}

# watchdog.log 가 갱신됐는지(로그 신선도)만으로는 "실제로 살아있는지"를 보장하지 못한다 —
# Write-Log 는 쓰기 실패를 -ErrorAction SilentlyContinue + try/catch 로 완전히 삼키므로,
# 로그만 멈추고 워치독/에이전트 자체는 죽어있는(혹은 반대로 로그만 멈추고 실제론 정상인) 경우를
# 구분하지 못한다. Task Scheduler 의 "Running" 표시도 프로세스가 죽었는데 상태만 안 갱신되는
# 경우가 있어 단독으로는 신뢰할 수 없다. 그래서 실제 결과(heartbeat-ok 최신화 + 살아있는 java 프로세스)로
# 직접 검증한다 — 이게 유일하게 "에이전트가 진짜로 정상 동작 중"임을 보장하는 신호다.
function Wait-AgentHealthy {
    param([string]$InstallDir, [datetime]$Since, [int]$TimeoutSec = 300)

    $sinceMs = [DateTimeOffset]::new($Since.ToUniversalTime()).ToUnixTimeMilliseconds()
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastJarProcessAlive = $false
    $lastHeartbeatMs = $null

    while ((Get-Date) -lt $deadline) {
        $jarProcess = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*agent.jar*' } | Select-Object -First 1
        $lastJarProcessAlive = [bool]$jarProcess
        $lastHeartbeatMs = Get-HeartbeatOkEpochMs -InstallDir $InstallDir

        if ($lastJarProcessAlive -and $lastHeartbeatMs -and $lastHeartbeatMs -gt $sinceMs) {
            $ageMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $lastHeartbeatMs
            Write-RepairLog "Verified healthy: java.exe pid=$($jarProcess.ProcessId), heartbeat-ok fresh (age=${ageMs}ms)."
            return $true
        }
        Start-Sleep -Seconds 3
    }

    # 실패 원인 진단에 필요한 정보를 남긴다 — Task Scheduler 상태와 실제 프로세스/heartbeat 상태를 나란히 기록해
    # "스케줄러엔 Running으로 뜨는데 실제론 죽어있는" 케이스를 다음에 바로 구분할 수 있게 한다.
    $taskState = try { (Get-ScheduledTask -TaskName $taskName -ErrorAction Stop).State } catch { "조회 실패: $_" }
    Write-RepairLog "Verify FAILED after ${TimeoutSec}s. TaskState=$taskState javaProcessAlive=$lastJarProcessAlive heartbeatOkEpochMs=$lastHeartbeatMs (기준시각 이후여야 함: $sinceMs)"
    return $false
}

function Export-TaskSummary {
    try {
        schtasks.exe /Query /TN $taskName /FO LIST /V 2>&1 |
            Out-File -LiteralPath (Join-Path $logDir 'watchdog-task-query.txt') -Encoding UTF8
        Export-ScheduledTask -TaskName $taskName |
            Out-File -LiteralPath (Join-Path $logDir 'watchdog-task-export.xml') -Encoding UTF8
    } catch {
        Write-RepairLog "Task summary export warning: $($_.Exception.Message)"
    }
}

try {
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Write-RepairLog 'Repair started.'

    # 로그 폴더 쓰기 불가는 "워치독이 돌아도 아무 기록이 없고 에이전트도 안 뜨는" 장애의 직접 원인이므로
    # 가장 먼저 확인한다. (잠긴 watchdog.log 정리는 잠근 프로세스를 종료한 뒤에 해야 하므로 아래에서 수행)
    Ensure-LogDirWritable | Out-Null

    # 작업 스케줄러 서비스가 죽어 있으면 작업을 등록해도 부팅 시 실행되지 않으므로 먼저 복구한다.
    $schedulerOk = Ensure-TaskSchedulerService

    $installDir = Resolve-InstallDir
    $appDir = Join-Path $installDir 'app'
    Write-RepairLog "InstallDir=$installDir"

    Stop-AndRemove-Task -Path '\' -Name 'PCAgentWatchdog'
    # 자식(\PCAgent\Main)을 부모보다 먼저 정리 — \PCAgent 가 레거시 작업 폴더로 존재하며 Main 이
    # 그 안에 남아있으면, 부모를 먼저 지우려 할 때 "폴더가 비어있지 않음" 오류로 실패한다.
    Stop-AndRemove-Task -Path '\PCAgent\' -Name 'Main'
    Stop-AndRemove-Task -Path '\' -Name 'PCAgent'
    Start-Sleep -Seconds 2
    Stop-PCAgentProcesses -InstallDir $installDir
    Start-Sleep -Seconds 2

    # 잠긴 watchdog.log 정리는 반드시 프로세스 종료 이후에 — 잠금을 쥔 워치독 인스턴스가 살아있는
    # 상태에서는 이름 변경이 실패한다.
    Reset-LockedWatchdogLog

    Ensure-ProgramDataConfig -InstallDir $installDir -AppDir $appDir
    $watchdogScript = Install-WatchdogScript -InstallDir $installDir -AppDir $appDir
    $launcherPath = Resolve-Launcher -InstallDir $installDir

    Register-WatchdogTask -InstallDir $installDir -WatchdogScript $watchdogScript -LauncherPath $launcherPath
    Assert-WatchdogTask
    Export-TaskSummary

    $beforeStart = Get-Date
    Start-ScheduledTask -TaskName $taskName
    Write-RepairLog "Started scheduled task: $taskName"

    # watchdog.log 갱신 여부는 참고용으로만 남긴다(로그 쓰기 자체가 조용히 실패할 수 있어 단독 신뢰 불가).
    # 실제 판정은 Wait-AgentHealthy(heartbeat-ok 신선도 + 살아있는 java 프로세스)로 한다.
    Start-Sleep -Seconds 5
    $watchdogLog = Join-Path $logDir 'watchdog.log'
    if ((Test-Path $watchdogLog) -and ((Get-Item $watchdogLog).LastWriteTime -ge $beforeStart.AddSeconds(-2))) {
        Write-RepairLog '(참고) watchdog.log 갱신 확인됨.'
    } else {
        Write-RepairLog '(참고) watchdog.log 갱신 안 됨 — 로그 쓰기 실패일 수 있어 단독으로는 실패로 판정하지 않음.'
    }

    $healthy = Wait-AgentHealthy -InstallDir $installDir -Since $beforeStart -TimeoutSec 300
    if (-not $healthy) {
        Write-RepairLog 'WARNING: 5분 내 정상 동작(heartbeat-ok 갱신 + java 프로세스) 확인 실패. 작업은 등록됐으나 실제 기동 여부는 재확인 필요.'
    }

    Register-WatchdogTask -InstallDir $installDir -WatchdogScript $watchdogScript -LauncherPath $launcherPath
    Assert-WatchdogTask
    Export-TaskSummary
    Write-RepairLog 'Final scheduled task registration refreshed after agent startup.'

    # 에이전트 복구와 무관하게 항상 수행 (실패해도 에이전트 결과에는 영향 없음)
    $compassSummary = Invoke-CompassAutoUpdateBlock
    $sqlSummary = Invoke-SqlServerNameSync

    if (-not $schedulerOk) {
        # 지금은 수동 실행 덕에 살아있어도, 서비스가 죽어 있으면 재부팅 후 자동 시작이 안 된다.
        Write-RepairLog 'WARNING: Task Scheduler 서비스 문제로 재부팅 후 자동 시작이 보장되지 않습니다. 위 안내를 확인하세요.'
        exit 2
    }
    if ($healthy) {
        Write-RepairLog "Repair completed. Agent verified healthy. | $compassSummary | $sqlSummary"
        exit 0
    } else {
        Write-RepairLog "Repair completed with WARNING: agent health not verified within timeout. | $compassSummary | $sqlSummary"
        exit 2
    }
} catch {
    Write-RepairLog "ERROR: $($_.Exception.Message)"
    exit 1
}

### WATCHDOG_PS1_BASE64 ###
77u/I1JlcXVpcmVzIC1WZXJzaW9uIDUuMQ0KPCMNCi5TWU5PUFNJUw0KICAgIFBDQWdlbnQg7JuM7LmY64+FIC0g7JeQ7J207KCE7Yq466W8IO2VreyDgSDs
grTslYTsnojqsowg7Jyg7KeAICsg66y07KSR64uoIOyXheuNsOydtO2KuC/roaTrsLEg6rSA66asLg0KDQouREVTQ1JJUFRJT04NCiAgICBXaW5kb3dzIOye
keyXhSDsiqTsvIDspITrn6woVGFzayBTY2hlZHVsZXIp7JeQIOydmO2VtCBTWVNURU0g6rOE7KCV7Jy866GcIOu2gO2MhSDsi5wg7J6Q64+ZIOyLpO2WieuQ
qeuLiOuLpC4NCiAgICDsl5DsnbTsoITtirjqsIAg7Lap64+M7ZWY6rGw64KYIOyiheujjOuQmOuptCDsnpDrj5nsnLzroZwg7J6s7Iuc7J6R7ZWp64uI64uk
Lg0KDQogICAg66y07KSR64uoIOyXheuNsOydtO2KuChob3Qtc3dhcCk6DQogICAgICDsl5DsnbTsoITtirjqsIAgQUdFTlRfVVBEQVRFIOyImOyLoCDihpIg
7IOIIGFnZW50LmphcuulvCBzdGFnaW5nICsgdXBkYXRlLXBlbmRpbmcuanNvbiDrp4jsu6Qg6riw66GdIO2bhCBzZWxmLWV4aXQuDQogICAgICDsm4zsuZjr
j4XsnYAg7JeQ7J207KCE7Yq4IOyiheujjOulvCDqsJDsp4DtlZjrqbQgc3RhZ2VkIGphcuulvCDsi6TsoJwg7JyE7LmY66GcIOq1kOyytO2VmOqzoCDsg4gg
67KE7KCE7J2EIOyLpO2Wie2VnOuLpC4NCiAgICAgIOyDiCDrsoTsoITsnbQg66eI6rCQKOq4sOuzuCA5MOy0iCkg64K07JeQIGhlYXJ0YmVhdCDshLHqs7Uo
aGVhcnRiZWF0LW9rIOuniOy7pCDqsLHsi6Ap7J2EIOuztOydtOuptCDsu6TrsIssDQogICAgICDqt7jroIfsp4Ag7JWK7Jy866m0KO2BrOuemOyLnC/rrLTs
nZHri7UpIOyngeyghCDrsoTsoIQocHJldmlvdXMgamFyKeycvOuhnCDsnpDrj5kg66Gk67Cx7ZWc64ukLg0KICAgICAgT1Mg7J6s67aA7YyF7J2AIOyghO2Y
gCDtlYTsmpQg7JeG7Jy866mwLCDsm4zsuZjrj4XsnYAg7JeQ7J207KCE7Yq466W8IOyehOydmOuhnCDsnqzrtoDtjIXtlZjsp4Ag7JWK64qU64ukLg0KDQou
UEFSQU1FVEVSIEluc3RhbGxEaXINCiAgICDsl5DsnbTsoITtirgg7ISk7LmYIO2PtOuNlCDqsr3roZwuIOq4sOuzuOqwkjogQzpcUHJvZ3JhbSBGaWxlc1xQ
Q0FnZW50DQoNCi5QQVJBTUVURVIgTGF1bmNoZXJQYXRoDQogICAg7Iuk7ZaJ7ZWgIOufsOyymCDqsr3roZwuIOq4sOuzuOqwkjogSW5zdGFsbERpclxQQ0Fn
ZW50LmV4ZQ0KIz4NCnBhcmFtKA0KICAgIFtzdHJpbmddJEluc3RhbGxEaXIgPSAiQzpcUHJvZ3JhbSBGaWxlc1xQQ0FnZW50IiwNCiAgICBbc3RyaW5nXSRM
YXVuY2hlclBhdGggPSAiIg0KKQ0KDQokcHJvZ3JhbURhdGFEaXIgPSAiQzpcUHJvZ3JhbURhdGFcUENBZ2VudCINCiRwcm9ncmFtRGF0YUxvZ0RpciA9IEpv
aW4tUGF0aCAkcHJvZ3JhbURhdGFEaXIgImxvZ3MiDQppZiAoIShUZXN0LVBhdGggJHByb2dyYW1EYXRhTG9nRGlyKSkgew0KICAgIE5ldy1JdGVtIC1JdGVt
VHlwZSBEaXJlY3RvcnkgLVBhdGggJHByb2dyYW1EYXRhTG9nRGlyIC1Gb3JjZSAtRXJyb3JBY3Rpb24gU2lsZW50bHlDb250aW51ZSB8IE91dC1OdWxsDQp9
DQokbG9nRmlsZSA9IEpvaW4tUGF0aCAkcHJvZ3JhbURhdGFMb2dEaXIgIndhdGNoZG9nLmxvZyINCiRwZW5kaW5nTWFya2VyID0gSm9pbi1QYXRoICRwcm9n
cmFtRGF0YURpciAidXBkYXRlLXBlbmRpbmcuanNvbiINCiRoZWFydGJlYXRPayAgID0gSm9pbi1QYXRoICRwcm9ncmFtRGF0YURpciAiaGVhcnRiZWF0LW9r
Ig0KDQojIOKUgOKUgCDroZzqt7gg7Jyg7Yu4IOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKU
gOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKU
gOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgA0KIyDsnbTsoIQg6rWs7ZiE7J2AIEFkZC1Db250ZW50IOyLpO2MqOulvCAtRXJyb3JBY3Rpb24g
U2lsZW50bHlDb250aW51ZSArIHRyeS9jYXRjaCDroZwg7JmE7KCE7Z6IIOyCvOy8sOuLpC4NCiMg6re4656Y7IScIOuhnOq3uCDtjIzsnbzsnbQgKOuLpOul
uCDsm4zsuZjrj4Ug7J247Iqk7YS07IqkL+uwseyXhS9BViDrk7Hsl5Ag7J2Y7ZW0KSDsnqDquLDrqbQg7JuM7LmY64+F7J2AIOqzhOyGjSDrj4zrqbTshJzr
j4QNCiMg66Gc6re466W8IO2VnCDspITrj4Qg64Ko6riw7KeAIOuqu+2VtCwgIuyjveydgCDqsoMi6rO8ICLsgrTslYTsnojripTrjbAg66Gc6re466eMIOuq
uyDsk7DripQg6rKDIuydhCDqtazrtoTtlaAg7IiYIOyXhuyXiOuLpC4NCiMg4oaSIOqzteyaqSDroZzqt7jqsIAg66eJ7Z6I66m0IOyduOyKpO2EtOyKpCDs
oITsmqkg66Gc6re4LCDqt7jqsoPrj4Qg66eJ7Z6I66m0IFRFTVAg66GcIOyekOuPmSDtj7TrsLHtlZzri6QuDQokc2NyaXB0OmFjdGl2ZUxvZ0ZpbGUgPSAk
bG9nRmlsZQ0KJHNjcmlwdDpsb2dGYWxsYmFja3MgPSBAKA0KICAgICRsb2dGaWxlLA0KICAgIChKb2luLVBhdGggJHByb2dyYW1EYXRhTG9nRGlyICJ3YXRj
aGRvZy1waWQkUElELmxvZyIpLA0KICAgIChKb2luLVBhdGggJGVudjpURU1QICJwY2FnZW50LXdhdGNoZG9nLXBpZCRQSUQubG9nIikNCikNCg0KZnVuY3Rp
b24gV3JpdGUtTG9nIHsNCiAgICBwYXJhbShbc3RyaW5nXSRNZXNzYWdlKQ0KICAgICRsaW5lID0gIiQoR2V0LURhdGUgLUZvcm1hdCAneXl5eS1NTS1kZCBI
SDptbTpzcycpIFtXYXRjaGRvZ10gJE1lc3NhZ2UiDQogICAgIyDsiJjrj5kg7Iuk7ZaJKOq0gOumrOyekCBQb3dlclNoZWxsIOuTsSkg7IucIO2ZlOuptOyc
vOuhnOuPhCDsp4Tri6jtlaAg7IiYIOyeiOqyjCDtlZzri6QuDQogICAgdHJ5IHsgV3JpdGUtSG9zdCAkbGluZSB9IGNhdGNoIHt9DQoNCiAgICAjIO2YhOye
rCDsgqzsmqkg7KSR7J24IOuMgOyDgeydhCDrqLzsoIAg7Iuc64+E7ZWY6rOgLCDsi6TtjKjtlZjrqbQg64uk7J2MIO2PtOuwseycvOuhnCDsirnqsqntlZzr
i6QuDQogICAgJHRhcmdldHMgPSBAKCRzY3JpcHQ6YWN0aXZlTG9nRmlsZSkgKyAoJHNjcmlwdDpsb2dGYWxsYmFja3MgfCBXaGVyZS1PYmplY3QgeyAkXyAt
bmUgJHNjcmlwdDphY3RpdmVMb2dGaWxlIH0pDQogICAgZm9yZWFjaCAoJHRhcmdldCBpbiAkdGFyZ2V0cykgew0KICAgICAgICBpZiAoLW5vdCAkdGFyZ2V0
KSB7IGNvbnRpbnVlIH0NCiAgICAgICAgdHJ5IHsNCiAgICAgICAgICAgIFtJTy5GaWxlXTo6QXBwZW5kQWxsVGV4dCgkdGFyZ2V0LCAkbGluZSArIFtFbnZp
cm9ubWVudF06Ok5ld0xpbmUsIFtUZXh0LkVuY29kaW5nXTo6VVRGOCkNCiAgICAgICAgICAgIGlmICgkc2NyaXB0OmFjdGl2ZUxvZ0ZpbGUgLW5lICR0YXJn
ZXQpIHsNCiAgICAgICAgICAgICAgICAkc2NyaXB0OmFjdGl2ZUxvZ0ZpbGUgPSAkdGFyZ2V0DQogICAgICAgICAgICAgICAgdHJ5IHsNCiAgICAgICAgICAg
ICAgICAgICAgW0lPLkZpbGVdOjpBcHBlbmRBbGxUZXh0KCR0YXJnZXQsDQogICAgICAgICAgICAgICAgICAgICAgICAiJChHZXQtRGF0ZSAtRm9ybWF0ICd5
eXl5LU1NLWRkIEhIOm1tOnNzJykgW1dhdGNoZG9nXSAo7JWM66a8KSDsnbTsoIQg66Gc6re4IOuMgOyDgeydtCDsnqDqsqgg7J6I7Ja0IOydtCDtjIzsnbzr
oZwg7KCE7ZmY7ZaI7Iq164uI64ukLiQoW0Vudmlyb25tZW50XTo6TmV3TGluZSkiLA0KICAgICAgICAgICAgICAgICAgICAgICAgW1RleHQuRW5jb2Rpbmdd
OjpVVEY4KQ0KICAgICAgICAgICAgICAgIH0gY2F0Y2gge30NCiAgICAgICAgICAgIH0NCiAgICAgICAgICAgIHJldHVybg0KICAgICAgICB9IGNhdGNoIHsg
Y29udGludWUgfQ0KICAgIH0NCiAgICAjIOuqqOuToCDrjIDsg4Eg7Iuk7YyoIOKAlCDtmZTrqbQg7Lac66Cl66eM7Jy866GcIOuCqOq4tOuLpCjsl6zquLDs
hJwg7JiI7Jm466W8IOuNmOyngOuptCDsm4zsuZjrj4Ug66Oo7ZSE6rCAIOyjveuKlOuLpCkuDQp9DQoNCmZ1bmN0aW9uIFRyaW0tTG9nIHsNCiAgICAjIOyd
tOyghCDqtaztmITsnYAgR2V0LUNvbnRlbnQg66GcIOyghOyytOulvCDsnb3qs6AgU2V0LUNvbnRlbnQg66GcIOyghOyytOulvCDri6Tsi5wg7I2o7IScLCDt
irjrprwg64+E7KSRIO2MjOydvOydtA0KICAgICMg67Cw7YOA7KCB7Jy866GcIOyeoOq4sOqzoCDri6Trpbgg7J247Iqk7YS07Iqk7J2YIOuhnOq3uCDquLDr
oZ3snbQg7Iuk7Yyo7ZWY64qUIOybkOyduOydtCDrkJDri6QuDQogICAgIyDsp4DquIjsnYAg7J20IOyduOyKpO2EtOyKpOqwgCDsi6TsoJzroZwg7JOw64qU
IO2MjOydvOunjCwg67Cw7YOAIOygkeq3vOydhCDslrvsnYQg7IiYIOyeiOydhCDrlYzrp4wg7Yq466a87ZWc64ukLg0KICAgICR0YXJnZXQgPSAkc2NyaXB0
OmFjdGl2ZUxvZ0ZpbGUNCiAgICBpZiAoLW5vdCAkdGFyZ2V0IC1vciAtbm90IChUZXN0LVBhdGggJHRhcmdldCkpIHsgcmV0dXJuIH0NCiAgICB0cnkgew0K
ICAgICAgICBpZiAoKEdldC1JdGVtICR0YXJnZXQgLUVycm9yQWN0aW9uIFN0b3ApLkxlbmd0aCAtbHQgMU1CKSB7IHJldHVybiB9DQogICAgfSBjYXRjaCB7
IHJldHVybiB9DQogICAgdHJ5IHsNCiAgICAgICAgJHN0cmVhbSA9IFtJTy5GaWxlXTo6T3BlbigkdGFyZ2V0LCAnT3BlbicsICdSZWFkV3JpdGUnLCAnTm9u
ZScpDQogICAgICAgIHRyeSB7DQogICAgICAgICAgICAkcmVhZGVyID0gTmV3LU9iamVjdCBJTy5TdHJlYW1SZWFkZXIoJHN0cmVhbSwgW1RleHQuRW5jb2Rp
bmddOjpVVEY4KQ0KICAgICAgICAgICAgJGFsbCA9ICRyZWFkZXIuUmVhZFRvRW5kKCkNCiAgICAgICAgICAgICRsaW5lcyA9ICRhbGwgLXNwbGl0ICJgcj9g
biIgfCBXaGVyZS1PYmplY3QgeyAkXyAtbmUgJycgfQ0KICAgICAgICAgICAgaWYgKCRsaW5lcy5Db3VudCAtZ3QgMTAwMCkgew0KICAgICAgICAgICAgICAg
ICRrZXB0ID0gKCRsaW5lcyB8IFNlbGVjdC1PYmplY3QgLUxhc3QgNTAwKSAtam9pbiBbRW52aXJvbm1lbnRdOjpOZXdMaW5lDQogICAgICAgICAgICAgICAg
JHN0cmVhbS5TZXRMZW5ndGgoMCkNCiAgICAgICAgICAgICAgICAkd3JpdGVyID0gTmV3LU9iamVjdCBJTy5TdHJlYW1Xcml0ZXIoJHN0cmVhbSwgW1RleHQu
RW5jb2RpbmddOjpVVEY4KQ0KICAgICAgICAgICAgICAgICR3cml0ZXIuV3JpdGUoJGtlcHQgKyBbRW52aXJvbm1lbnRdOjpOZXdMaW5lKQ0KICAgICAgICAg
ICAgICAgICR3cml0ZXIuRmx1c2goKQ0KICAgICAgICAgICAgfQ0KICAgICAgICB9IGZpbmFsbHkgeyAkc3RyZWFtLkNsb3NlKCkgfQ0KICAgIH0gY2F0Y2gg
ew0KICAgICAgICAjIOyeoOqyqCDsnojsnLzrqbQg7J2067KIIOyjvOq4sOuKlCDqsbTrhIjrm7Tri6Qg4oCUIO2KuOumvCDsi6TtjKjripQg7JuM7LmY64+F
IOuPmeyekeyXkCDsmIHtlqXsnbQg7JeG7Ja07JW8IO2VnOuLpC4NCiAgICB9DQp9DQoNCiMg4pSA4pSAIGphdmEuZXhlIO2DkOyDiSDilIDilIDilIDilIDi
lIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDi
lIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIDilIANCmZ1bmN0aW9uIEZpbmQtSmF2YSB7
DQogICAgaWYgKCRlbnY6SkFWQV9IT01FIC1hbmQgKFRlc3QtUGF0aCAiJGVudjpKQVZBX0hPTUVcYmluXGphdmEuZXhlIikpIHsNCiAgICAgICAgcmV0dXJu
ICIkZW52OkpBVkFfSE9NRVxiaW5camF2YS5leGUiDQogICAgfQ0KICAgICRqYXZhQ21kID0gR2V0LUNvbW1hbmQgImphdmEiIC1FcnJvckFjdGlvbiBTaWxl
bnRseUNvbnRpbnVlDQogICAgaWYgKCRqYXZhQ21kKSB7IHJldHVybiAkamF2YUNtZC5Tb3VyY2UgfQ0KICAgICRzZWFyY2hSb290cyA9IEAoDQogICAgICAg
ICJDOlxQcm9ncmFtIEZpbGVzXEphdmEiLA0KICAgICAgICAiQzpcUHJvZ3JhbSBGaWxlc1xFY2xpcHNlIEFkb3B0aXVtIiwNCiAgICAgICAgIkM6XFByb2dy
YW0gRmlsZXNcTWljcm9zb2Z0IiwNCiAgICAgICAgIkM6XFByb2dyYW0gRmlsZXNcQmVsbFNvZnQiLA0KICAgICAgICAiQzpcUHJvZ3JhbSBGaWxlc1xBenVs
IFN5c3RlbXMiLA0KICAgICAgICAiQzpcUHJvZ3JhbSBGaWxlc1xBbWF6b24gQ29ycmV0dG8iDQogICAgKQ0KICAgIGZvcmVhY2ggKCRyb290IGluICRzZWFy
Y2hSb290cykgew0KICAgICAgICBpZiAoLW5vdCAoVGVzdC1QYXRoICRyb290KSkgeyBjb250aW51ZSB9DQogICAgICAgICRqZGtEaXJzID0gR2V0LUNoaWxk
SXRlbSAtUGF0aCAkcm9vdCAtRGlyZWN0b3J5IC1FcnJvckFjdGlvbiBTaWxlbnRseUNvbnRpbnVlDQogICAgICAgIGZvcmVhY2ggKCRkaXIgaW4gJGpka0Rp
cnMpIHsNCiAgICAgICAgICAgICRjYW5kaWRhdGUgPSBKb2luLVBhdGggJGRpci5GdWxsTmFtZSAiYmluXGphdmEuZXhlIg0KICAgICAgICAgICAgaWYgKFRl
c3QtUGF0aCAkY2FuZGlkYXRlKSB7IHJldHVybiAkY2FuZGlkYXRlIH0NCiAgICAgICAgfQ0KICAgIH0NCiAgICByZXR1cm4gJG51bGwNCn0NCg0KZnVuY3Rp
b24gUmVzb2x2ZS1KYXIgew0KICAgIHBhcmFtKFtzdHJpbmddJEluc3RhbGxEaXIpDQogICAgJGNhbmRpZGF0ZXMgPSBAKA0KICAgICAgICAoSm9pbi1QYXRo
ICRJbnN0YWxsRGlyICJhcHBcYWdlbnQuamFyIiksDQogICAgICAgIChKb2luLVBhdGggJEluc3RhbGxEaXIgImFnZW50LmphciIpDQogICAgKQ0KICAgIGZv
cmVhY2ggKCRjYW5kaWRhdGUgaW4gJGNhbmRpZGF0ZXMpIHsNCiAgICAgICAgaWYgKFRlc3QtUGF0aCAkY2FuZGlkYXRlKSB7IHJldHVybiAkY2FuZGlkYXRl
IH0NCiAgICB9DQogICAgcmV0dXJuICRudWxsDQp9DQoNCmZ1bmN0aW9uIFJlc29sdmUtQ29uZmlnRGlyIHsNCiAgICBwYXJhbShbc3RyaW5nXSRSZXNvbHZl
ZEphcikNCiAgICAkamFyRGlyID0gU3BsaXQtUGF0aCAkUmVzb2x2ZWRKYXIgLVBhcmVudA0KICAgICRwcm9ncmFtRGF0YUNvbmZpZyA9IEpvaW4tUGF0aCAk
cHJvZ3JhbURhdGFEaXIgImNvbmZpZy5qc29uIg0KICAgICRqYXJDb25maWcgPSBKb2luLVBhdGggJGphckRpciAiY29uZmlnLmpzb24iDQoNCiAgICBpZiAo
KC1ub3QgKFRlc3QtUGF0aCAkcHJvZ3JhbURhdGFDb25maWcpKSAtYW5kIChUZXN0LVBhdGggJGphckNvbmZpZykpIHsNCiAgICAgICAgdHJ5IHsNCiAgICAg
ICAgICAgIENvcHktSXRlbSAtTGl0ZXJhbFBhdGggJGphckNvbmZpZyAtRGVzdGluYXRpb24gJHByb2dyYW1EYXRhQ29uZmlnIC1Gb3JjZSAtRXJyb3JBY3Rp
b24gU3RvcA0KICAgICAgICAgICAgV3JpdGUtTG9nICJQcm9ncmFtRGF0YSBjb25maWcuanNvbiDstIjquLDtmZQ6ICRqYXJDb25maWcgLT4gJHByb2dyYW1E
YXRhQ29uZmlnIg0KICAgICAgICB9IGNhdGNoIHsNCiAgICAgICAgICAgIFdyaXRlLUxvZyAiUHJvZ3JhbURhdGEgY29uZmlnLmpzb24g7LSI6riw7ZmUIOyL
pO2MqDogJF8iDQogICAgICAgIH0NCiAgICB9DQoNCiAgICBpZiAoVGVzdC1QYXRoICRwcm9ncmFtRGF0YUNvbmZpZykgew0KICAgICAgICByZXR1cm4gJHBy
b2dyYW1EYXRhRGlyDQogICAgfQ0KICAgIGlmIChUZXN0LVBhdGggJGphckNvbmZpZykgew0KICAgICAgICByZXR1cm4gJGphckRpcg0KICAgIH0NCiAgICBy
ZXR1cm4gJHByb2dyYW1EYXRhRGlyDQp9DQoNCmZ1bmN0aW9uIFF1b3RlLVByb2Nlc3NBcmd1bWVudCB7DQogICAgcGFyYW0oW3N0cmluZ10kVmFsdWUpDQog
ICAgcmV0dXJuICciJyArICgkVmFsdWUgLXJlcGxhY2UgJyInLCAnXCInKSArICciJw0KfQ0KDQpmdW5jdGlvbiBUcmltLUFnZW50T3V0cHV0TG9ncyB7DQog
ICAgdHJ5IHsNCiAgICAgICAgaWYgKC1ub3QgKFRlc3QtUGF0aCAkcHJvZ3JhbURhdGFMb2dEaXIpKSB7IHJldHVybiB9DQogICAgICAgIEdldC1DaGlsZEl0
ZW0gLVBhdGggJHByb2dyYW1EYXRhTG9nRGlyIC1GaWx0ZXIgImFnZW50LXN0ZCoubG9nIiAtRXJyb3JBY3Rpb24gU2lsZW50bHlDb250aW51ZSB8DQogICAg
ICAgICAgICBTb3J0LU9iamVjdCBMYXN0V3JpdGVUaW1lIC1EZXNjZW5kaW5nIHwNCiAgICAgICAgICAgIFNlbGVjdC1PYmplY3QgLVNraXAgMjAgfA0KICAg
ICAgICAgICAgUmVtb3ZlLUl0ZW0gLUZvcmNlIC1FcnJvckFjdGlvbiBTaWxlbnRseUNvbnRpbnVlDQogICAgfSBjYXRjaCB7fQ0KfQ0KDQojIOKUgOKUgCDr
rLTspJHri6gg7JeF642w7J207Yq4IOuniOy7pCDsnKDti7gg4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA
4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA4pSA
DQpmdW5jdGlvbiBHZXQtUGVuZGluZyB7DQogICAgaWYgKC1ub3QgKFRlc3QtUGF0aCAkcGVuZGluZ01hcmtlcikpIHsgcmV0dXJuICRudWxsIH0NCiAgICB0
cnkgew0KICAgICAgICAkanNvbiA9IEdldC1Db250ZW50ICRwZW5kaW5nTWFya2VyIC1SYXcgLUVycm9yQWN0aW9uIFN0b3ANCiAgICAgICAgaWYgKC1ub3Qg
JGpzb24gLW9yICRqc29uLlRyaW0oKSAtZXEgIiIpIHsgcmV0dXJuICRudWxsIH0NCiAgICAgICAgcmV0dXJuICgkanNvbiB8IENvbnZlcnRGcm9tLUpzb24p
DQogICAgfSBjYXRjaCB7DQogICAgICAgIFdyaXRlLUxvZyAidXBkYXRlLXBlbmRpbmcuanNvbiDtjIzsi7Eg7Iuk7YyoOiAkXyINCiAgICAgICAgcmV0dXJu
ICRudWxsDQogICAgfQ0KfQ0KDQpmdW5jdGlvbiBTYXZlLVBlbmRpbmcgew0KICAgIHBhcmFtKCRvYmopDQogICAgdHJ5IHsNCiAgICAgICAgKCRvYmogfCBD
b252ZXJ0VG8tSnNvbiAtQ29tcHJlc3MpIHwgU2V0LUNvbnRlbnQgLUxpdGVyYWxQYXRoICRwZW5kaW5nTWFya2VyIC1FbmNvZGluZyBVVEY4IC1FcnJvckFj
dGlvbiBTdG9wDQogICAgfSBjYXRjaCB7DQogICAgICAgIFdyaXRlLUxvZyAidXBkYXRlLXBlbmRpbmcuanNvbiDsoIDsnqUg7Iuk7YyoOiAkXyINCiAgICB9
DQp9DQoNCmZ1bmN0aW9uIFJlbW92ZS1QZW5kaW5nIHsNCiAgICB0cnkgeyBSZW1vdmUtSXRlbSAtTGl0ZXJhbFBhdGggJHBlbmRpbmdNYXJrZXIgLUZvcmNl
IC1FcnJvckFjdGlvbiBTaWxlbnRseUNvbnRpbnVlIH0gY2F0Y2gge30NCn0NCg0KZnVuY3Rpb24gTm93LVVuaXhNcyB7DQogICAgcmV0dXJuIFtEYXRlVGlt
ZU9mZnNldF06OlV0Y05vdy5Ub1VuaXhUaW1lTWlsbGlzZWNvbmRzKCkNCn0NCg0KZnVuY3Rpb24gR2V0LUhlYXJ0YmVhdEFnZU1zIHsNCiAgICBpZiAoLW5v
dCAoVGVzdC1QYXRoICRoZWFydGJlYXRPaykpIHsgcmV0dXJuICRudWxsIH0NCiAgICB0cnkgew0KICAgICAgICAkY29udGVudCA9IEdldC1Db250ZW50ICRo
ZWFydGJlYXRPayAtUmF3IC1FcnJvckFjdGlvbiBTdG9wDQogICAgICAgIGlmICgtbm90ICRjb250ZW50KSB7IHJldHVybiAkbnVsbCB9DQogICAgICAgICRo
ZWFydGJlYXRNcyA9IFtsb25nXTANCiAgICAgICAgaWYgKFtsb25nXTo6VHJ5UGFyc2UoJGNvbnRlbnQuVHJpbSgpLCBbcmVmXSRoZWFydGJlYXRNcykpIHsN
CiAgICAgICAgICAgIHJldHVybiAoTm93LVVuaXhNcykgLSAkaGVhcnRiZWF0TXMNCiAgICAgICAgfQ0KICAgIH0gY2F0Y2gge30NCiAgICByZXR1cm4gJG51
bGwNCn0NCg0KIyDsl5DsnbTsoITtirgg7KKF66OMIO2bhCwgc3RhZ2VkIGphcuulvCDsi6TsoJwg7JyE7LmY66GcIOq1kOyytO2VnOuLpCAoYXBwbGllZD10
cnVlIO2RnOyLnCkuIOyEseqztSDsi5wgJHRydWUuDQpmdW5jdGlvbiBBcHBseS1TdGFnZWQgew0KICAgIHBhcmFtKCRwZW5kaW5nKQ0KICAgIHRyeSB7DQog
ICAgICAgIGlmICgtbm90IChUZXN0LVBhdGggJHBlbmRpbmcuc3RhZ2VkSmFyKSkgew0KICAgICAgICAgICAgV3JpdGUtTG9nICLqsr3qs6A6IHN0YWdlZCBq
YXIg7JeG7J2MICgkKCRwZW5kaW5nLnN0YWdlZEphcikpIOKAlCDsl4XrjbDsnbTtirgg7Leo7IaMLCDquLDsobQg67KE7KCEIOycoOyngCINCiAgICAgICAg
ICAgIFJlbW92ZS1QZW5kaW5nDQogICAgICAgICAgICByZXR1cm4gJGZhbHNlDQogICAgICAgIH0NCiAgICAgICAgQ29weS1JdGVtIC1MaXRlcmFsUGF0aCAk
cGVuZGluZy5zdGFnZWRKYXIgLURlc3RpbmF0aW9uICRwZW5kaW5nLnRhcmdldEphciAtRm9yY2UgLUVycm9yQWN0aW9uIFN0b3ANCiAgICAgICAgV3JpdGUt
TG9nICLsg4ggamFyIOyggeyaqTogLT4gJCgkcGVuZGluZy50YXJnZXRKYXIpICh2JCgkcGVuZGluZy50YXJnZXRWZXJzaW9uKSkiDQogICAgICAgICRwZW5k
aW5nLmFwcGxpZWQgPSAkdHJ1ZQ0KICAgICAgICAkcGVuZGluZy52ZXJpZnlEZWFkbGluZUVwb2NoTXMgPSAoTm93LVVuaXhNcykgKyA5MDAwMA0KICAgICAg
ICBTYXZlLVBlbmRpbmcgJHBlbmRpbmcNCiAgICAgICAgcmV0dXJuICR0cnVlDQogICAgfSBjYXRjaCB7DQogICAgICAgIFdyaXRlLUxvZyAiamFyIOq1kOyy
tCDsi6TtjKg6ICRfIOKAlCDsl4XrjbDsnbTtirgg7Leo7IaMLCDquLDsobQg67KE7KCEIOycoOyngCINCiAgICAgICAgUmVtb3ZlLVBlbmRpbmcNCiAgICAg
ICAgcmV0dXJuICRmYWxzZQ0KICAgIH0NCn0NCg0KIyDsg4gg67KE7KCEIOygleyDgSDrj5nsnpEoaGVhcnRiZWF0IOyEseqztSkg7ZmV7KCVIOyLnCBzdGFn
aW5nL3ByZXZpb3VzL+uniOy7pCDsoJXrpqwuDQpmdW5jdGlvbiBDb21taXQtVXBkYXRlIHsNCiAgICBwYXJhbSgkcGVuZGluZykNCiAgICBXcml0ZS1Mb2cg
IuyDiCDrsoTsoIQg7Luk67CLICh2JCgkcGVuZGluZy50YXJnZXRWZXJzaW9uKSkuIOyXheuNsOydtO2KuCDshLHqs7UuIg0KICAgIHRyeSB7IFJlbW92ZS1J
dGVtIC1MaXRlcmFsUGF0aCAkcGVuZGluZy5wcmV2aW91c0phciAtRm9yY2UgLUVycm9yQWN0aW9uIFNpbGVudGx5Q29udGludWUgfSBjYXRjaCB7fQ0KICAg
IHRyeSB7IFJlbW92ZS1JdGVtIC1MaXRlcmFsUGF0aCAkcGVuZGluZy5zdGFnZWRKYXIgICAtRm9yY2UgLUVycm9yQWN0aW9uIFNpbGVudGx5Q29udGludWUg
fSBjYXRjaCB7fQ0KICAgIFJlbW92ZS1QZW5kaW5nDQp9DQoNCiMg7IOIIOuyhOyghCDsi6TtjKgg7IucIOyngeyghCDrsoTsoIQocHJldmlvdXMgamFyKeyc
vOuhnCDrkJjrj4zrprDri6QuDQpmdW5jdGlvbiBSb2xsYmFjay1VcGRhdGUgew0KICAgIHBhcmFtKCRwZW5kaW5nKQ0KICAgIHRyeSB7DQogICAgICAgIGlm
IChUZXN0LVBhdGggJHBlbmRpbmcucHJldmlvdXNKYXIpIHsNCiAgICAgICAgICAgIENvcHktSXRlbSAtTGl0ZXJhbFBhdGggJHBlbmRpbmcucHJldmlvdXNK
YXIgLURlc3RpbmF0aW9uICRwZW5kaW5nLnRhcmdldEphciAtRm9yY2UgLUVycm9yQWN0aW9uIFN0b3ANCiAgICAgICAgICAgIFdyaXRlLUxvZyAi66Gk67Cx
IOyZhOujjDog7KeB7KCEIOuyhOyghCBqYXIg67O17JuQIC0+ICQoJHBlbmRpbmcudGFyZ2V0SmFyKSINCiAgICAgICAgfSBlbHNlIHsNCiAgICAgICAgICAg
IFdyaXRlLUxvZyAi6rK96rOgOiBwcmV2aW91cyBqYXIg7JeG7J2MICgkKCRwZW5kaW5nLnByZXZpb3VzSmFyKSkg4oCUIOuhpOuwsSDrtojqsIAsIO2YhOye
rCBqYXIg7Jyg7KeAIg0KICAgICAgICB9DQogICAgfSBjYXRjaCB7DQogICAgICAgIFdyaXRlLUxvZyAi66Gk67CxIOykkSDsmKTrpZg6ICRfIg0KICAgIH0N
CiAgICB0cnkgeyBSZW1vdmUtSXRlbSAtTGl0ZXJhbFBhdGggJHBlbmRpbmcuc3RhZ2VkSmFyIC1Gb3JjZSAtRXJyb3JBY3Rpb24gU2lsZW50bHlDb250aW51
ZSB9IGNhdGNoIHt9DQogICAgUmVtb3ZlLVBlbmRpbmcNCn0NCg0KIyDsl5DsnbTsoITtirgoYWdlbnQuamFyKeulvCBqYXZh66GcIOyngeygkSDsi6Ttlont
lZjqs6AgUHJvY2VzcyDqsJ3ssrTrpbwg67CY7ZmY7ZWc64ukICjsi6TtjKgg7IucICRudWxsKS4NCiMgUENBZ2VudC5leGUo65+w7LKYKeulvCDqsbDsuZjs
p4Ag7JWK64qU64ukIOKAlCDrn7DsspjripQg7J6Q7IudIGphdmHqsIAg7KO97Ja064+EIOyCtOyVhOyeiOyWtCDsm4zsuZjrj4XsnbQNCiMg7JuM7LukIOyi
heujjOulvCDqsJDsp4DtlZjsp4Ag66q77ZWY6rOgIOyigOu5hCDrn7DsspjqsIAg64iE7KCB65CY6riwIOuVjOusuC4gamF2YeulvCDsp4HsoJEg6rCQ7Iuc
7ZWc64ukLg0KZnVuY3Rpb24gU3RhcnQtQWdlbnQgew0KICAgIHBhcmFtKFtzdHJpbmddJEluc3RhbGxEaXIsIFtzdHJpbmddJExhdW5jaGVyUGF0aCkNCiAg
ICAkcmVzb2x2ZWRKYXIgPSBSZXNvbHZlLUphciAtSW5zdGFsbERpciAkSW5zdGFsbERpcg0KICAgIGlmICgtbm90ICRyZXNvbHZlZEphcikgew0KICAgICAg
ICBXcml0ZS1Mb2cgIuyYpOulmDogYWdlbnQuamFy66W8IOywvuydhCDsiJgg7JeG7Iq164uI64ukLiINCiAgICAgICAgcmV0dXJuICRudWxsDQogICAgfQ0K
ICAgICMg67KI65OkIEpSRSDtg5Dsg4kgKGpwYWNrYWdlPXJ1bnRpbWVcLCDsiJjrj5nshKTsuZg9anJlXCksIOyXhuycvOuptCDsi5zsiqTthZwgamF2YQ0K
ICAgICRqYXZhRXhlID0gJG51bGwNCiAgICBmb3JlYWNoICgkY2FuZCBpbiBAKChKb2luLVBhdGggJEluc3RhbGxEaXIgInJ1bnRpbWVcYmluXGphdmEuZXhl
IiksDQogICAgICAgICAgICAgICAgICAgICAgICAoSm9pbi1QYXRoICRJbnN0YWxsRGlyICJqcmVcYmluXGphdmEuZXhlIikpKSB7DQogICAgICAgIGlmIChU
ZXN0LVBhdGggJGNhbmQpIHsgJGphdmFFeGUgPSAkY2FuZDsgYnJlYWsgfQ0KICAgIH0NCiAgICBpZiAoLW5vdCAkamF2YUV4ZSkgeyAkamF2YUV4ZSA9IEZp
bmQtSmF2YSB9DQogICAgaWYgKC1ub3QgJGphdmFFeGUpIHsNCiAgICAgICAgV3JpdGUtTG9nICLsmKTrpZg6IGphdmEuZXhl66W8IOywvuydhCDsiJgg7JeG
7Iq164uI64ukLiDrsojrk6QgSlJFIOuYkOuKlCBKREsgMTcrIO2VhOyalC4iDQogICAgICAgIHJldHVybiAkbnVsbA0KICAgIH0NCiAgICAjIOyEpOy5mO2Y
lSDsl5DsnbTsoITtirjripQgUHJvZ3JhbURhdGHsnZggY29uZmlnLmpzb24gLyBoZWFydGJlYXQtb2vrpbwg6riw7KSA7Jy866GcIOuPmeyeke2VnOuLpC4N
CiAgICAkY29uZmlnRGlyID0gUmVzb2x2ZS1Db25maWdEaXIgLVJlc29sdmVkSmFyICRyZXNvbHZlZEphcg0KICAgICRydW5TdGFtcCA9IEdldC1EYXRlIC1G
b3JtYXQgInl5eXlNTWRkLUhIbW1zcy1mZmYiDQogICAgVHJpbS1BZ2VudE91dHB1dExvZ3MNCiAgICAkamF2YUFyZ3MgPSAiLVhteDEyOG0gLURwY2FnZW50
LmNvbmZpZy5kaXI9JChRdW90ZS1Qcm9jZXNzQXJndW1lbnQgJGNvbmZpZ0RpcikgLWphciAkKFF1b3RlLVByb2Nlc3NBcmd1bWVudCAkcmVzb2x2ZWRKYXIp
Ig0KDQogICAgIyBzdGRvdXQvc3RkZXJyIOumrOuLpOydtOugie2KuOuKlCAi7J6I7Jy866m0IOyii+ydgCIg7KeE64uoIOyImOuLqOydvCDrv5DsnbTri6Qu
IOq3uOufsOuNsCBTdGFydC1Qcm9jZXNzIOuKlCDrpqzri6TsnbTroIntirgNCiAgICAjIOuMgOyDgSDtjIzsnbzsnYQg66eM65Ok7KeAIOuqu+2VmOuptCDs
mIjsmbjrpbwg642Y7KeA66+A66GcLCDroZzqt7gg7Y+0642U6rCAIOyTsOq4sCDrtojqsIAg7IOB7YOcKEJTT0Qg7ZuEIO2MjOydvOyLnOyKpO2FnC/qtozt
lZwg7IaQ7IOBLA0KICAgICMgQVYg7ZWE7YSwIOydtOyDgSDrk7Ep7J2066m0IOyXkOydtOyghO2KuOqwgCDslYTsmIgg6riw64+Z65CY7KeAIOyViuuKlOuL
pC4g7Iuk7KCc66GcICLsm4zsuZjrj4XsnYQg7IiY64+ZIOyLpO2Wie2VtOuPhCDsl5DsnbTsoITtirjqsIANCiAgICAjIOyViCDrnKjripTrjbAgUENBZ2Vu
dC5leGUg66W8IOyngeygkSDriITrpbTrqbQg65yo64qUIig9IOumrOuLpOydtOugie2KuOulvCDsk7Dsp4Ag7JWK64qUIOqyveuhnOunjCDrj5nsnpEpIOye
peyVoOqwgCDsnojsl4jri6QuDQogICAgIyDihpIg66as64uk7J2066CJ7Yq4IOqwgOuKpSDsl6zrtoDrpbwg66i87KCAIO2ZleyduO2VmOqzoCwg67aI6rCA
64ql7ZWY66m0IOumrOuLpOydtOugie2KuCDsl4bsnbTrnbzrj4Qg67CY65Oc7IucIOudhOyatOuLpC4NCiAgICAkc3Rkb3V0RmlsZSA9IEpvaW4tUGF0aCAk
cHJvZ3JhbURhdGFMb2dEaXIgImFnZW50LXN0ZG91dC0kcnVuU3RhbXAubG9nIg0KICAgICRzdGRlcnJGaWxlID0gSm9pbi1QYXRoICRwcm9ncmFtRGF0YUxv
Z0RpciAiYWdlbnQtc3RkZXJyLSRydW5TdGFtcC5sb2ciDQogICAgJGNhblJlZGlyZWN0ID0gJGZhbHNlDQogICAgdHJ5IHsNCiAgICAgICAgaWYgKC1ub3Qg
KFRlc3QtUGF0aCAkcHJvZ3JhbURhdGFMb2dEaXIpKSB7DQogICAgICAgICAgICBOZXctSXRlbSAtSXRlbVR5cGUgRGlyZWN0b3J5IC1QYXRoICRwcm9ncmFt
RGF0YUxvZ0RpciAtRm9yY2UgLUVycm9yQWN0aW9uIFN0b3AgfCBPdXQtTnVsbA0KICAgICAgICB9DQogICAgICAgICMg7Iuk7KCc66GcIOunjOuTpOyWtCDr
tJDslbwg6raM7ZWcL+2VhO2EsCDrrLjsoJzrpbwg7ZmV7J247ZWgIOyImCDsnojri6QuDQogICAgICAgIFtJTy5GaWxlXTo6V3JpdGVBbGxUZXh0KCRzdGRv
dXRGaWxlLCAnJywgW1RleHQuRW5jb2RpbmddOjpVVEY4KQ0KICAgICAgICBbSU8uRmlsZV06OldyaXRlQWxsVGV4dCgkc3RkZXJyRmlsZSwgJycsIFtUZXh0
LkVuY29kaW5nXTo6VVRGOCkNCiAgICAgICAgJGNhblJlZGlyZWN0ID0gJHRydWUNCiAgICB9IGNhdGNoIHsNCiAgICAgICAgV3JpdGUtTG9nICLqsr3qs6A6
IOyXkOydtOyghO2KuCDstpzroKUg66Gc6re466W8IOunjOuTpCDsiJgg7JeG7Iq164uI64ukICgkKCRfLkV4Y2VwdGlvbi5NZXNzYWdlKSkuIOumrOuLpOyd
tOugie2KuCDsl4bsnbQg6riw64+Z7ZWp64uI64ukLiINCiAgICB9DQoNCiAgICBpZiAoJGNhblJlZGlyZWN0KSB7DQogICAgICAgIFdyaXRlLUxvZyAiYWdl
bnQuamFyIOyngeygkSDsi6TtlokuLi4gKGphdmE9JGphdmFFeGUsIGNvbmZpZz0kY29uZmlnRGlyLCBzdGRvdXQ9JHN0ZG91dEZpbGUsIHN0ZGVycj0kc3Rk
ZXJyRmlsZSkiDQogICAgICAgIHRyeSB7DQogICAgICAgICAgICByZXR1cm4gU3RhcnQtUHJvY2VzcyAtRmlsZVBhdGggJGphdmFFeGUgLUFyZ3VtZW50TGlz
dCAkamF2YUFyZ3MgLVdvcmtpbmdEaXJlY3RvcnkgJGNvbmZpZ0RpciBgDQogICAgICAgICAgICAgICAgLVdpbmRvd1N0eWxlIEhpZGRlbiAtUmVkaXJlY3RT
dGFuZGFyZE91dHB1dCAkc3Rkb3V0RmlsZSAtUmVkaXJlY3RTdGFuZGFyZEVycm9yICRzdGRlcnJGaWxlIGANCiAgICAgICAgICAgICAgICAtUGFzc1RocnUg
LUVycm9yQWN0aW9uIFN0b3ANCiAgICAgICAgfSBjYXRjaCB7DQogICAgICAgICAgICBXcml0ZS1Mb2cgIuumrOuLpOydtOugie2KuCDquLDrj5kg7Iuk7Yyo
ICgkKCRfLkV4Y2VwdGlvbi5NZXNzYWdlKSkg4oaSIOumrOuLpOydtOugie2KuCDsl4bsnbQg7J6s7Iuc64+E7ZWp64uI64ukLiINCiAgICAgICAgfQ0KICAg
IH0NCg0KICAgIFdyaXRlLUxvZyAiYWdlbnQuamFyIOyngeygkSDsi6Ttloko7Lac66ClIOumrOuLpOydtOugie2KuCDsl4bsnYwpLi4uIChqYXZhPSRqYXZh
RXhlLCBjb25maWc9JGNvbmZpZ0RpcikiDQogICAgcmV0dXJuIFN0YXJ0LVByb2Nlc3MgLUZpbGVQYXRoICRqYXZhRXhlIC1Bcmd1bWVudExpc3QgJGphdmFB
cmdzIC1Xb3JraW5nRGlyZWN0b3J5ICRjb25maWdEaXIgYA0KICAgICAgICAtV2luZG93U3R5bGUgSGlkZGVuIC1QYXNzVGhydSAtRXJyb3JBY3Rpb24gU3Rv
cA0KfQ0KDQojIOyDiCDrsoTsoIQg6rKA7KadOiDrp4jqsJDquYzsp4AgaGVhcnRiZWF0LW9rIOqwseyLoOydhCDqsJDsi5wuIOyEseqztSDsu6TrsIsg7Iuc
ICR0cnVlLCDsi6TtjKgo66Gk67CxIO2VhOyalCkg7IucICRmYWxzZS4NCmZ1bmN0aW9uIFZlcmlmeS1OZXdWZXJzaW9uIHsNCiAgICBwYXJhbSgkcHJvYywg
JHBlbmRpbmcpDQogICAgJGNyZWF0ZWRNcyA9IFtsb25nXSRwZW5kaW5nLmNyZWF0ZWRFcG9jaE1zDQogICAgV3JpdGUtTG9nICLsg4gg67KE7KCEIOqygOym
nSDsi5zsnpEgKOuniOqwkOq5jOyngCBoZWFydGJlYXQg7ISx6rO1IOuMgOq4sCkuLi4iDQogICAgd2hpbGUgKChOb3ctVW5peE1zKSAtbHQgW2xvbmddJHBl
bmRpbmcudmVyaWZ5RGVhZGxpbmVFcG9jaE1zKSB7DQogICAgICAgIGlmICgkcHJvYy5IYXNFeGl0ZWQpIHsNCiAgICAgICAgICAgIFdyaXRlLUxvZyAi6rKA
7KadIOykkSDsg4gg67KE7KCEIO2UhOuhnOyEuOyKpCDsooXro4zrkKggKEV4aXRDb2RlPSQoJHByb2MuRXhpdENvZGUpKSDihpIg66Gk67CxIg0KICAgICAg
ICAgICAgcmV0dXJuICRmYWxzZQ0KICAgICAgICB9DQogICAgICAgIGlmIChUZXN0LVBhdGggJGhlYXJ0YmVhdE9rKSB7DQogICAgICAgICAgICAkYyA9IEdl
dC1Db250ZW50ICRoZWFydGJlYXRPayAtUmF3IC1FcnJvckFjdGlvbiBTaWxlbnRseUNvbnRpbnVlDQogICAgICAgICAgICBpZiAoJGMpIHsNCiAgICAgICAg
ICAgICAgICAkb2tNcyA9IFtsb25nXTANCiAgICAgICAgICAgICAgICBpZiAoW2xvbmddOjpUcnlQYXJzZSgkYy5UcmltKCksIFtyZWZdJG9rTXMpKSB7DQog
ICAgICAgICAgICAgICAgICAgIGlmICgkb2tNcyAtZ3QgJGNyZWF0ZWRNcykgew0KICAgICAgICAgICAgICAgICAgICAgICAgQ29tbWl0LVVwZGF0ZSAkcGVu
ZGluZw0KICAgICAgICAgICAgICAgICAgICAgICAgcmV0dXJuICR0cnVlDQogICAgICAgICAgICAgICAgICAgIH0NCiAgICAgICAgICAgICAgICB9DQogICAg
ICAgICAgICB9DQogICAgICAgIH0NCiAgICAgICAgU3RhcnQtU2xlZXAgLVNlY29uZHMgMw0KICAgIH0NCiAgICBXcml0ZS1Mb2cgIuqygOymnSDrp4jqsJAg
7LSI6rO8IChoZWFydGJlYXQg7ISx6rO1IOyXhuydjCkg4oaSIOuhpOuwsSINCiAgICByZXR1cm4gJGZhbHNlDQp9DQoNCiMg4pSA4pSAIOuplOyduCDsm4zs
uZjrj4Ug66Oo7ZSEIOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKU
gOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgOKUgA0KV3JpdGUt
TG9nICI9PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09Ig0KV3JpdGUtTG9nICJQQ0FnZW50
IOybjOy5mOuPhSDsi5zsnpEuIEluc3RhbGxEaXI9JEluc3RhbGxEaXIgTGF1bmNoZXJQYXRoPSRMYXVuY2hlclBhdGgiDQpXcml0ZS1Mb2cgIj09PT09PT09
PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0iDQoNCiMgaGVhcnRiZWF0LW9rIOqyveuhnOulvCDsl5Ds
nbTsoITtirgg7Iuk7KCcIOq4sOuhnSDsnITsuZgoY29uZmlnLmRpcinroZwg66ee7LaY64ukLg0KIyAo7JeQ7J207KCE7Yq464qUIGNvbmZpZ1BhdGguZ2V0
UGFyZW50KClcaGVhcnRiZWF0LW9rIOyXkCDquLDroZ3tlZzri6QpDQokamFyRm9ySGIgPSBSZXNvbHZlLUphciAtSW5zdGFsbERpciAkSW5zdGFsbERpcg0K
aWYgKCRqYXJGb3JIYikgew0KICAgICRoZWFydGJlYXRPayA9IEpvaW4tUGF0aCAoUmVzb2x2ZS1Db25maWdEaXIgLVJlc29sdmVkSmFyICRqYXJGb3JIYikg
ImhlYXJ0YmVhdC1vayINCiAgICBXcml0ZS1Mb2cgImhlYXJ0YmVhdC1vayDqsJDsi5wg6rK966GcOiAkaGVhcnRiZWF0T2siDQp9DQoNCiRjb25zZWN1dGl2
ZUZhaWxzID0gMA0KDQp3aGlsZSAoJHRydWUpIHsNCiAgICBUcmltLUxvZw0KDQogICAgdHJ5IHsNCiAgICAgICAgIyAxKSDrs7TrpZgg7KSR7J24IOyXheuN
sOydtO2KuOqwgCDsnojqs6Ag7JWE7KeBIOq1kOyytCDsoITsnbTrqbQo7JeQ7J207KCE7Yq4IOyiheujjCDsg4Htg5wpIGphciDqtZDssrQNCiAgICAgICAg
JHBlbmRpbmcgPSBHZXQtUGVuZGluZw0KICAgICAgICBpZiAoJHBlbmRpbmcgLWFuZCAtbm90ICRwZW5kaW5nLmFwcGxpZWQpIHsNCiAgICAgICAgICAgIEFw
cGx5LVN0YWdlZCAkcGVuZGluZyB8IE91dC1OdWxsDQogICAgICAgICAgICAkcGVuZGluZyA9IEdldC1QZW5kaW5nICAjIGFwcGxpZWQvZGVhZGxpbmUg6rCx
7IugIOuwmOyYgQ0KICAgICAgICB9DQoNCiAgICAgICAgIyBJZiBhbm90aGVyIGhlYWx0aHkgYWdlbnQgaW5zdGFuY2UgaXMgYWxyZWFkeSBhbGl2ZSwgZG8g
bm90IGtlZXAgc3Bhd25pbmcNCiAgICAgICAgIyBzaG9ydC1saXZlZCBkdXBsaWNhdGVzIGp1c3QgdG8gaGl0IHRoZSBzaW5nbGUtaW5zdGFuY2UgbG9jay4N
CiAgICAgICAgaWYgKC1ub3QgJHBlbmRpbmcpIHsNCiAgICAgICAgICAgICRoZWFydGJlYXRBZ2VNcyA9IEdldC1IZWFydGJlYXRBZ2VNcw0KICAgICAgICAg
ICAgaWYgKCRudWxsIC1uZSAkaGVhcnRiZWF0QWdlTXMgLWFuZCAkaGVhcnRiZWF0QWdlTXMgLWx0IDMwMDAwKSB7DQogICAgICAgICAgICAgICAgV3JpdGUt
TG9nICLquLDsobQg7JeQ7J207KCE7Yq4IGhlYXJ0YmVhdCDtmZXsnbggKCR7aGVhcnRiZWF0QWdlTXN9bXMg7KCEKSDihpIgMzDstIgg7ZuEIOyerO2Zleyd
uC4uLiINCiAgICAgICAgICAgICAgICAkY29uc2VjdXRpdmVGYWlscyA9IDANCiAgICAgICAgICAgICAgICBTdGFydC1TbGVlcCAtU2Vjb25kcyAzMA0KICAg
ICAgICAgICAgICAgIGNvbnRpbnVlDQogICAgICAgICAgICB9DQogICAgICAgIH0NCg0KICAgICAgICAjIDIpIOyXkOydtOyghO2KuCDsi6TtlokNCiAgICAg
ICAgJHByb2MgPSBTdGFydC1BZ2VudCAtSW5zdGFsbERpciAkSW5zdGFsbERpciAtTGF1bmNoZXJQYXRoICRMYXVuY2hlclBhdGgNCiAgICAgICAgaWYgKC1u
b3QgJHByb2MpIHsNCiAgICAgICAgICAgICRjb25zZWN1dGl2ZUZhaWxzKysNCiAgICAgICAgICAgICRkZWxheSA9IFtNYXRoXTo6TWluKDUgKiBbTWF0aF06
OlBvdygyLCAoJGNvbnNlY3V0aXZlRmFpbHMgLSAxKSksIDYwKQ0KICAgICAgICAgICAgV3JpdGUtTG9nICIkY29uc2VjdXRpdmVGYWlscyDrsogg7Jew7IaN
IOyLpO2WiSDsi6TtjKguICR7ZGVsYXl97LSIIO2bhCDsnqzsi5zrj4QuLi4iDQogICAgICAgICAgICBTdGFydC1TbGVlcCAtU2Vjb25kcyAkZGVsYXkNCiAg
ICAgICAgICAgIGNvbnRpbnVlDQogICAgICAgIH0NCiAgICAgICAgV3JpdGUtTG9nICLsl5DsnbTsoITtirgg7Iuc7J6R65CoIChQSUQ9JCgkcHJvYy5JZCkp
LiINCiAgICAgICAgJGNvbnNlY3V0aXZlRmFpbHMgPSAwDQoNCiAgICAgICAgIyAzKSDrsKnquIgg7JeF642w7J207Yq466W8IOyggeyaqe2WiOuLpOuptCDs
g4gg67KE7KCEIOqygOymnSAo7Iuk7YyoIOyLnCDroaTrsLEpDQogICAgICAgIGlmICgkcGVuZGluZyAtYW5kICRwZW5kaW5nLmFwcGxpZWQpIHsNCiAgICAg
ICAgICAgICRjb21taXR0ZWQgPSBWZXJpZnktTmV3VmVyc2lvbiAtcHJvYyAkcHJvYyAtcGVuZGluZyAkcGVuZGluZw0KICAgICAgICAgICAgaWYgKC1ub3Qg
JGNvbW1pdHRlZCkgew0KICAgICAgICAgICAgICAgIGlmICgtbm90ICRwcm9jLkhhc0V4aXRlZCkgew0KICAgICAgICAgICAgICAgICAgICB0cnkgeyAkcHJv
Yy5LaWxsKCkgfSBjYXRjaCB7fQ0KICAgICAgICAgICAgICAgICAgICB0cnkgeyAkcHJvYy5XYWl0Rm9yRXhpdCgxMDAwMCkgfCBPdXQtTnVsbCB9IGNhdGNo
IHt9DQogICAgICAgICAgICAgICAgfQ0KICAgICAgICAgICAgICAgIFJvbGxiYWNrLVVwZGF0ZSAkcGVuZGluZw0KICAgICAgICAgICAgICAgICMg64uk7J2M
IOujqO2UhOyXkOyEnCDrs7Xsm5DrkJwg7KeB7KCEIOuyhOyghOycvOuhnCDsnqzsi5zsnpENCiAgICAgICAgICAgICAgICBTdGFydC1TbGVlcCAtU2Vjb25k
cyAzDQogICAgICAgICAgICAgICAgY29udGludWUNCiAgICAgICAgICAgIH0NCiAgICAgICAgICAgICMg7Luk67CL65CoIOKGkiDsoJXsg4Eg7Jq07JiB7Jy8
66GcIOydtOyWtOynkA0KICAgICAgICB9DQoNCiAgICAgICAgIyA0KSDsoJXsg4Eg7Jq07JiBOiDsl5DsnbTsoITtirgg7KKF66OM6rmM7KeAIOuMgOq4sCDt
m4Qg7J6s7Iuc7J6RDQogICAgICAgICRzdGFydFRpbWUgPSBHZXQtRGF0ZQ0KICAgICAgICAkcHJvYy5XYWl0Rm9yRXhpdCgpDQogICAgICAgICRlbGFwc2Vk
ID0gKEdldC1EYXRlKSAtICRzdGFydFRpbWUNCiAgICAgICAgV3JpdGUtTG9nICLsl5DsnbTsoITtirgg7KKF66OM65CoIChFeGl0Q29kZT0kKCRwcm9jLkV4
aXRDb2RlKSwgcnVudGltZT0kKFtpbnRdJGVsYXBzZWQuVG90YWxTZWNvbmRzKXMpLiDsnqzsi5zsnpEg7KSA67mELi4uIg0KDQogICAgICAgICMgZmFzdC1l
eGl0IOqwkOyngDogNey0iCDrr7jrp4wg7KKF66OMICsg7LWc6re8IGhlYXJ0YmVhdCDihpIg7JeQ7J207KCE7Yq4IOydtOuvuCDsi6Ttlokg7KSRICjspJHr
s7Ug6riw64+ZIOuwqeyngCkNCiAgICAgICAgaWYgKCRlbGFwc2VkLlRvdGFsU2Vjb25kcyAtbHQgNSkgew0KICAgICAgICAgICAgaWYgKFRlc3QtUGF0aCAk
aGVhcnRiZWF0T2spIHsNCiAgICAgICAgICAgICAgICAkaGJDb250ZW50ID0gR2V0LUNvbnRlbnQgJGhlYXJ0YmVhdE9rIC1SYXcgLUVycm9yQWN0aW9uIFNp
bGVudGx5Q29udGludWUNCiAgICAgICAgICAgICAgICAkaGJNcyA9IFtsb25nXTANCiAgICAgICAgICAgICAgICBpZiAoJGhiQ29udGVudCAtYW5kIFtsb25n
XTo6VHJ5UGFyc2UoJGhiQ29udGVudC5UcmltKCksIFtyZWZdJGhiTXMpKSB7DQogICAgICAgICAgICAgICAgICAgICRhZ2VNcyA9IChOb3ctVW5peE1zKSAt
ICRoYk1zDQogICAgICAgICAgICAgICAgICAgIGlmICgkYWdlTXMgLWx0IDMwMDAwKSB7DQogICAgICAgICAgICAgICAgICAgICAgICBXcml0ZS1Mb2cgIuyX
kOydtOyghO2KuCDsnbTrr7gg7Iuk7ZaJIOykkSAoaGVhcnRiZWF0ICR7YWdlTXN9bXMg7KCEKSDihpIg7KSR67O1IOq4sOuPmSDrsKnsp4AuIDMw7LSIIO2b
hCDsnqztmZXsnbguLi4iDQogICAgICAgICAgICAgICAgICAgICAgICAkY29uc2VjdXRpdmVGYWlscyA9IDANCiAgICAgICAgICAgICAgICAgICAgICAgIFN0
YXJ0LVNsZWVwIC1TZWNvbmRzIDMwDQogICAgICAgICAgICAgICAgICAgICAgICBjb250aW51ZQ0KICAgICAgICAgICAgICAgICAgICB9DQogICAgICAgICAg
ICAgICAgfQ0KICAgICAgICAgICAgfQ0KICAgICAgICB9DQoNCiAgICB9IGNhdGNoIHsNCiAgICAgICAgV3JpdGUtTG9nICLsm4zsuZjrj4Ug66Oo7ZSEIOyY
pOulmDogJF8iDQogICAgICAgICRjb25zZWN1dGl2ZUZhaWxzKysNCiAgICB9DQoNCiAgICBpZiAoJGNvbnNlY3V0aXZlRmFpbHMgLWd0IDApIHsNCiAgICAg
ICAgJGRlbGF5ID0gW01hdGhdOjpNaW4oNSAqIFtNYXRoXTo6UG93KDIsICgkY29uc2VjdXRpdmVGYWlscyAtIDEpKSwgNjApDQogICAgICAgIFdyaXRlLUxv
ZyAiJGNvbnNlY3V0aXZlRmFpbHMg67KIIOyXsOyGjSDsi6TtjKguICR7ZGVsYXl97LSIIO2bhCDsnqzsi5zsnpEuLi4iDQogICAgICAgIFN0YXJ0LVNsZWVw
IC1TZWNvbmRzICRkZWxheQ0KICAgIH0gZWxzZSB7DQogICAgICAgIFN0YXJ0LVNsZWVwIC1TZWNvbmRzIDUNCiAgICB9DQp9DQo=
