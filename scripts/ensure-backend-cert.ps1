<#
Ensure backend certificate for DOMAIN in backend/.env
- If DOMAIN resolves to this machine (public IP or local interface), the script checks the TLS cert served on :443.
- If no cert is served, it will invoke the win-acme helper to request a certificate.
- If a cert is present, it checks days until expiration and runs renewal if within threshold.

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\ensure-backend-cert.ps1 [-EnvFilePath backend/.env] [-RenewDaysThreshold 30] [-WinAcmeScript scripts/win-acme-setup.ps1] [-PfxPassword changeit] [-DryRun]

Notes:
- Requires PowerShell and network access. Uses api.ipify.org to detect public IP (best-effort).
- For local domains like "localhost" or unresolvable domains, the script will skip certificate actions.
#>
param(
  [string]$EnvFilePath = "backend/.env",
  [int]$RenewDaysThreshold = 30,
  [string]$WinAcmeScript = "scripts/win-acme-setup.ps1",
  [string]$PfxPassword = "changeit",
  [switch]$DryRun
)

function Get-EnvMap($path) {
  $kv = @{}
  if (-not (Test-Path $path)) { return $kv }
  Get-Content $path | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)\s*=(.*)$') {
      $k = $matches[1].Trim()
      $v = $matches[2].Trim()
      $kv[$k] = $v
    }
  }
  return $kv
}

try {
  $envKv = Get-EnvMap $EnvFilePath
  $domain = $envKv['DOMAIN']
  $email = $envKv['EMAIL']
  $acmeServer = $envKv['ACME_SERVER']
  $zeroSslEabKid = $envKv['ZEROSSL_EAB_KID']
  $zeroSslEabHmac = $envKv['ZEROSSL_EAB_HMAC_KEY']
} catch {
  Write-Error "Failed to read env file: $_"
  exit 2
}

$scriptRoot = Split-Path -Path $MyInvocation.MyCommand.Definition -Parent
$certsDir = Join-Path $scriptRoot 'certs'
if (-not (Test-Path $certsDir)) { New-Item -ItemType Directory -Path $certsDir -Force | Out-Null }
$metaFile = Join-Path $certsDir 'last-pfx-path.txt'

if (-not $domain -or $domain -eq "" -or $domain -eq "localhost") {
  if (Test-Path $metaFile) { Remove-Item $metaFile -Force -ErrorAction SilentlyContinue }
  Write-Host "DOMAIN not set or is localhost; skipping certificate actions."
  exit 0
}

# Prepare expected PFX path for this domain
$PfxPath = Join-Path $certsDir ("$domain.pfx")

# If a PFX already exists (from previous run), record it for callers
if (Test-Path $PfxPath) {
  Write-Host "Found existing PFX at $PfxPath"
  try { Set-Content -Path $metaFile -Value (Resolve-Path $PfxPath).Path -Force } catch { }
}

$resolved = @()
try {
  $addrs = [System.Net.Dns]::GetHostAddresses($domain)
  foreach ($a in $addrs) { if ($a.AddressFamily -eq 'InterNetwork') { $resolved += $a.IPAddressToString } }
} catch {
  Write-Warning "DNS resolution failed for ${domain}: $_"
}

# Get local IPv4 addresses (excluding loopback)
$localIPs = @()
try {
  $netips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -ne '127.0.0.1' }
  foreach ($n in $netips) { $localIPs += $n.IPAddress }
} catch {
  # fallback
  try {
    $hostIPs = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) | Where-Object { $_.AddressFamily -eq 'InterNetwork' } | ForEach-Object { $_.IPAddressToString }
    $localIPs += $hostIPs
  } catch { }
}

# Public IP (best-effort)
$publicIP = $null
try {
  $publicIP = (Invoke-RestMethod -Uri "https://api.ipify.org" -UseBasicParsing -TimeoutSec 5).Trim()
} catch { }

$pointsToThis = $false
foreach ($r in $resolved) {
  if ($localIPs -contains $r -or $r -eq $publicIP) { $pointsToThis = $true; break }
}

if (-not $pointsToThis) {
  if (Test-Path $metaFile) { Remove-Item $metaFile -Force -ErrorAction SilentlyContinue }
  Write-Host "Domain $domain does not resolve to this machine (resolved: $($resolved -join ', ')); no certificate action."
  exit 0
}

Write-Host "Domain $domain resolves to this machine. Checking TLS certificate..."

