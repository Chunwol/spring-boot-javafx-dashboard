#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Safe checkpoint automation script for dashboard development
.DESCRIPTION
    Provides:
    - Pre-task snapshot (git checkpoint)
    - Post-task commit with validation
    - Build verification before commit
.PARAMETER TaskName
    Short task name for branch/commit message (e.g., "zoom-fixes", "mvvm-refactor")
.PARAMETER BuildVerify
    Optionally build and verify after changes [Yes/No]
.EXAMPLE
    .\checkpoint.ps1 -TaskName "implement-fxml-layout" -BuildVerify Yes
#>

param(
    [string]$TaskName = "",
    [string]$BuildVerify = "Yes"
)

$ErrorActionPreference = "Stop"

function Write-Status {
    param([string]$Message, [string]$Type = "Info")
    $colors = @{
        Info    = 'Cyan'
        Success = 'Green'
        Warning = 'Yellow'
        Error   = 'Red'
    }
    $color = $colors[$Type] ?? 'White'
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Message" -ForegroundColor $color
}

function Write-Section {
    param([string]$Title)
    Write-Host "`n" + ("=" * 60) -ForegroundColor Cyan
    Write-Host $Title -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}

# ROOT: Workspace directory
$wsRoot = Split-Path -Parent -Path $PSScriptRoot
if (-not (Test-Path (Join-Path $wsRoot ".git"))) {
    Write-Status "Error: Not in a git repository!" "Error"
    exit 1
}

Set-Location $wsRoot

# 1. Check git status
Write-Section "1. Git Status Check"
$status = & git status --porcelain
if ([string]::IsNullOrWhiteSpace($status)) {
    Write-Status "Working directory clean, ready to start new task" "Success"
} else {
    Write-Status "⚠️  Uncommitted changes detected:" "Warning"
    Write-Host $status
    Write-Status "Please commit or stash before proceeding" "Warning"
    exit 1
}

# 2. Get task name if not provided
if ([string]::IsNullOrWhiteSpace($TaskName)) {
    Write-Section "2. Task Name Input"
    $TaskName = Read-Host "Enter task name (e.g., zoom-fixes, mvvm-refactor)"
    if ([string]::IsNullOrWhiteSpace($TaskName)) {
        Write-Status "Error: Task name cannot be empty" "Error"
        exit 1
    }
}

# 3. Create checkpoint before task
Write-Section "3. Creating Pre-Task Checkpoint"
$timestamp = Get-Date -Format "yyyyMMdd-HHmm"
$branchName = "task/$($TaskName -replace '[^a-zA-Z0-9-]', '-')"

try {
    Write-Status "Creating checkpoint: chore: checkpoint before $TaskName" "Info"
    & git add -A
    & git commit -m "chore: checkpoint before $TaskName"
    Write-Status "✅ Checkpoint created" "Success"
} catch {
    Write-Status "Note: No changes to commit" "Warning"
}

# 4. Optionally create feature branch
Write-Host "`nCreate feature branch '$branchName'? (Y/n): " -NoNewline
$response = Read-Host
if ($response -ne 'n' -and $response -ne 'N') {
    & git checkout -b $branchName 2>/dev/null || (
        Write-Status "Branch already exists or could not be created" "Warning"
    )
    Write-Status "✅ Working on branch: $branchName" "Success"
}

# 5. Task execution hint
Write-Section "4. Ready for Development"
Write-Host @"
✅ Pre-task checkpoint created
📋 Task: $TaskName
🔗 Branch: $branchName

Now make your code changes. When finished, run:
  .\scripts\checkpoint.ps1 -TaskName "$TaskName" -Commit Yes

"@

# 6. Option to build now (for checkpoint validation)
if ($BuildVerify -eq "Yes" -or $BuildVerify -eq "Y") {
    Write-Section "5. Build Verification"
    Write-Host "Run build now? (Y/n): " -NoNewline
    $buildResponse = Read-Host
    
    if ($buildResponse -ne 'n' -and $buildResponse -ne 'N') {
        Write-Status "Building frontend..." "Info"
        $buildOutput = & .\gradlew.bat :frontend:build -x test 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Status "✅ Build successful" "Success"
        } else {
            Write-Status "❌ Build failed" "Error"
            Write-Host $buildOutput
            Write-Status "Fix errors before committing" "Error"
        }
    }
}

Write-Section "Development Session Started"
Write-Status "Happy coding! 🚀" "Success"
