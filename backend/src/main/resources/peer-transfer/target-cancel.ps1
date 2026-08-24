$ErrorActionPreference = 'Stop'
$jobId = '__JOB_ID__'
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\target"
$pidPath = Join-Path $workDir 'worker.pid'
$cancelPath = Join-Path $workDir 'cancel.requested'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
'cancelled' | Set-Content -LiteralPath $cancelPath -Encoding ASCII
$workerPid = 0
if (Test-Path -LiteralPath $pidPath) {
    $workerPid = [int](Get-Content -LiteralPath $pidPath -Raw -ErrorAction SilentlyContinue)
    if ($workerPid) { Stop-Process -Id $workerPid -Force -ErrorAction SilentlyContinue }
}
[ordered]@{ marker='PEER_TRANSFER_TARGET_CANCEL'; jobId=$jobId; workerPid=$workerPid; stopped=-not [bool](if($workerPid){Get-Process -Id $workerPid -ErrorAction SilentlyContinue}else{$false}) } | ConvertTo-Json -Compress