function Get-RemoteCertificate($targetHost, $port=443, $timeoutMs=5000) {
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect($targetHost, $port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($timeoutMs)) { $tcp.Close(); return $null }
    $tcp.EndConnect($iar)
    $stream = $tcp.GetStream()
    $ssl = New-Object System.Net.Security.SslStream($stream, $false, ({ $true }))
    $ssl.AuthenticateAsClient($targetHost)
    $raw = $ssl.RemoteCertificate
    $ssl.Close(); $tcp.Close()
    if (-not $raw) { return $null }
    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2([byte[]]$raw.GetRawCertData())
    return $cert
  } catch {
    return $null
  }
}

$cert = Get-RemoteCertificate $domain 443 5000
if (-not $cert) {
  Write-Host "No TLS certificate served on ${domain}:443 or connection failed."
  if ($DryRun) {
    Write-Host "[DryRun] Would run $WinAcmeScript to obtain certificate for $domain"
    exit 0
  }
  if (-not (Test-Path $WinAcmeScript)) {
    Write-Warning "win-acme script not found at $WinAcmeScript. Please provide a method to obtain certificate."
    exit 2
  }
  Write-Host "Invoking win-acme helper to request certificate for $domain..."
  $issueArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $WinAcmeScript,
    "-Domain", $domain,
    "-Email", $email,
    "-PfxPassword", $PfxPassword,
    "-OutPfxPath", $PfxPath
  )
  if ($acmeServer -and $acmeServer -ne "") {
    $issueArgs += @("-AcmeServer", $acmeServer)
  }
  if ($zeroSslEabKid -and $zeroSslEabKid -ne "" -and $zeroSslEabHmac -and $zeroSslEabHmac -ne "") {
    $issueArgs += @("-EabKid", $zeroSslEabKid, "-EabHmacKey", $zeroSslEabHmac)
  }
  & powershell @issueArgs
  if (Test-Path $PfxPath) {
    Write-Host "PFX created at $PfxPath"
    try { Set-Content -Path $metaFile -Value (Resolve-Path $PfxPath).Path -Force } catch { }
    exit 0
  }
  if (Test-Path $metaFile) { Remove-Item $metaFile -Force -ErrorAction SilentlyContinue }
  exit $LASTEXITCODE
}

$notAfter = $cert.NotAfter
$daysLeft = [math]::Floor(($notAfter - (Get-Date)).TotalDays)
Write-Host ("Certificate for {0} expires on {1} ({2} days left)" -f $domain, $notAfter, $daysLeft)

if ($daysLeft -le $RenewDaysThreshold) {
  Write-Host "Certificate is due to expire within threshold ($RenewDaysThreshold days). Renewing..."
  if ($DryRun) { Write-Host "[DryRun] Would run $WinAcmeScript to renew certificate for $domain"; exit 0 }
  if (-not (Test-Path $WinAcmeScript)) { Write-Warning "win-acme script not found at $WinAcmeScript"; exit 2 }
  $renewArgs = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $WinAcmeScript,
    "-Domain", $domain,
    "-Email", $email,
    "-PfxPassword", $PfxPassword,
    "-OutPfxPath", $PfxPath
  )
  if ($acmeServer -and $acmeServer -ne "") {
    $renewArgs += @("-AcmeServer", $acmeServer)
  }
  if ($zeroSslEabKid -and $zeroSslEabKid -ne "" -and $zeroSslEabHmac -and $zeroSslEabHmac -ne "") {
    $renewArgs += @("-EabKid", $zeroSslEabKid, "-EabHmacKey", $zeroSslEabHmac)
  }
  & powershell @renewArgs
  if (Test-Path $PfxPath) {
    Write-Host "PFX created/renewed at $PfxPath"
    try { Set-Content -Path $metaFile -Value (Resolve-Path $PfxPath).Path -Force } catch { }
    exit 0
  }
  if (Test-Path $metaFile) { Remove-Item $metaFile -Force -ErrorAction SilentlyContinue }
  exit $LASTEXITCODE
} else {
  if (Test-Path $PfxPath) {
    try { Set-Content -Path $metaFile -Value (Resolve-Path $PfxPath).Path -Force } catch { }
  } else {
    if (Test-Path $metaFile) { Remove-Item $metaFile -Force -ErrorAction SilentlyContinue }
  }
  Write-Host "Certificate OK; no action required."
  exit 0
}
