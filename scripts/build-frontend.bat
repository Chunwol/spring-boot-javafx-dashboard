@echo off
REM Build the React web frontend
setlocal
cd /d "%~dp0..\web-frontend"
call npm ci
if errorlevel 1 exit /b %ERRORLEVEL%
call npm run build
endlocal
