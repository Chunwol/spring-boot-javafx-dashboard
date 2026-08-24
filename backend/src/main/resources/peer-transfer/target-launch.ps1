$ErrorActionPreference = 'Stop'
$jobId = '__JOB_ID__'
$sourceIp = '__SOURCE_IP__'
$port = __PORT__
$token = '__TOKEN__'
$targetPath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__TARGET_PATH_B64__'))
$overwriteExisting = [bool]::Parse('__OVERWRITE__')
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\target"
$statusPath = Join-Path $workDir 'status.json'
$workerPath = Join-Path $workDir 'worker.ps1'
$pidPath = Join-Path $workDir 'worker.pid'
$cancelPath = Join-Path $workDir 'cancel.requested'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
Remove-Item -LiteralPath $cancelPath -Force -ErrorAction SilentlyContinue

if (Test-Path -LiteralPath $statusPath) {
    try {
        $existing = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
        if ($existing.state -eq 'completed') { $existing | ConvertTo-Json -Compress; exit 0 }
    } catch {}
}
if (Test-Path -LiteralPath $pidPath) {
    $existingPid = [int](Get-Content -LiteralPath $pidPath -Raw -ErrorAction SilentlyContinue)
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        [ordered]@{ marker='PEER_TRANSFER_TARGET'; jobId=$jobId; state='running'; workerPid=$existingPid } | ConvertTo-Json -Compress
        exit 0
    }
}

$worker = @'
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$jobId = '__JOB_ID__'
$sourceIp = '__SOURCE_IP__'
$port = __PORT__
$token = '__TOKEN__'
$targetPath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__TARGET_PATH_B64__'))
$overwriteExisting = [bool]::Parse('__OVERWRITE__')
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\target"
$statusPath = Join-Path $workDir 'status.json'
$manifestPath = Join-Path $workDir 'manifest.json'
$stageDir = Join-Path $workDir 'stage'
$cancelPath = Join-Path $workDir 'cancel.requested'
$baseUrl = "http://$sourceIp`:$port/$token"

function Write-State([string]$State, [hashtable]$Extra = @{}) {
    $row = [ordered]@{ marker='PEER_TRANSFER_TARGET_STATUS'; jobId=$jobId; pcName=$env:COMPUTERNAME; state=$State; updatedAt=(Get-Date).ToString('o') }
    foreach ($key in $Extra.Keys) { $row[$key] = $Extra[$key] }
    $tmp = "$statusPath.tmp"
    $row | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $statusPath -Force
}

