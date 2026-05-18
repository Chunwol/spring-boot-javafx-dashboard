<#
PowerShell 헬퍼 템플릿: win-acme를 다운로드하고 기본 인증서 발급 시도
사용 예: .\win-acme-setup.ps1 -Domain "example.com" -Email "you@example.com" -PfxPassword "changeit"
주의: 관리자로 실행해야 하며 환경에 따라 매개변수를 조정하세요.
#>
param(
  [string]$Domain = "example.com",
  [string]$Email = "you@example.com",
  [string]$PfxPassword = "changeit",
  [string]$OutPfxPath = "",
  [string]$AcmeServer = "",
  [string]$EabKid = "",
  [string]$EabHmacKey = ""
)

function Test-IsAdministrator {
  try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
  } catch {
    return $false
  }
}

function Get-RedactedArgText {
  param([string[]]$ArgList)

  $redacted = New-Object System.Collections.Generic.List[string]
  for ($i = 0; $i -lt $ArgList.Count; $i++) {
    $arg = $ArgList[$i]
    $redacted.Add($arg)
    if ($arg -in @("--pfxpassword", "--eab-key")) {
      if ($i + 1 -lt $ArgList.Count) {
        $redacted.Add("********")
        $i++
      }
    }
  }
  return ($redacted -join ' ')
}

$Tmp = Join-Path $env:TEMP "win-acme"
New-Item -ItemType Directory -Path $Tmp -Force | Out-Null

Write-Host "다운로드 및 압축 해제 준비: $Tmp"
$zip = Join-Path $Tmp "wacs.zip"

function Resolve-WinAcmeZipUrl {
  $apiUrl = "https://api.github.com/repos/win-acme/win-acme/releases/latest"
  try {
    $release = Invoke-RestMethod -Uri $apiUrl -Headers @{ "User-Agent" = "pcagent-winacme-helper" } -ErrorAction Stop
    if ($null -ne $release.assets) {
      $preferred = $release.assets |
        Where-Object { $_.name -match "x64\.trimmed\.zip$" } |
        Select-Object -First 1
      if ($preferred -and $preferred.browser_download_url) {
        return [string]$preferred.browser_download_url
      }

      $fallback = $release.assets |
        Where-Object { $_.name -match "x64.*\.zip$" } |
        Select-Object -First 1
      if ($fallback -and $fallback.browser_download_url) {
        return [string]$fallback.browser_download_url
      }
    }
  } catch {
    Write-Warning "GitHub API로 최신 win-acme 릴리스 조회 실패: $($_.Exception.Message)"
  }

  # API 실패 시 최후의 fallback
  return "https://github.com/win-acme/win-acme/releases/latest/download/win-acme.x64.trimmed.zip"
}

try {
  $releaseUrl = Resolve-WinAcmeZipUrl
  Write-Host "win-acme 다운로드 URL: $releaseUrl"
  Invoke-WebRequest -Uri $releaseUrl -OutFile $zip -UseBasicParsing -ErrorAction Stop
  Expand-Archive -Path $zip -DestinationPath $Tmp -Force
} catch {
  Write-Warning "자동 다운로드 실패: $_. 수동으로 https://www.win-acme.com/ 에서 내려받아 $Tmp에 배치하세요."
}

$wacs = Get-ChildItem $Tmp -Filter wacs.exe -Recurse | Select-Object -First 1
if (-not $wacs) {
  Write-Error "wacs.exe를 찾을 수 없습니다. 수동으로 설치한 후 다시 시도하세요."
  exit 1
}

# 예: 비대화식(manual target, self-host validation, PFX 저장) -- 환경에 따라 옵션 조정 필요
$isAdmin = Test-IsAdministrator
$validationPlugin = "selfhosting"
$validationMode = "http-01"
$webrootPath = ""
$pythonProc = $null

$effectiveEmail = $Email
if (-not $effectiveEmail -or $effectiveEmail.Trim() -eq "") {
  $effectiveEmail = "admin@$Domain"
} elseif ($effectiveEmail -match "@example\.(com|net|org)$") {
  # Let's Encrypt rejects placeholder example domains for account contacts.
  $effectiveEmail = "admin@$Domain"
}

