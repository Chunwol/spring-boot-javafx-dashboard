# ===================================
# CLASSROOM DASHBOARD - FRONTEND
# PowerShell Startup Script
# ===================================

param(
    [switch]$Build = $false,
    [string]$ApiHost = "localhost",
    [int]$ApiPort = 8888
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host ""
Write-Host "===================================" -ForegroundColor Cyan
Write-Host "  CLASSROOM DASHBOARD - FRONTEND" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

# .env 파일 확인
$envFile = if (Test-Path "frontend.env") { "frontend.env" } 
           elseif (Test-Path "frontend\.env") { "frontend\.env" } 
           else { $null }

if (-not $envFile) {
    Write-Host "[ERROR] frontend.env file not found!" -ForegroundColor Red
    Write-Host "다음 경로에 frontend.env 또는 frontend\.env 파일이 있어야 합니다:" -ForegroundColor Red
    Write-Host "  1. $ScriptDir\frontend.env" -ForegroundColor Yellow
    Write-Host "  2. $ScriptDir\frontend\.env" -ForegroundColor Yellow
    Read-Host "Enter를 눌러 종료"
    exit 1
}

Write-Host "Workspace: $ScriptDir" -ForegroundColor Green
Write-Host "Profile: $envFile" -ForegroundColor Green
Write-Host ""

# frontend.env 파일에서 API 설정 추출
if (Test-Path $envFile) {
    $envContent = Get-Content $envFile
    foreach ($line in $envContent) {
        if ($line -match "API_SERVER_HOST\s*=\s*(.*)") {
            $ApiHost = $matches[1].Trim()
        }
        if ($line -match "API_SERVER_PORT\s*=\s*(.*)") {
            $ApiPort = [int]$matches[1].Trim()
        }
    }
}

Write-Host "API Server: http://$ApiHost`:$ApiPort" -ForegroundColor Green
Write-Host ""

$JarFile = "frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar"

# Build 확인
if (-not (Test-Path $JarFile) -or $Build) {
    Write-Host "[INFO] Building frontend..." -ForegroundColor Yellow
    & ".\gradlew.bat" ":frontend:shadowJar" "-q"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Build failed!" -ForegroundColor Red
        Read-Host "Enter를 눌러 종료"
        exit 1
    }
    Write-Host "[OK] Build completed!" -ForegroundColor Green
    Write-Host ""
}

# Java 버전 확인
$JavaVersion = java -version 2>&1 | Where-Object { $_ -match "version" } | Select-Object -First 1
Write-Host "Java: $JavaVersion" -ForegroundColor Cyan

# Frontend 실행
Write-Host "[INFO] Starting frontend client..." -ForegroundColor Yellow
Write-Host ""

java -jar $JarFile

exit $LASTEXITCODE
