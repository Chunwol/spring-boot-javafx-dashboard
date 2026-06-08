# PCAgent MSI 첫 설치 설정 스크립트
# WiX 커스텀 액션(SYSTEM 계정)으로 InstallFinalize 후 실행됨
# JVM 없이 PowerShell만으로 ProgramData 초기화 + schtasks 등록

param()
$ErrorActionPreference = 'SilentlyContinue'

try {
    # PSScriptRoot = C:\Program Files\PCAgent\app
    $appDir     = $PSScriptRoot
    $installDir = Split-Path -Parent $appDir
    $exePath    = Join-Path $installDir "PCAgent.exe"
    if (!(Test-Path $exePath)) { $exePath = "C:\Program Files\PCAgent\PCAgent.exe" }

    # 1. ProgramData 디렉토리 생성
    $dataDir = "C:\ProgramData\PCAgent"
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

    # 2. config.json 복사 (없을 때만)
    $src = Join-Path $appDir "config.json"
    $dst = Join-Path $dataDir "config.json"
    if ((Test-Path $src) -and !(Test-Path $dst)) {
        Copy-Item $src $dst -Force
    }

    # 3. schtasks 등록 (부팅 시 SYSTEM으로 자동 실행)
    $null = schtasks /Create /TN "PCAgent" /TR "`"$exePath`"" /SC ONSTART /RU SYSTEM /RL HIGHEST /F 2>&1

    # 4. 에이전트 즉시 시작 (5초 후 — MSI 트랜잭션 완료 대기)
    Start-Sleep 5
    $null = schtasks /Run /TN "PCAgent" 2>&1

} catch {
    # 오류 무시 — MSI 설치 실패 방지
}