$effectiveAcmeServer = $AcmeServer
if ((-not $effectiveAcmeServer -or $effectiveAcmeServer.Trim() -eq "") -and $EabKid -and $EabHmacKey) {
  $effectiveAcmeServer = "https://acme.zerossl.com/v2/DV90"
}

if (-not $isAdmin) {
  $validationPlugin = "filesystem"
  $webrootPath = Join-Path $Tmp "acme-webroot"
  New-Item -ItemType Directory -Path $webrootPath -Force | Out-Null

  $python = Get-Command python -ErrorAction SilentlyContinue
  if (-not $python) {
    Write-Error "비관리자 모드에서는 filesystem 검증을 위해 python이 필요합니다. python을 설치하거나 관리자 권한으로 실행하세요."
    exit 1
  }

  Write-Host "비관리자 모드 감지: filesystem 검증을 위해 임시 HTTP 서버(80)를 시작합니다."
  $pythonArgs = @("-m", "http.server", "80", "--directory", $webrootPath)
  $pythonProc = Start-Process -FilePath $python.Source -ArgumentList $pythonArgs -PassThru -WindowStyle Hidden
}

$wacsArgs = @(
  "--target", "manual",
  "--host", $Domain,
  "--emailaddress", $effectiveEmail,
  "--validation", $validationPlugin,
  "--validationmode", $validationMode,
  "--store", "pfxfile",
  "--pfxpassword", $PfxPassword,
  "--installation", "none",
  "--accepttos"
)

if ($effectiveAcmeServer -and $effectiveAcmeServer.Trim() -ne "") {
  $wacsArgs += @("--baseuri", $effectiveAcmeServer)
}

if ($EabKid -and $EabHmacKey) {
  $wacsArgs += @("--eab-key-identifier", $EabKid, "--eab-key", $EabHmacKey)
}

if ($validationPlugin -eq "filesystem") {
  $wacsArgs += @("--webroot", $webrootPath)
}

if ($OutPfxPath -and $OutPfxPath -ne "") {
  $outDir = Split-Path $OutPfxPath -Parent
  $outName = [System.IO.Path]::GetFileNameWithoutExtension($OutPfxPath)
  if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
  # win-acme pfxfile store expects directory path; file prefix is provided separately
  $wacsArgs += @("--pfxfilepath", $outDir)
  if ($outName -and $outName -ne "") {
    $wacsArgs += @("--pfxfilename", $outName)
  }
}

Write-Host "wacs 실행: $($wacs.FullName) $(Get-RedactedArgText -ArgList $wacsArgs)"
try {
  $proc = Start-Process -FilePath $wacs.FullName -ArgumentList $wacsArgs -Wait -NoNewWindow -PassThru
  if ($proc.ExitCode -ne 0) {
    Write-Error "win-acme 실행 실패 (exit code=$($proc.ExitCode))"
    exit $proc.ExitCode
  }

  if ($OutPfxPath -and $OutPfxPath -ne "" -and -not (Test-Path $OutPfxPath)) {
    $outDir = Split-Path $OutPfxPath -Parent
    $outName = [System.IO.Path]::GetFileNameWithoutExtension($OutPfxPath)
    $candidate = $null

    if ($outDir -and (Test-Path $outDir)) {
      if ($outName -and $outName -ne "") {
        $candidate = Get-ChildItem -Path $outDir -Filter ("{0}*.pfx" -f $outName) -File -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1
      }

      if (-not $candidate) {
        $candidate = Get-ChildItem -Path $outDir -Filter "*.pfx" -File -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending |
          Select-Object -First 1
      }
    }

    if ($candidate) {
      Copy-Item -Path $candidate.FullName -Destination $OutPfxPath -Force
      Write-Host "PFX 표준 경로 동기화: $OutPfxPath (source=$($candidate.FullName))"
    }
  }
} finally {
  if ($pythonProc -and -not $pythonProc.HasExited) {
    Stop-Process -Id $pythonProc.Id -Force -ErrorAction SilentlyContinue
  }
}

Write-Host "발급 완료 후 생성된 PFX 파일을 PKCS12로서 Spring Boot에 연결하거나, 필요한 경우 JKS로 변환하세요."
Write-Host "예: keytool -importkeystore -srckeystore cert.pfx -srcstoretype PKCS12 -srcstorepass <pass> -destkeystore keystore.jks -deststorepass <pass>"
