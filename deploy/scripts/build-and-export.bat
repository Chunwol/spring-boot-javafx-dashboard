@echo off
setlocal

SET IMAGE_NAME=classroom-dashboard
SET IMAGE_TAG=latest
SET OUTPUT_FILE=deploy\classroom-dashboard.tar

SET PROJECT_ROOT=%~dp0..\..
cd /d "%PROJECT_ROOT%"

echo.
echo ================================================
echo  Classroom Dashboard Build ^& Export
echo ================================================
echo  Project: %CD%
echo ================================================
echo.

REM -- Check Docker is running --
docker info >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [INFO] Trying desktop-linux context...
    docker context use desktop-linux >nul 2>&1
    docker info >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo.
        echo [FAILED] Docker Desktop is not running.
        echo  Please start Docker Desktop and try again.
        pause
        exit /b 1
    )
)
echo [OK] Docker is running.
echo.

echo [1/3] Building JAR with Gradle...
call gradlew.bat --project-dir "%CD%" :backend:bootJar -x test --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Gradle build failed.
    pause
    exit /b 1
)
echo.

echo [2/3] Building Docker image...
docker build -f deploy\Dockerfile -t %IMAGE_NAME%:%IMAGE_TAG% .
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Docker build failed.
    pause
    exit /b 1
)
echo.

echo [3/3] Saving image to file...
docker save -o %OUTPUT_FILE% %IMAGE_NAME%:%IMAGE_TAG%
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] docker save failed.
    pause
    exit /b 1
)

echo.
echo ================================================
echo  Done! Output: deploy\classroom-dashboard.tar
echo ================================================
echo.
echo Next steps on NAS:
echo   docker load -i classroom-dashboard.tar
echo   cp .env.example .env
echo   docker compose up -d
echo.
pause
endlocal