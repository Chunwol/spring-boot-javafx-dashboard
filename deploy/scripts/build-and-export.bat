@echo off
setlocal

SET IMAGE_NAME=classroom-dashboard
SET IMAGE_TAG=latest
SET OUTPUT_FILE=deploy\classroom-dashboard.tar
SET IMAGE_CONTEXT=deploy\image-context

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

echo [1/5] Installing and testing web frontend...
call npm --prefix web-frontend ci
if %ERRORLEVEL% neq 0 (
    echo [FAILED] npm install failed.
    pause
    exit /b 1
)
call npm --prefix web-frontend run build
if %ERRORLEVEL% neq 0 (
    echo [FAILED] Web frontend build failed.
    pause
    exit /b 1
)
echo.

call npm --prefix web-frontend test
if %ERRORLEVEL% neq 0 (
    echo [FAILED] Web frontend tests failed.
    pause
    exit /b 1
)
echo.

echo [2/5] Running backend tests...
call gradlew.bat --project-dir "%CD%" :backend:test -PskipWebFrontendBuild --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Backend tests failed.
    pause
    exit /b 1
)
echo.

echo [3/5] Building JAR with Gradle...
call gradlew.bat --project-dir "%CD%" :backend:bootJar -PskipWebFrontendBuild --no-daemon
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Gradle build failed.
    pause
    exit /b 1
)
echo.

echo [4/5] Building Docker image...
copy /Y backend\build\libs\backend-0.0.1-SNAPSHOT.jar %IMAGE_CONTEXT%\app.jar >nul
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Unable to stage backend JAR for Docker build.
    pause
    exit /b 1
)
docker build -t %IMAGE_NAME%:%IMAGE_TAG% %IMAGE_CONTEXT%
if %ERRORLEVEL% neq 0 (
    echo.
    echo [FAILED] Docker build failed.
    pause
    exit /b 1
)
del /Q %IMAGE_CONTEXT%\app.jar >nul 2>&1
echo.

echo [5/5] Saving image to file...
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
