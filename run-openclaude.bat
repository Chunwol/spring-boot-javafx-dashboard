@echo off
setlocal
cd /d "%~dp0"

where dotenv >nul 2>nul
if errorlevel 1 (
  echo [ERROR] dotenv command not found.
  echo Install: npm install -g dotenv-cli
  exit /b 1
)

echo [INFO] Using .env.openclaude
if not exist ".env.openclaude" (
  echo [ERROR] .env.openclaude not found in project root.
  exit /b 1
)

dotenv -e .env.openclaude -- openclaude %*
