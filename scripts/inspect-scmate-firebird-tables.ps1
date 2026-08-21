$ErrorActionPreference = 'Stop'

$isql = 'C:/Program Files (x86)/Firebird/Firebird_2_5/bin/isql.exe'
$serverExe = 'C:/Program Files/SCMATE_RX2Server/SCMateServer.exe'
$database = 'localhost/3050:C:/Program Files/SCMATE_RX2Server/Data/SCMate.Fdb'
if (-not (Test-Path -LiteralPath $isql -PathType Leaf) -or
    -not (Test-Path -LiteralPath $serverExe -PathType Leaf)) {
    throw 'SCMATE Firebird runtime not found.'
}

$binaryText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($serverExe))
$credentialMatch = [regex]::Match(
    $binaryText,
    'user_name=([A-Za-z0-9_.-]+).{0,80}?password=([A-Za-z0-9_.!@#$%^&*-]+)',
    [Text.RegularExpressions.RegexOptions]::Singleline
)
if (-not $credentialMatch.Success) {
    throw 'SCMATE database credential metadata not found.'
}

$user = $credentialMatch.Groups[1].Value
$password = $credentialMatch.Groups[2].Value
$sqlPath = Join-Path $env:TEMP ('pcagent-scmate-tables-' + [guid]::NewGuid().ToString('N') + '.sql')
try {
    "SHOW TABLES;`r`nSHOW TABLE UMSCLIENTINFO;`r`nQUIT;" |
        Set-Content -LiteralPath $sqlPath -Encoding ASCII
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $result = @(& $isql '-q' '-user' $user '-password' $password '-input' $sqlPath $database 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    $combined = ($result | Out-String)
    $tables = @([regex]::Matches($combined, '(?m)^\s+([A-Z][A-Z0-9_$]+)\s*$') |
        ForEach-Object { $_.Groups[1].Value } |
        Sort-Object -Unique)

    [ordered]@{
        computerName = $env:COMPUTERNAME
        exitCode = $exitCode
        connected = $exitCode -eq 0
        tables = $tables
        umsClientInfoSchema = if ($combined.Length -gt 16000) {
            $combined.Substring($combined.Length - 16000)
        } else {
            $combined
        }
    } | ConvertTo-Json -Depth 4 -Compress
} finally {
    $user = $null
    $password = $null
    Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
}
