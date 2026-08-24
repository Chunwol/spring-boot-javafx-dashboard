$ErrorActionPreference = 'Stop'
$jobId = '__JOB_ID__'
$statusPath = "C:\ProgramData\PCAgent\peer-transfer\$jobId\target\status.json"
if (-not (Test-Path -LiteralPath $statusPath)) {
    [ordered]@{ marker='PEER_TRANSFER_TARGET_STATUS'; jobId=$jobId; pcName=$env:COMPUTERNAME; state='missing'; verifiedFiles=0; verifiedBytes=[long]0 } | ConvertTo-Json -Compress
    exit 0
}
$status = Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json
$status | ConvertTo-Json -Depth 8 -Compress
