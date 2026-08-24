$ErrorActionPreference = 'Stop'
$jobId = '__JOB_ID__'
$port = __PORT__
$token = '__TOKEN__'
$sourcePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__SOURCE_PATH_B64__'))
$targetAddresses = @('__TARGET_IPS_CSV__'.Split(',') | Where-Object { $_ })
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\source"
$statusPath = Join-Path $workDir 'status.json'
$workerPath = Join-Path $workDir 'worker.ps1'
$pidPath = Join-Path $workDir 'worker.pid'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

if (Test-Path -LiteralPath $pidPath) {
    $existingPid = [int](Get-Content -LiteralPath $pidPath -Raw -ErrorAction SilentlyContinue)
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        [ordered]@{ marker='PEER_TRANSFER_SOURCE'; jobId=$jobId; state='running'; workerPid=$existingPid } | ConvertTo-Json -Compress
        exit 0
    }
}

$worker = @'
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$jobId = '__JOB_ID__'
$port = __PORT__
$token = '__TOKEN__'
$sourcePath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__SOURCE_PATH_B64__'))
$targetAddresses = @('__TARGET_IPS_CSV__'.Split(',') | Where-Object { $_ })
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\source"
$statusPath = Join-Path $workDir 'status.json'
$manifestPath = Join-Path $workDir 'manifest.json'
$ruleName = "PCAgent Peer Transfer $jobId"

function Write-State([string]$State, [hashtable]$Extra = @{}) {
    $row = [ordered]@{ marker='PEER_TRANSFER_SOURCE_STATUS'; jobId=$jobId; state=$State; updatedAt=(Get-Date).ToString('o') }
    foreach ($key in $Extra.Keys) { $row[$key] = $Extra[$key] }
    $tmp = "$statusPath.tmp"
    $row | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $statusPath -Force
}

try {
    Write-State 'preparing' @{ sourcePath=$sourcePath }
    if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Source path not found: $sourcePath" }
    $sourceItem = Get-Item -LiteralPath $sourcePath -Force
    if ($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Source root cannot be a reparse point.' }
    $rootKind = if ($sourceItem.PSIsContainer) { 'directory' } else { 'file' }
    $basePath = if ($sourceItem.PSIsContainer) { $sourceItem.FullName } else { $sourceItem.DirectoryName }
    $files = if ($sourceItem.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $sourceItem.FullName -File -Force -Recurse | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) })
    } else { @($sourceItem) }
    $directories = if ($sourceItem.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $sourceItem.FullName -Directory -Force -Recurse | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } | ForEach-Object {
            $_.FullName.Substring($basePath.Length).TrimStart('\').Replace('\','/')
        })
    } else { @() }
    $entries = @()
    $totalBytes = [long]0
    foreach ($file in $files) {
        $relative = if ($rootKind -eq 'file') { $file.Name } else { $file.FullName.Substring($basePath.Length).TrimStart('\').Replace('\','/') }
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $entries += [ordered]@{ path=$relative; size=[long]$file.Length; sha256=$hash }
        $totalBytes += [long]$file.Length
        Write-State 'preparing' @{ sourcePath=$sourcePath; hashedFiles=$entries.Count; totalFiles=$files.Count; totalBytes=$totalBytes }
    }
    $manifest = [ordered]@{
        jobId=$jobId; rootKind=$rootKind; rootName=$sourceItem.Name; totalFiles=$entries.Count;
        totalBytes=$totalBytes; directories=$directories; files=$entries
    }
    $manifest | ConvertTo-Json -Depth 8 -Compress | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $byRelative = @{}
    foreach ($entry in $entries) { $byRelative[[string]$entry.path] = $entry }

    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    $firewall = @{ DisplayName=$ruleName; Direction='Inbound'; Action='Allow'; Protocol='TCP'; LocalPort=$port; Profile='Any' }
    if ($targetAddresses.Count) { $firewall.RemoteAddress = $targetAddresses }
    New-NetFirewallRule @firewall | Out-Null

    $listener = [Net.HttpListener]::new()
    $listener.Prefixes.Add("http://+:$port/$token/")
    $listener.Start()
    Write-State 'ready' @{ sourcePath=$sourcePath; rootKind=$rootKind; totalFiles=$entries.Count; totalBytes=$totalBytes; port=$port }

    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            $route = $context.Request.Url.AbsolutePath.Substring(("/$token/").Length)
            if ($route -eq 'health') {
                $bytes = [Text.Encoding]::UTF8.GetBytes('ok')
                $context.Response.ContentType = 'text/plain; charset=utf-8'
                $context.Response.ContentLength64 = $bytes.Length
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            } elseif ($route -eq 'manifest') {
                $bytes = [IO.File]::ReadAllBytes($manifestPath)
                $context.Response.ContentType = 'application/json; charset=utf-8'
                $context.Response.ContentLength64 = $bytes.Length
                $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            } elseif ($route -eq 'file') {
                $relative = [string]$context.Request.QueryString['path']
                if (-not $byRelative.ContainsKey($relative)) { $context.Response.StatusCode = 404 }
                else {
                    $fullPath = if ($rootKind -eq 'file') { $sourceItem.FullName } else { Join-Path $basePath ($relative.Replace('/','\')) }
                    $resolved = [IO.Path]::GetFullPath($fullPath)
                    if ($rootKind -eq 'directory' -and -not $resolved.StartsWith(([IO.Path]::GetFullPath($basePath).TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
                        throw 'Resolved source path escaped the source root.'
                    }
                    $stream = [IO.File]::Open($resolved, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
                    try {
                        $context.Response.ContentType = 'application/octet-stream'
                        $context.Response.ContentLength64 = $stream.Length
                        $stream.CopyTo($context.Response.OutputStream, 1048576)
                    } finally { $stream.Dispose() }
                }
            } else { $context.Response.StatusCode = 404 }
        } catch {
            $context.Response.StatusCode = 500
        } finally {
            try { $context.Response.OutputStream.Close() } catch {}
            try { $context.Response.Close() } catch {}
        }
    }
} catch {
    Write-State 'failed' @{ sourcePath=$sourcePath; error=$_.Exception.Message }
    exit 1
}
'@
Set-Content -LiteralPath $workerPath -Value $worker -Encoding UTF8
[ordered]@{ marker='PEER_TRANSFER_SOURCE_STATUS'; jobId=$jobId; state='starting'; sourcePath=$sourcePath; updatedAt=(Get-Date).ToString('o') } | ConvertTo-Json -Compress | Set-Content -LiteralPath $statusPath -Encoding UTF8
$process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$workerPath`"") -WindowStyle Hidden -PassThru
$process.Id | Set-Content -LiteralPath $pidPath -Encoding ASCII
Start-Sleep -Seconds 1
if (-not (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) { throw 'Source worker stopped during startup.' }
[ordered]@{ marker='PEER_TRANSFER_SOURCE'; jobId=$jobId; state='started'; workerPid=$process.Id } | ConvertTo-Json -Compress
