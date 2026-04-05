# ===================================
# CLASSROOM DASHBOARD - FRONTEND DEV
# PowerShell Startup Script
# ===================================

param()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
Set-Location $ProjectRoot

$ApiHost = "localhost"
$ApiPort = 8888

if (Test-Path "frontend.env") {
    Get-Content "frontend.env" | ForEach-Object {
        if ($_ -match "^API_SERVER_HOST=(.*)$") {
            $ApiHost = $matches[1].Trim()
        }
        if ($_ -match "^API_SERVER_PORT=(.*)$") {
            $ApiPort = $matches[1].Trim()
        }
    }
}

Write-Host ""
Write-Host "===================================" -ForegroundColor Cyan
Write-Host "  RUN FRONTEND DEV" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Connecting to: http://$ApiHost`:$ApiPort" -ForegroundColor Green
Write-Host ""

& ".\gradlew.bat" ":frontend:run"
exit $LASTEXITCODE