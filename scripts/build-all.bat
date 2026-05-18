@echo off
REM Build All Modules - Windows
REM Usage: scripts\build-all.bat

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo.
echo ============================================================
echo === Build All Modules (Windows)
echo ============================================================
echo.

REM 0. Clean up stale processes
echo [0/6] Cleaning up stale database/cache processes...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CD%\scripts\cleanup-runtime.ps1" -NoBanner
echo.

REM 1. Common module
echo [1/6] Building Common Module...
call gradlew.bat :common:build -x test
if !errorlevel! equ 0 (
    echo [OK] Common module built successfully
) else (
    echo [ERROR] Common module build failed
    exit /b 1
)
echo.

REM 2. Backend module
echo [2/6] Building Backend Module...
call gradlew.bat :backend:bootJar -x test
if !errorlevel! equ 0 (
    echo [OK] Backend JAR: backend\build\libs\backend-0.0.1-SNAPSHOT.jar
) else (
    echo [ERROR] Backend build failed
    exit /b 1
)
echo.

REM 3. Agent module
echo [3/6] Building Agent Module...
call gradlew.bat :agent:build -x test
if !errorlevel! equ 0 (
    echo [OK] Agent JAR: agent\build\libs\agent-1.0.0.jar
) else (
    echo [ERROR] Agent build failed
    exit /b 1
)
echo.

REM 4. Frontend module
echo [4/6] Building Frontend Module...
call gradlew.bat :frontend:shadowJar -x test
if !errorlevel! equ 0 (
    echo [OK] Frontend JAR: frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar
) else (
    echo [ERROR] Frontend build failed
    exit /b 1
)

echo.
echo [5/6] Packaging Frontend EXE (app-image)...
call gradlew.bat :frontend:packageExe
if !errorlevel! equ 0 (
    echo [OK] Frontend EXE: frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe
) else (
    echo [ERROR] Frontend EXE packaging failed
    exit /b 1
)

echo.
echo [5.5/6] Packaging Frontend MSI installer...
call gradlew.bat :frontend:packageMsi
if !errorlevel! equ 0 (
    echo [OK] Frontend MSI generated in frontend\build\package\
) else (
    echo [WARN] Frontend MSI packaging skipped or failed ^(WiX Toolset may be required^)
)

echo.
echo [6/6] Collecting release artifacts to dist\
if not exist dist mkdir dist
if not exist dist\backend mkdir dist\backend
if not exist dist\agent mkdir dist\agent
if not exist dist\frontend mkdir dist\frontend

copy /Y backend\build\libs\backend-0.0.1-SNAPSHOT.jar dist\backend\backend.jar >nul
copy /Y agent\build\libs\agent-1.0.0.jar dist\agent\agent.jar >nul
copy /Y frontend\build\libs\frontend-0.0.1-SNAPSHOT-all.jar dist\frontend\frontend-all.jar >nul

if exist frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe (
    copy /Y frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe dist\frontend\ClassroomDashboard.exe >nul
)

for %%f in (frontend\build\package\*.msi) do (
    copy /Y "%%f" dist\frontend\ClassroomDashboard.msi >nul
)

echo.
echo ============================================================
echo All modules built successfully!
echo ============================================================
echo.
echo Generated artifacts:
echo   - Backend:  dist\backend\backend.jar
echo   - Agent:    dist\agent\agent.jar
echo   - Frontend fat JAR: dist\frontend\frontend-all.jar
echo   - Frontend EXE:     dist\frontend\ClassroomDashboard.exe
echo   - Frontend MSI:     dist\frontend\ClassroomDashboard.msi ^(if generated^)
echo.
echo Next steps:
echo   1. Distribute dist\ folder
echo   2. Run dist\frontend\ClassroomDashboard.exe on target PC
echo   3. Use backend.jar and agent.jar for server/agent deployment
echo.

endlocal
