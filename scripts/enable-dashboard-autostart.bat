@echo off
REM Enable dashboard auto-start at logon (Windows Task Scheduler)

setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%cd%\scripts\setup-dashboard-autostart.ps1" -Action enable -RunNow
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%
