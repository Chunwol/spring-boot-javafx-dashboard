param(
    [string]$BaseUrl = "http://127.0.0.1:8888/api/v1",
    [string]$Token = "",
    [string]$Username = "",
    [string]$Password = "",
    [string]$SharedSecret = "",
    [switch]$DoRetryFailed,
    [switch]$DoRollback,
    [long]$RollbackFromJobId = 0
)

$ErrorActionPreference = "Stop"

function Get-AuthToken {
    param([string]$BaseUrl, [string]$Username, [string]$Password)
    if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
        return ""
    }
    $body = @{ username = $Username; password = $Password } | ConvertTo-Json
    $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType "application/json" -Body $body
    return [string]$resp.token
}

function New-SignHeaders {
    param([string]$Canonical, [string]$SharedSecret)
    if ([string]::IsNullOrWhiteSpace($SharedSecret)) {
        return @{}
    }
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $msg = "$ts`n$Canonical"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($SharedSecret))
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($msg))
    $sig = ([System.BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
    return @{ "X-FD-Timestamp" = $ts; "X-FD-Signature" = $sig }
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    $Token = Get-AuthToken -BaseUrl $BaseUrl -Username $Username -Password $Password
}

$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers["Authorization"] = "Bearer $Token"
}

Write-Host "[1] 최근 파일배포 Job 조회..."
$jobs = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs" -Headers $headers
$jobCount = @($jobs).Count
Write-Host "최근 Job 수: $jobCount"
if ($jobCount -eq 0) {
    Write-Host "조회된 Job이 없어 테스트를 종료합니다."
    exit 0
}

$selected = $jobs[0]
$jobId = [long]$selected.jobId
Write-Host "선택 Job ID: $jobId / 상태: $($selected.status)"

Write-Host "[2] 상세 상태/이벤트 조회..."
$status = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs/$jobId" -Headers $headers
$events = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs/$jobId/events" -Headers $headers
Write-Host "진행률: $($status.progress), 이벤트: $(@($events).Count)"

if ($DoRetryFailed) {
    Write-Host "[3] 실패 대상 재전송 호출..."
    $canonical = "retryFailed|$jobId|"
    $signHeaders = New-SignHeaders -Canonical $canonical -SharedSecret $SharedSecret
    $allHeaders = @{}
    $headers.GetEnumerator() | ForEach-Object { $allHeaders[$_.Key] = $_.Value }
    $signHeaders.GetEnumerator() | ForEach-Object { $allHeaders[$_.Key] = $_.Value }
    $retryResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/file-distribution/jobs/$jobId/retry-failed" -Headers $allHeaders -ContentType "application/json" -Body "{}"
    Write-Host "재전송 결과: $($retryResp | ConvertTo-Json -Compress)"
}

if ($DoRollback) {
    if ($RollbackFromJobId -le 0) {
        throw "-DoRollback 사용 시 -RollbackFromJobId 값을 지정하세요."
    }
    Write-Host "[4] 롤백 호출... source=$jobId rollbackFrom=$RollbackFromJobId"
    $canonical = "rollback|$jobId|$RollbackFromJobId|20|False|"
    $signHeaders = New-SignHeaders -Canonical $canonical -SharedSecret $SharedSecret
    $allHeaders = @{}
    $headers.GetEnumerator() | ForEach-Object { $allHeaders[$_.Key] = $_.Value }
    $signHeaders.GetEnumerator() | ForEach-Object { $allHeaders[$_.Key] = $_.Value }
    $body = @{ rollbackFromJobId = $RollbackFromJobId; waveSize = 20; canaryOnly = $false } | ConvertTo-Json
    $rollbackResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/file-distribution/jobs/$jobId/rollback" -Headers $allHeaders -ContentType "application/json" -Body $body
    Write-Host "롤백 결과: $($rollbackResp | ConvertTo-Json -Compress)"
}

Write-Host "파일배포 스모크 테스트 완료"
