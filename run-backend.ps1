# ===================================
# CLASSROOM DASHBOARD - BACKEND
# PowerShell Startup Script
# ===================================

param(
    [switch]$Build = $false,
    [int]$Port = 8888
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host ""
Write-Host "===================================" -ForegroundColor Cyan
Write-Host "  CLASSROOM DASHBOARD - BACKEND" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

# .env 파일 확인
$envFile = if (Test-Path "backend.env") { "backend.env" } 
           elseif (Test-Path "backend\.env") { "backend\.env" } 
           else { $null }

if (-not $envFile) {
    Write-Host "[ERROR] backend.env file not found!" -ForegroundColor Red
    Write-Host "다음 경로에 backend.env 또는 backend\.env 파일이 있어야 합니다:" -ForegroundColor Red
    Write-Host "  1. $ScriptDir\backend.env" -ForegroundColor Yellow
    Write-Host "  2. $ScriptDir\backend\.env" -ForegroundColor Yellow
    Read-Host "Enter를 눌러 종료"
    exit 1
}

Write-Host "Workspace: $ScriptDir" -ForegroundColor Green
Write-Host "Profile: $envFile" -ForegroundColor Green
Write-Host ""

$JarFile = "backend\build\libs\backend-0.0.1-SNAPSHOT.jar"

# Build 확인
if (-not (Test-Path $JarFile) -or $Build) {
    Write-Host "[INFO] Building backend..." -ForegroundColor Yellow
    & ".\gradlew.bat" ":backend:build" "-q"
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

# 서버 실행
Write-Host "[INFO] Starting backend server on port $Port..." -ForegroundColor Yellow
Write-Host ""

java -jar $JarFile

exit $LASTEXITCODE
