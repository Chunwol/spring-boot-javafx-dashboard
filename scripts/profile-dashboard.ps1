param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [int]$Iterations = 5,
    [int]$Warmup = 1,
    [switch]$IncludeBuildMetrics
)

$ErrorActionPreference = "Stop"

function Invoke-TimedRequest {
    param(
        [string]$Url
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -Uri $Url -Method GET -UseBasicParsing -TimeoutSec 15
    $sw.Stop()

    [pscustomobject]@{
        Url = $Url
        StatusCode = [int]$response.StatusCode
        ElapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
        ContentLength = if ($null -ne $response.Content) { $response.Content.Length } else { 0 }
    }
}

function Measure-Endpoint {
    param(
        [string]$Label,
        [string]$Path
    )

    $url = "$BaseUrl$Path"
    for ($i = 0; $i -lt $Warmup; $i++) {
        [void](Invoke-TimedRequest -Url $url)
    }

    $samples = @()
    for ($i = 0; $i -lt $Iterations; $i++) {
        $samples += Invoke-TimedRequest -Url $url
    }

    $times = $samples | ForEach-Object { $_.ElapsedMs }
    $payload = ($samples | Select-Object -First 1).ContentLength
    [pscustomobject]@{
        Name = $Label
        Path = $Path
        AvgMs = [math]::Round((($times | Measure-Object -Average).Average), 2)
        MinMs = [math]::Round((($times | Measure-Object -Minimum).Minimum), 2)
        MaxMs = [math]::Round((($times | Measure-Object -Maximum).Maximum), 2)
        PayloadBytes = $payload
        Samples = ($times -join ", ")
    }
}

function Get-BackendMemorySummary {
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $listener) {
        return [pscustomobject]@{
            ProcessId = $null
            WorkingSetMB = $null
            PrivateMemoryMB = $null
            Note = "No listener on port 8080"
        }
    }

    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return [pscustomobject]@{
            ProcessId = $listener.OwningProcess
            WorkingSetMB = $null
            PrivateMemoryMB = $null
            Note = "Process not found"
        }
    }

    [pscustomobject]@{
        ProcessId = $process.Id
        WorkingSetMB = [math]::Round($process.WorkingSet64 / 1MB, 2)
        PrivateMemoryMB = [math]::Round($process.PrivateMemorySize64 / 1MB, 2)
        Note = $process.ProcessName
    }
}

function Measure-CommandDuration {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Label
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = Start-Process -FilePath $FilePath -ArgumentList $Arguments -NoNewWindow -Wait -PassThru
    $sw.Stop()

    [pscustomobject]@{
        Name = $Label
        ExitCode = $proc.ExitCode
        ElapsedMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
    }
}

$results = @(
    (Measure-Endpoint -Label "Unified refresh" -Path "/dashboard/refresh")
    (Measure-Endpoint -Label "Classrooms" -Path "/dashboard/classrooms")
    (Measure-Endpoint -Label "Texts" -Path "/dashboard/texts")
    (Measure-Endpoint -Label "Images" -Path "/dashboard/images")
)

$memory = Get-BackendMemorySummary
$buildMetrics = @()

if ($IncludeBuildMetrics) {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
    $gradleBat = Join-Path $repoRoot "gradlew.bat"
    if (Test-Path $gradleBat) {
        $buildMetrics += Measure-CommandDuration -FilePath $gradleBat -Arguments @(':backend:classes', '-PskipWebFrontendBuild=true') -Label 'backend classes build'
    }
}

Write-Host "Dashboard performance baseline" -ForegroundColor Cyan
$results | Format-Table Name, Path, AvgMs, MinMs, MaxMs, PayloadBytes -AutoSize

Write-Host ""
Write-Host "Backend memory" -ForegroundColor Cyan
$memory | Format-List

if ($buildMetrics.Count -gt 0) {
    Write-Host "Build metrics" -ForegroundColor Cyan
    $buildMetrics | Format-Table Name, ExitCode, ElapsedMs -AutoSize
}

$output = [pscustomobject]@{
    MeasuredAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    BaseUrl = $BaseUrl
    Iterations = $Iterations
    Warmup = $Warmup
    Endpoints = $results
    BackendMemory = $memory
    BuildMetrics = $buildMetrics
}

$jsonPath = Join-Path $PSScriptRoot "profile-dashboard.latest.json"
$output | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonPath -Encoding UTF8
Write-Host "Saved JSON report to $jsonPath" -ForegroundColor Green
