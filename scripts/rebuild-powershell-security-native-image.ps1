$ErrorActionPreference = 'Stop'

$ngen = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\ngen.exe'
$assembly = Join-Path $env:WINDIR (
    'Microsoft.NET\assembly\GAC_MSIL\Microsoft.PowerShell.Security\' +
    'v4.0_3.0.0.0__31bf3856ad364e35\Microsoft.PowerShell.Security.dll')

if (-not (Test-Path -LiteralPath $ngen -PathType Leaf)) {
    throw "ngen.exe not found: $ngen"
}
if (-not (Test-Path -LiteralPath $assembly -PathType Leaf)) {
    throw "PowerShell Security assembly not found: $assembly"
}

function Invoke-Ngen([string[]]$arguments) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = [Diagnostics.ProcessStartInfo]@{
        FileName = $ngen
        Arguments = ($arguments -join ' ')
        UseShellExecute = $false
        CreateNoWindow = $true
        RedirectStandardOutput = $true
        RedirectStandardError = $true
    }
    if (-not $process.Start()) { throw 'Failed to start ngen.exe.' }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    [pscustomobject]@{
        arguments = $process.StartInfo.Arguments
        exitCode = $process.ExitCode
        stdout = $stdout.Trim()
        stderr = $stderr.Trim()
    }
}

$uninstall = Invoke-Ngen @('uninstall', 'Microsoft.PowerShell.Security', '/nologo')
$install = Invoke-Ngen @('install', ('"{0}"' -f $assembly), '/nologo')
if ($install.exitCode -ne 0) {
    throw "ngen install failed: exit=$($install.exitCode) stderr=$($install.stderr)"
}

$test = [Diagnostics.Process]::new()
$test.StartInfo = [Diagnostics.ProcessStartInfo]@{
    FileName = (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe')
    Arguments = '-NoProfile -NonInteractive -Command "Import-Module Microsoft.PowerShell.Security -Force -ErrorAction Stop; (Get-Command Get-AuthenticodeSignature).Source"'
    UseShellExecute = $false
    CreateNoWindow = $true
    RedirectStandardOutput = $true
    RedirectStandardError = $true
}
if (-not $test.Start()) { throw 'Failed to start PowerShell verification process.' }
$testOut = $test.StandardOutput.ReadToEnd()
$testErr = $test.StandardError.ReadToEnd()
$test.WaitForExit()
if ($test.ExitCode -ne 0) {
    throw "PowerShell Security verification failed: exit=$($test.ExitCode) stderr=$($testErr.Trim())"
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    nativeImageRebuilt = $true
    uninstallExitCode = $uninstall.exitCode
    installExitCode = $install.exitCode
    verificationExitCode = $test.ExitCode
    verificationOutput = $testOut.Trim()
    uninstallOutput = $uninstall.stdout
    installOutput = $install.stdout
} | ConvertTo-Json -Depth 5 -Compress
