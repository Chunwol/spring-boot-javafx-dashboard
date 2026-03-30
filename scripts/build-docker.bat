@echo off
REM Docker 이미지 빌드 배치 파일
REM 각 모듈별 Docker 이미지를 생성합니다

setlocal enabledelayedexpansion

echo ================================
echo Building Docker Images
echo ================================
echo.

REM 1. Backend Docker 이미지
echo [1/2] Building Backend Docker image...
docker build -t dashboard-backend:latest -f Dockerfile .
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Backend image built: dashboard-backend:latest ^& echo.
) else (
    echo. ^& echo *** Backend Docker build failed *** ^& echo.
    exit /b 1
)

REM 2. Agent Docker 이미지
echo [2/2] Building Agent Docker image...
docker build -t dashboard-agent:latest -f agent\Dockerfile .
if !errorlevel! equ 0 (
    echo. ^& echo ✓ Agent image built: dashboard-agent:latest ^& echo.
) else (
    echo. ^& echo *** Agent Docker build failed *** ^& echo.
    exit /b 1
)

echo.
echo ================================
echo All Docker images built successfully!
echo ================================
echo.
echo Docker images created:
echo   • dashboard-backend:latest
echo   • dashboard-agent:latest
echo.
echo Frontend distribution:
echo   • frontend\build\package\ClassroomDashboard\ClassroomDashboard.exe
echo.
echo Next steps:
echo   1. Run 'docker-compose up -d' to start the stack
echo   2. Check images: docker images ^| findstr dashboard
echo   3. Check containers: docker ps -a
echo   4. View logs: docker-compose logs -f
echo.

endlocal
