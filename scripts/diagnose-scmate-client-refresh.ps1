$ErrorActionPreference = 'Stop'

$taskName = "PCAgent-SCMateRefresh-$([Guid]::NewGuid().ToString('N'))"
$target = 'C:\Program Files\SCMATE_RX2Client\SCMateClient.exe'
$outputPath = Join-Path $env:WINDIR "Temp\$taskName.json"
$runnerPath = Join-Path $env:WINDIR "Temp\$taskName.ps1"
$runner = @"
`$ErrorActionPreference = 'Stop'
`$target = '$target'
try {
    `$before = @(Get-CimInstance Win32_Process -Filter "Name='SCMateClient.exe'" |
        Where-Object { `$_.ExecutablePath -eq `$target } |
        Select-Object ProcessId, SessionId, ExecutablePath)
    foreach (`$process in `$before) {
        Stop-Process -Id `$process.ProcessId -Force -ErrorAction Stop
    }
    Start-Sleep -Milliseconds 1000
    `$started = Start-Process -FilePath `$target -WorkingDirectory (Split-Path -Parent `$target) -PassThru
    Start-Sleep -Milliseconds 1000
    [ordered]@{
        success = `$true
        identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
        before = `$before
        startedPid = `$started.Id
        running = `$null -ne (Get-Process -Id `$started.Id -ErrorAction SilentlyContinue)
    } | ConvertTo-Json -Depth 4 -Compress | Set-Content -LiteralPath '$outputPath' -Encoding UTF8
} catch {
    [ordered]@{
        success = `$false
        identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        sessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
        errorType = `$_.Exception.GetType().FullName
        error = `$_.Exception.Message
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath '$outputPath' -Encoding UTF8
}
"@
$runner | Set-Content -LiteralPath $runnerPath -Encoding UTF8
$taskCommand = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -File `"$runnerPath`""

try {
    & schtasks.exe /Create /TN $taskName /TR $taskCommand /SC ONCE /ST 00:00 /RU INTERACTIVE /IT /RL HIGHEST /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "interactive task create failed: $LASTEXITCODE" }
    & schtasks.exe /Run /TN $taskName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "interactive task start failed: $LASTEXITCODE" }

    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not (Test-Path -LiteralPath $outputPath) -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $outputPath)) { throw 'interactive task result timed out' }
    Get-Content -LiteralPath $outputPath -Raw
} finally {
    & schtasks.exe /End /TN $taskName 2>$null | Out-Null
    & schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null
    Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $runnerPath -Force -ErrorAction SilentlyContinue
}
