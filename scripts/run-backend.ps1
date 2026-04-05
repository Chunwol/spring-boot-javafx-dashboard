# ===================================
# CLASSROOM DASHBOARD - BACKEND
# PowerShell Startup Script
# ===================================

param()

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
Set-Location $ProjectRoot

Write-Host ""
Write-Host "===================================" -ForegroundColor Cyan
Write-Host "  RUN BACKEND" -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

& ".\gradlew.bat" ":backend:bootRun"
exit $LASTEXITCODE