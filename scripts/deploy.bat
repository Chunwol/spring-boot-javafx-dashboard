@echo off
REM 전체 배포 배치 파일
REM 모든 모듈을 빌드하고 Docker Compose로 배포합니다

setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo ==================================================
echo STEP 0: Cleaning existing runtime and Docker stack...
echo ==================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cleanup-runtime.ps1" -NoBanner -IncludeDocker

echo.
echo ==================================================
echo STEP 1: Building Java modules...
echo ==================================================
call scripts\build-all.bat
if !errorlevel! neq 0 (
    echo *** Java module build failed ***
    exit /b 1
)

echo.
echo ==================================================
echo STEP 2: Checking .env file...
echo ==================================================
if not exist ".env" (
    echo *** .env file not found ***
    echo Creating .env from .env.example...
    if exist ".env.example" (
        copy .env.example .env
        echo ✓ .env created from .env.example
        echo.
        echo.
        echo *** Edit .env and replace every CHANGE_ME value before deployment ***
        exit /b 1
    ) else (
        echo *** .env.example not found ***
        exit /b 1
    )
) else (
    echo ✓ .env file exists
)

findstr /C:"CHANGE_ME" ".env" >nul
if !errorlevel! equ 0 (
    echo *** .env still contains CHANGE_ME placeholders ***
    echo Update the secrets and run deploy.bat again.
    exit /b 1
)

echo.
echo ==================================================
echo STEP 3: Building Docker images...
echo ==================================================
call scripts\build-docker.bat
if !errorlevel! neq 0 (
    echo *** Docker image build failed ***
    exit /b 1
)

echo.
echo ==================================================
echo STEP 4: Starting Docker stack...
echo ==================================================
docker compose up -d
if !errorlevel! neq 0 (
    echo *** Docker Compose startup failed ***
    exit /b 1
)

echo ✓ Docker stack started

REM 잠시 대기 (컨테이너 시작)
timeout /t 3 /nobreak

echo.
echo ==================================================
echo STEP 5: Checking container status...
echo ==================================================
docker compose ps

echo.
echo ✓ Deployment completed!
echo.
echo Important information:
echo   • Backend API:  https://pelmon.kro.kr/api/...
echo   • WebSocket:    wss://pelmon.kro.kr/ws/...
echo   • Health Check: https://pelmon.kro.kr/health
echo   • Frontend EXE: frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe
echo   • Backend Run:  scripts\run-backend.bat
echo   • Frontend Dev: scripts\run-frontend-dev.bat
echo   • Frontend Run: scripts\run-frontend.bat
echo   • Agent Run:    scripts\run-agent.bat
echo.
echo Useful commands:
echo   • View logs:    docker-compose logs -f [SERVICE_NAME]
echo   • Stop stack:   docker-compose down
echo   • Restart:      docker-compose restart [SERVICE_NAME]
echo   • Remove all data ^(DANGEROUS^): run cleanup-runtime.ps1 with -IncludeDocker -RemoveDockerVolumes
echo.

endlocal
