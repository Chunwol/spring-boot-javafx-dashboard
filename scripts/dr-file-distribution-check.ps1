param(
    [string]$BaseUrl = "http://127.0.0.1:8888/api/v1",
    [string]$Token = "",
    [string]$OutFile = "scripts/profile-dashboard.latest.json"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    Write-Host "주의: 토큰 없이 조회를 시도합니다. 보호된 API면 실패할 수 있습니다."
}

$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers["Authorization"] = "Bearer $Token"
}

Write-Host "[DR-1] 최근 Job 조회"
$jobs = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs" -Headers $headers

$now = (Get-Date).ToString("s")
$summary = [ordered]@{
    checkedAt = $now
    totalJobs = @($jobs).Count
    inProgress = @($jobs | Where-Object { $_.status -eq "IN_PROGRESS" }).Count
    failed = @($jobs | Where-Object { $_.status -eq "FAILED" }).Count
    cancelled = @($jobs | Where-Object { $_.status -eq "CANCELLED" }).Count
    completed = @($jobs | Where-Object { $_.status -eq "COMPLETED" }).Count
}

if (@($jobs).Count -gt 0) {
    $latest = $jobs[0]
    $jobId = [long]$latest.jobId
    Write-Host "[DR-2] 최신 Job($jobId) 상세 조회"
    $status = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs/$jobId" -Headers $headers
    $events = Invoke-RestMethod -Method Get -Uri "$BaseUrl/file-distribution/jobs/$jobId/events" -Headers $headers
    $summary["latestJob"] = $status
    $summary["latestJobEventCount"] = @($events).Count
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
Set-Content -Path $OutFile -Value $summaryJson -Encoding UTF8
Write-Host "DR 점검 완료. 결과 파일: $OutFile"
