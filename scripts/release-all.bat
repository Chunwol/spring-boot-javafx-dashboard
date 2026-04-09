@echo off
REM One-click Release Build (Windows)
REM Usage: scripts\release-all.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === One-click Release Build (Windows)
echo ============================================================
echo.

set "NO_PAUSE=1"
call scripts\check-versions-and-fix.bat check
if !errorlevel! neq 0 (
    echo [ERROR] Environment check failed
    exit /b 1
)

call scripts\build-all.bat
if !errorlevel! neq 0 (
    echo [ERROR] Release build failed
    exit /b 1
)

echo.
echo [OK] Release build completed.
echo Output: dist\
echo.
endlocal