try {
    Write-State 'waiting-source' @{ targetPath=$targetPath; verifiedFiles=0; verifiedBytes=[long]0 }
    $deadline = (Get-Date).AddMinutes(45)
    do {
        if (Test-Path -LiteralPath $cancelPath) { throw 'Transfer cancelled.' }
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/manifest" -OutFile $manifestPath -TimeoutSec 30
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            break
        } catch {
            if ((Get-Date) -ge $deadline) { throw "Source manifest timeout: $($_.Exception.Message)" }
            Start-Sleep -Seconds 5
        }
    } while ($true)

    $rootKind = [string]$manifest.rootKind
    $files = @($manifest.files)
    $targetRoot = if ($rootKind -eq 'directory') { [IO.Path]::GetFullPath($targetPath).TrimEnd('\') } else { [IO.Path]::GetFullPath((Split-Path -Parent $targetPath)) }
    if ($rootKind -eq 'directory') { New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null }
    else { New-Item -ItemType Directory -Path (Split-Path -Parent $targetPath) -Force | Out-Null }
    foreach ($relativeDirectory in @($manifest.directories)) {
        $directoryPath = [IO.Path]::GetFullPath((Join-Path $targetRoot ([string]$relativeDirectory).Replace('/','\')))
        if (-not $directoryPath.StartsWith(($targetRoot + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Directory path escaped the target root.' }
        New-Item -ItemType Directory -Path $directoryPath -Force | Out-Null
    }

    $driveRoot = [IO.Path]::GetPathRoot($targetRoot)
    $drive = Get-PSDrive -Name $driveRoot.Substring(0,1)
    if ([long]$drive.Free -lt [long]$manifest.totalBytes) { throw "Insufficient disk space: required=$($manifest.totalBytes), free=$($drive.Free)" }
    New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

    $verifiedFiles = 0
    $verifiedBytes = [long]0
    foreach ($entry in $files) {
        if (Test-Path -LiteralPath $cancelPath) { throw 'Transfer cancelled.' }
        $relative = [string]$entry.path
        $destination = if ($rootKind -eq 'file') { [IO.Path]::GetFullPath($targetPath) } else { [IO.Path]::GetFullPath((Join-Path $targetRoot $relative.Replace('/','\'))) }
        if ($rootKind -eq 'directory' -and -not $destination.StartsWith(($targetRoot + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'File path escaped the target root.' }
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null

        if (Test-Path -LiteralPath $destination) {
            $existing = Get-Item -LiteralPath $destination -Force
            if ($existing.PSIsContainer) { throw "Destination is a directory: $destination" }
            if ([long]$existing.Length -eq [long]$entry.size) {
                $existingHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($existingHash -eq [string]$entry.sha256) {
                    $verifiedFiles += 1; $verifiedBytes += [long]$entry.size
                    Write-State 'running' @{ targetPath=$targetPath; totalFiles=$manifest.totalFiles; totalBytes=$manifest.totalBytes; verifiedFiles=$verifiedFiles; verifiedBytes=$verifiedBytes }
                    continue
                }
            }
            if (-not $overwriteExisting) { throw "Destination exists with different content: $destination" }
        }

        $partial = Join-Path $stageDir (([Guid]::NewGuid().ToString('N')) + '.partial')
        Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/file?path=$([uri]::EscapeDataString($relative))" -OutFile $partial -TimeoutSec 3600
        $downloaded = Get-Item -LiteralPath $partial
        if ([long]$downloaded.Length -ne [long]$entry.size) { throw "Size mismatch: $relative" }
        $actualHash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne [string]$entry.sha256) { throw "SHA-256 mismatch: $relative" }
        Move-Item -LiteralPath $partial -Destination $destination -Force
        $verifiedFiles += 1
        $verifiedBytes += [long]$entry.size
        Write-State 'running' @{ targetPath=$targetPath; totalFiles=$manifest.totalFiles; totalBytes=$manifest.totalBytes; verifiedFiles=$verifiedFiles; verifiedBytes=$verifiedBytes }
    }
    Write-State 'completed' @{ targetPath=$targetPath; totalFiles=$manifest.totalFiles; totalBytes=$manifest.totalBytes; verifiedFiles=$verifiedFiles; verifiedBytes=$verifiedBytes }
} catch {
    $current = $null
    try { $current = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json } catch {}
    Write-State 'failed' @{ targetPath=$targetPath; verifiedFiles=if($current){[long]$current.verifiedFiles}else{0}; verifiedBytes=if($current){[long]$current.verifiedBytes}else{0}; error=$_.Exception.Message }
    exit 1
}
'@
Set-Content -LiteralPath $workerPath -Value $worker -Encoding UTF8
[ordered]@{ marker='PEER_TRANSFER_TARGET_STATUS'; jobId=$jobId; pcName=$env:COMPUTERNAME; state='starting'; targetPath=$targetPath; verifiedFiles=0; verifiedBytes=[long]0; updatedAt=(Get-Date).ToString('o') } | ConvertTo-Json -Compress | Set-Content -LiteralPath $statusPath -Encoding UTF8
$process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$workerPath`"") -WindowStyle Hidden -PassThru
$process.Id | Set-Content -LiteralPath $pidPath -Encoding ASCII
Start-Sleep -Seconds 1
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) { throw 'Target worker stopped during startup.' }
[ordered]@{ marker='PEER_TRANSFER_TARGET'; jobId=$jobId; state='started'; workerPid=$process.Id } | ConvertTo-Json -Compress
