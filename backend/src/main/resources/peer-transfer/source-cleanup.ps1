$ErrorActionPreference = 'Stop'
$jobId = '__JOB_ID__'
$workDir = "C:\ProgramData\PCAgent\peer-transfer\$jobId\source"
$pidPath = Join-Path $workDir 'worker.pid'
$ruleName = "PCAgent Peer Transfer $jobId"
if (Test-Path -LiteralPath $pidPath) {
    $workerPid = [int](Get-Content -LiteralPath $pidPath -Raw -ErrorAction SilentlyContinue)
    if ($workerPid) { Stop-Process -Id $workerPid -Force -ErrorAction SilentlyContinue }
}
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
[ordered]@{ marker='PEER_TRANSFER_SOURCE_CLEANUP'; jobId=$jobId; workerRemaining=[bool](if($workerPid){Get-Process -Id $workerPid -ErrorAction SilentlyContinue}else{$false}); firewallRemaining=[bool](Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) } | ConvertTo-Json -Compress
