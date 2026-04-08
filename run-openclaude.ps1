Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Set-Location -Path $PSScriptRoot

if (-not (Get-Command dotenv -ErrorAction SilentlyContinue)) {
    Write-Error "dotenv command not found. Install with: npm install -g dotenv-cli"
}

if (-not (Test-Path ".env.openclaude")) {
    Write-Error ".env.openclaude not found in project root."
}

Write-Host "[INFO] Using .env.openclaude"
& dotenv -e .env.openclaude -- openclaude @args
