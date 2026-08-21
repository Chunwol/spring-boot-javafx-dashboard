@echo off
REM React web frontend development server
setlocal
cd /d "%~dp0..\web-frontend"
call npm ci
if errorlevel 1 exit /b %ERRORLEVEL%
call npm run dev
endlocal
