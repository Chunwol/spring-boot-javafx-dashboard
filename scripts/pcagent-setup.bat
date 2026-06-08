@echo off
rem PCAgent setup: ProgramData 생성 + config.json 복사
rem WiX CA로 SYSTEM 권한 실행 (InstallFiles 직후)
rem schtask 등록은 하지 않음 - 에이전트가 첫 실행 시 ensureAutostart()로 자가 등록

md "C:\ProgramData\PCAgent" 2>nul
if not exist "C:\ProgramData\PCAgent\config.json" (
    copy /Y "%~dp0config.json" "C:\ProgramData\PCAgent\" >nul 2>&1
)
