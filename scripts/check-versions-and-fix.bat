@echo off
setlocal enabledelayedexpansion

REM Check runtime/tool versions and apply safe local fixes.
REM
REM ====================================================
REM 맞춰야 하는 버전 (Required versions)
REM   Java  : 25 LTS  (openjdk 25.0.x)
REM   Gradle: 8.14.4  (gradlew wrapper 포함, 별도 설치 불필요)
REM   Node  : 24.x 이상 (web-frontend)
REM   Spring Boot: 4.x (backend/build.gradle)
REM ====================================================
REM
REM Usage:
REM   scripts\check-versions-and-fix.bat
REM   scripts\check-versions-and-fix.bat check
REM   scripts\check-versions-and-fix.bat fix

set "MODE=%~1"
if "%MODE%"=="" set "MODE=check"

if /I not "%MODE%"=="check" if /I not "%MODE%"=="fix" (
  echo [FAIL] Unknown mode: %MODE% ^(allowed: check^|fix^)
  exit /b 1
)

set "ROOT=%~dp0.."
pushd "%ROOT%" >nul

set /a FAIL_COUNT=0
set /a WARN_COUNT=0

echo.
echo ============================================================
echo Version Check ^& Safe Fix
echo Mode: %MODE%
echo Root: %CD%
echo ============================================================

call :check_java
call :check_gradle_wrapper
call :check_optional_tools
call :check_agent_config_file

if /I "%MODE%"=="fix" (
  echo.
  echo Applying safe fixes...
  call :fix_config_json
)

echo.
echo ============================================================
echo Summary: FAIL=%FAIL_COUNT% WARN=%WARN_COUNT%
echo ============================================================

popd >nul

if %FAIL_COUNT% GTR 0 (
  echo Result: FAILED
  if /I not "%NO_PAUSE%"=="1" pause
  exit /b 1
)

echo Result: OK
if /I not "%NO_PAUSE%"=="1" pause
exit /b 0

:pass
echo [PASS] %~1
goto :eof

:warn
echo [WARN] %~1
set /a WARN_COUNT+=1
goto :eof

:fail
echo [FAIL] %~1
set /a FAIL_COUNT+=1
goto :eof

:check_java
where java >nul 2>&1
if errorlevel 1 (
  call :fail "java not found in PATH"
  goto :eof
)

set "JAVA_VER_LINE="
for /f "usebackq delims=" %%i in (`java -version 2^>^&1`) do (
  if not defined JAVA_VER_LINE set "JAVA_VER_LINE=%%i"
)

set "JAVA_VER="
for /f "tokens=3" %%v in ('java -version 2^>^&1') do (
  if not defined JAVA_VER set "JAVA_VER=%%~v"
)

if not defined JAVA_VER (
  call :warn "Could not parse java version: !JAVA_VER_LINE!"
  goto :eof
)

set "JAVA_MAJOR="
for /f "tokens=1 delims=." %%m in ("!JAVA_VER!") do set "JAVA_MAJOR=%%m"

if not defined JAVA_MAJOR (
  call :warn "Could not parse java major number: !JAVA_VER_LINE!"
  goto :eof
)

set /a MAJOR_NUM=!JAVA_MAJOR! 2>nul
if errorlevel 1 (
  call :warn "Could not parse java major number: !JAVA_VER_LINE!"
  goto :eof
)

if !MAJOR_NUM! LSS 25 (
  call :fail "Java 25+ required. Current: !JAVA_VER_LINE!"
) else (
  call :pass "Java OK: !JAVA_VER_LINE!"
)
goto :eof

:check_gradle_wrapper
if not exist "gradlew.bat" (
  call :fail "gradlew.bat not found"
  goto :eof
)

call gradlew.bat --version >nul 2>&1
if errorlevel 1 (
  call :fail "Gradle wrapper failed to run"
) else (
  call :pass "Gradle wrapper runnable"
)
goto :eof

:check_optional_tools
where pwsh >nul 2>&1
if errorlevel 1 (
  call :warn "pwsh not found (optional)"
) else (
  call :pass "pwsh found (optional for cross-platform scripts)"
)
goto :eof

:check_agent_config_file
if exist "scripts\config.json" (
  call :pass "scripts\config.json exists"
) else (
  call :warn "scripts\config.json missing"
)
goto :eof

:fix_config_json
if exist "scripts\config.json" (
  call :pass "scripts\config.json already present"
  goto :eof
)

if exist "config.json" (
  copy /Y "config.json" "scripts\config.json" >nul
  if errorlevel 1 (
    call :fail "Failed to copy config.json -> scripts\config.json"
  ) else (
    call :pass "Copied config.json -> scripts\config.json"
  )
  goto :eof
)

(
  echo {
  echo   "agentToken" : "",
  echo   "githubOwner" : "",
  echo   "githubRepo" : "",
  echo   "serverUrl" : "http://127.0.0.1:8888",
  echo   "heartbeatIntervalSec" : 120,
  echo   "installKey" : "INST-CHANGE-ME-BEFORE-DEPLOY",
  echo   "jitterSec" : 30
  echo }
) > "scripts\config.json"

if errorlevel 1 (
  call :fail "Failed to create default scripts\config.json"
) else (
  call :pass "Created default scripts\config.json"
)
goto :eof
