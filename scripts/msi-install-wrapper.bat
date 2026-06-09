@echo off
setlocal EnableExtensions

cd /d "%~dp0"
set "MSI_NAME=${msiFileName}"
set "LOG_FILE=%~dp0install-msi.log"

echo ==========================================
echo  PCAgent MSI Batch Installer
echo ==========================================
echo MSI      : %MSI_NAME%
echo InstallLog: %LOG_FILE%
echo.

if not exist "%MSI_NAME%" (
    echo [ERROR] MSI file not found: %MSI_NAME%
    exit /b 2
)

net session >nul 2>&1
if errorlevel 1 (
    echo [INFO] Requesting administrator privileges...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "Start-Process -FilePath '%~f0' -ArgumentList 'elevated' -Verb RunAs"
    exit /b
)

echo [1/2] Installing MSI...
msiexec.exe /i "%~dp0%MSI_NAME%" /passive /norestart /L*v "%LOG_FILE%"
if errorlevel 1 (
    echo [ERROR] MSI install failed. Check log: %LOG_FILE%
    exit /b %errorlevel%
)

echo [2/2] Starting scheduled task...
schtasks /Run /TN "PCAgent" >nul 2>&1
if errorlevel 1 (
    echo [WARN] Scheduled task start failed. It will start automatically after reboot.
) else (
    echo [OK] PCAgent scheduled task started.
)

echo.
echo [OK] Installation completed.
echo Log path: C:\ProgramData\PCAgent\logs\agent.log
exit /b 0
