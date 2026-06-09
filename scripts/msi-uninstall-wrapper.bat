@echo off
setlocal EnableExtensions

cd /d "%~dp0"
set "MSI_NAME=${msiFileName}"
set "LOG_FILE=%~dp0uninstall-msi.log"

echo ==========================================
echo  PCAgent MSI Batch Uninstaller
echo ==========================================
echo MSI        : %MSI_NAME%
echo UninstallLog: %LOG_FILE%
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

msiexec.exe /x "%~dp0%MSI_NAME%" /passive /norestart /L*v "%LOG_FILE%"
if errorlevel 1 (
    echo [ERROR] MSI uninstall failed. Check log: %LOG_FILE%
    exit /b %errorlevel%
)

echo [OK] Uninstall completed.
exit /b 0
