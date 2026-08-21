$ErrorActionPreference = 'Stop'

$exe = 'C:/Program Files/SCMATE_RX2Client/Shield/ShdCmd.exe'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw 'ShdCmd.exe not found.'
}

function Invoke-ReadOnlyQuery([string] $Argument) {
    $stdoutPath = Join-Path $env:TEMP ('shdcmd-query-' + [guid]::NewGuid().ToString('N') + '.out')
    $stderrPath = Join-Path $env:TEMP ('shdcmd-query-' + [guid]::NewGuid().ToString('N') + '.err')
    try {
        $process = Start-Process -FilePath $exe -ArgumentList $Argument -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $timedOut = -not $process.WaitForExit(5000)
        if ($timedOut) {
            $process.Kill()
        } else {
            $process.WaitForExit()
            $process.Refresh()
        }
        [ordered]@{
            argument = $Argument
            timedOut = $timedOut
            exitCode = if ($timedOut) { $null } else { $process.ExitCode }
            stdout = [string](Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue)
            stderr = [string](Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue)
        }
    } finally {
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    queries = @(
        Invoke-ReadOnlyQuery '/status'
        Invoke-ReadOnlyQuery '/List'
        Invoke-ReadOnlyQuery '/ListTask'
    )
} | ConvertTo-Json -Depth 5 -Compress
