$ErrorActionPreference = 'Stop'

$exe = 'C:/Program Files/SCMATE_RX2Client/Shield/ShdCmd.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw 'ShdCmd.exe not found.'
}

$stdoutPath = Join-Path $env:TEMP ('shdcmd-help-' + [guid]::NewGuid().ToString('N') + '.out')
$stderrPath = Join-Path $env:TEMP ('shdcmd-help-' + [guid]::NewGuid().ToString('N') + '.err')
try {
    $process = Start-Process -FilePath $exe -ArgumentList '/?' -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    if (-not $process.WaitForExit(5000)) {
        $process.Kill()
        $timedOut = $true
    } else {
        $timedOut = $false
    }
    [ordered]@{
        computerName = $env:COMPUTERNAME
        timedOut = $timedOut
        exitCode = if ($timedOut) { $null } else { $process.ExitCode }
        stdout = (Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue)
        stderr = (Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue)
    } | ConvertTo-Json -Compress
} finally {
    Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
}
