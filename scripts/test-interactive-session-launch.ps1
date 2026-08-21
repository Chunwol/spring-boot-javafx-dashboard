$ErrorActionPreference = 'Stop'

$taskName = "PCAgent-InteractiveProbe-$([Guid]::NewGuid().ToString('N'))"
$outputPath = Join-Path $env:WINDIR "Temp\$taskName.json"
$probePath = Join-Path $env:WINDIR "Temp\$taskName.ps1"
$probe = @"
`$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
`$session = [Diagnostics.Process]::GetCurrentProcess().SessionId
[ordered]@{ identity = `$identity; sessionId = `$session } |
    ConvertTo-Json -Compress | Set-Content -LiteralPath '$outputPath' -Encoding UTF8
"@
$probe | Set-Content -LiteralPath $probePath -Encoding UTF8
$taskCommand = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -File `"$probePath`""

try {
    & schtasks.exe /Create /TN $taskName /TR $taskCommand /SC ONCE /ST 00:00 /RU INTERACTIVE /IT /F | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "interactive task create failed: $LASTEXITCODE" }
    & schtasks.exe /Run /TN $taskName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "interactive task start failed: $LASTEXITCODE" }

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (-not (Test-Path -LiteralPath $outputPath) -and [DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $outputPath)) { throw 'interactive task result timed out' }
    Get-Content -LiteralPath $outputPath -Raw
} finally {
    & schtasks.exe /Delete /TN $taskName /F 2>$null | Out-Null
    Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $probePath -Force -ErrorAction SilentlyContinue
}
