@echo off
REM Disable dashboard auto-start task (Windows Task Scheduler)

setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%cd%\scripts\setup-dashboard-autostart.ps1" -Action disable
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%
