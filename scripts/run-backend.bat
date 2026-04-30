@echo off
REM Backend Only - Windows
REM Usage: run-backend.bat

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%.."
set PROJECT_ROOT=%cd%

echo.
echo ============================================================
echo === Backend Only (Port 8888, Auto Bootstrap)
echo ============================================================
echo.

echo [0/3] Cleaning up stale database/cache processes...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$redisProcs = Get-Process -Name 'redis-server' -ErrorAction SilentlyContinue; if ($redisProcs) { $redisProcs | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 300 }; $postgresProcs = Get-Process -Name 'postgres' -ErrorAction SilentlyContinue; if ($postgresProcs) { $postgresProcs | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep -Milliseconds 500 }"

rem Already-running guard: if backend health endpoint responds on 8888, do not start another instance.
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r=Invoke-WebRequest -Uri 'http://127.0.0.1:8888/actuator/health' -UseBasicParsing -TimeoutSec 2; if($r.StatusCode -ge 200 -and $r.StatusCode -lt 500){ exit 7 } } catch { exit 0 }"
if "%ERRORLEVEL%"=="7" (
    echo Backend already running on 8888. Skipping duplicate start.
    endlocal
    exit /b 0
)

if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found
    exit /b 1
)

if not exist "scripts\backend-bootstrap.ps1" (
    echo ERROR: scripts\backend-bootstrap.ps1 not found
    exit /b 1
)

echo [1/3] Bootstrap PostgreSQL/Redis and backup settings...
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\backend-bootstrap.ps1"
if errorlevel 1 (
    echo Bootstrap failed!
    exit /b 1
)

echo.
echo [2/3] Building backend...
call gradlew.bat :backend:build -x test
if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

echo.
echo [2.5/3] Ensure TLS certificate if configured for this host...
set "PFX_PATH="
powershell -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%\scripts\ensure-backend-cert.ps1" -EnvFilePath "backend/.env" -WinAcmeScript "%PROJECT_ROOT%\scripts\win-acme-setup.ps1" -PfxPassword "changeit"
set "CERT_CHECK_EXIT=%ERRORLEVEL%"

if exist "scripts\certs\last-pfx-path.txt" (
    set /p PFX_PATH=<"scripts\certs\last-pfx-path.txt"
)

if defined PFX_PATH (
    set "PFX_PATH=!PFX_PATH:"=!"
    if not exist "!PFX_PATH!" set "PFX_PATH="
)

if not defined PFX_PATH (
    for /f "usebackq delims=" %%i in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$cand=Get-ChildItem 'scripts/certs' -Filter *.pfx -File -ErrorAction SilentlyContinue ^| Sort-Object LastWriteTime -Descending ^| Select-Object -First 1; if($cand){ Write-Output (Resolve-Path $cand.FullName).Path }"`) do set "PFX_PATH=%%i"
)

if not "%CERT_CHECK_EXIT%"=="0" (
    if defined PFX_PATH (
        echo WARNING: TLS ensure script failed with code %CERT_CHECK_EXIT%, but existing PFX was found. Continuing with HTTPS.
    ) else (
        echo WARNING: TLS ensure script failed with code %CERT_CHECK_EXIT%. Falling back to HTTP bootRun.
    )
)

if not defined PFX_PATH goto START_HTTP
if not exist "!PFX_PATH!" goto START_HTTP

echo Found PFX at !PFX_PATH! - starting backend with HTTPS (port 443)
echo [3/3] Building runnable jar...
call gradlew.bat :backend:bootJar -x test
if errorlevel 1 (
    echo BootJar build failed!
    exit /b 1
)
set "JARPATH="
for %%f in (backend\build\libs\*.jar) do set "JARPATH=%%f"
if not defined JARPATH (
    echo Could not find backend jar.
    exit /b 1
)
echo Running backend with HTTPS using PFX
java -Dserver.ssl.key-store="!PFX_PATH!" -Dserver.ssl.key-store-password=changeit -Dserver.ssl.key-store-type=PKCS12 -Dserver.port=443 -jar "!JARPATH!"
set EXIT_CODE=%ERRORLEVEL%
if not "%EXIT_CODE%"=="0" (
    echo Backend run failed with code %EXIT_CODE%
    exit /b %EXIT_CODE%
)
endlocal
exit /b 0

:START_HTTP
echo HTTPS auto-start skipped: no valid PFX candidate for non-localhost DOMAIN.
echo.
echo [3/3] Build successful. Starting Backend...
echo Backend: http://localhost:8888
echo Press Ctrl+C to stop
echo.

call gradlew.bat :backend:bootRun
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
    echo Backend run failed with code %EXIT_CODE%
    exit /b %EXIT_CODE%
)

endlocal
exit /b 0
