$ErrorActionPreference = 'Stop'

$process = [Diagnostics.Process]::new()
$process.StartInfo = [Diagnostics.ProcessStartInfo]@{
    FileName = 'chkdsk.exe'
    Arguments = 'C: /spotfix'
    UseShellExecute = $false
    CreateNoWindow = $true
    RedirectStandardInput = $true
    RedirectStandardOutput = $true
    RedirectStandardError = $true
}

if (-not $process.Start()) {
    throw 'Failed to start chkdsk.'
}
$process.StandardInput.WriteLine('Y')
$process.StandardInput.Close()
$stdout = $process.StandardOutput.ReadToEnd()
$stderr = $process.StandardError.ReadToEnd()
$process.WaitForExit()

$bootExecute = @((Get-ItemProperty `
    'HKLM:/SYSTEM/CurrentControlSet/Control/Session Manager' `
    -Name BootExecute -ErrorAction Stop).BootExecute)
$scheduled = ($bootExecute -join "`n") -match '(?i)autochk.*\\\?\?\\C:'
if (-not $scheduled) {
    throw "C: spot repair was not scheduled. exitCode=$($process.ExitCode) stdout=$stdout stderr=$stderr"
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    exitCode = $process.ExitCode
    repairScheduled = $scheduled
    bootExecute = $bootExecute
    stdout = $stdout.Trim()
    stderr = $stderr.Trim()
} | ConvertTo-Json -Depth 4 -Compress
