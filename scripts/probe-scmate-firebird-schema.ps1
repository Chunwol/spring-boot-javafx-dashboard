$ErrorActionPreference = 'Stop'

$isql = 'C:/Program Files (x86)/Firebird/Firebird_2_5/bin/isql.exe'
$database = 'localhost/3050:C:/Program Files/SCMATE_RX2Server/Data/SCMate.Fdb'

if (-not (Test-Path -LiteralPath $isql -PathType Leaf)) {
    throw 'Firebird isql.exe not found.'
}

$sqlPath = Join-Path $env:TEMP ('pcagent-scmate-schema-' + [guid]::NewGuid().ToString('N') + '.sql')
try {
    @'
SHOW SQL DIALECT;
SHOW TABLES;
QUIT;
'@ | Set-Content -LiteralPath $sqlPath -Encoding ASCII

    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $result = @(& $isql '-q' '-trusted' '-input' $sqlPath $database 2>&1)
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    $combined = ($result | Out-String).Trim()

    [ordered]@{
        computerName = $env:COMPUTERNAME
        exitCode = $exitCode
        connected = $exitCode -eq 0 -and $combined -notmatch 'Your user name and password are not defined|unavailable database|connection rejected'
        output = if ($combined.Length -gt 3500) { $combined.Substring(0, 3500) } else { $combined }
    } | ConvertTo-Json -Compress
} finally {
    Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
}
