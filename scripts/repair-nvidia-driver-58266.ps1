$ErrorActionPreference = 'Stop'

$url = 'https://us.download.nvidia.com/Windows/582.66/582.66-desktop-win10-win11-64bit-international-dch-whql.exe'
$expectedLength = 912109016L
$root = Join-Path $env:windir 'Temp\PCAgentDiagnostics\nvidia-58266'
$installerPath = Join-Path $root 'nvidia-582.66.exe'
$partialPath = $installerPath + '.part'
$statePath = Join-Path $root 'repair.state'
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Path $root, $logDir -Force | Out-Null

function Write-State([string]$Status, [string]$Detail = '') {
    @(
        'status=' + $Status
        'updatedAt=' + (Get-Date).ToString('o')
        'pid=' + $PID
        'detail=' + ($Detail -replace '[\r\n]+', ' ')
    ) | Set-Content -LiteralPath $statePath -Encoding ascii
}

try {
    $validExisting = $false
    if (Test-Path -LiteralPath $installerPath -PathType Leaf) {
        $file = Get-Item -LiteralPath $installerPath
        $signature = Get-AuthenticodeSignature -FilePath $installerPath
        $validExisting = $file.Length -eq $expectedLength -and
            $signature.Status -eq 'Valid' -and
            $signature.SignerCertificate.Subject -match 'NVIDIA'
    }

    if (-not $validExisting) {
        Remove-Item -LiteralPath $installerPath -Force -ErrorAction SilentlyContinue
        Write-State 'DOWNLOADING' $url
        $curl = Get-Command curl.exe -ErrorAction Stop
        & $curl.Source --fail --location --retry 5 --retry-delay 5 `
            --connect-timeout 30 --continue-at - --output $partialPath $url
        if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE." }
        $download = Get-Item -LiteralPath $partialPath
        if ($download.Length -ne $expectedLength) {
            throw "Unexpected installer length: $($download.Length)."
        }
        Move-Item -LiteralPath $partialPath -Destination $installerPath -Force
    }

    Write-State 'VERIFYING' 'Authenticode'
    $installer = Get-Item -LiteralPath $installerPath
    $signature = Get-AuthenticodeSignature -FilePath $installerPath
    if ($installer.Length -ne $expectedLength) {
        throw "Unexpected installer length: $($installer.Length)."
    }
    if ($signature.Status -ne 'Valid' -or
        $signature.SignerCertificate.Subject -notmatch 'NVIDIA') {
        throw "Invalid NVIDIA signature: $($signature.Status)."
    }

    Write-State 'INSTALLING' $signature.SignerCertificate.Subject
    $arguments = @(
        '-s', '-n', 'Display.Driver',
        ('-log:' + $logDir), '-loglevel:6'
    )
    $process = Start-Process -FilePath $installerPath -ArgumentList $arguments `
        -WindowStyle Hidden -Wait -PassThru
    $exitCode = $process.ExitCode
    Write-State 'DONE' ("installerExit=$exitCode")
    if ($exitCode -notin @(0, 1)) { exit $exitCode }
} catch {
    Write-State 'FAILED' $_.Exception.Message
    throw
}

