param(
  [Parameter(Mandatory=$true)][string]$Target,
  [Parameter(Mandatory=$true)][string]$Next,
  [Parameter(Mandatory=$true)][string]$Backup,
  [int]$VerifySeconds = 30
)

# Wait for current process to exit (graceful shutdown window)
Start-Sleep -Seconds 8

try {
  Copy-Item -LiteralPath $Next -Destination $Target -Force
} catch {
  Write-Output "COPY_FAILED: $_"
  exit 2
}

# If target is a JAR, find java
$java = $null
if ($Target.ToLower().EndsWith('.jar')) {
  try { $java = (Get-Command java -ErrorAction SilentlyContinue).Source } catch {}
  if (-not $java) { Write-Output 'JAVA_NOT_FOUND'; exit 3 }
}

try {
  if ($Target.ToLower().EndsWith('.jar')) {
    $proc = Start-Process -FilePath $java -ArgumentList '-jar', $Target -WorkingDirectory (Split-Path $Target) -PassThru
  } else {
    $proc = Start-Process -FilePath $Target -WorkingDirectory (Split-Path $Target) -PassThru
  }
} catch {
  Write-Output "START_FAILED: $_"
}

Start-Sleep -Seconds $VerifySeconds

if ($proc -eq $null -or $proc.HasExited) {
  Write-Output 'NEW_PROCESS_FAILED'
  try { Copy-Item -LiteralPath $Backup -Destination $Target -Force } catch { Write-Output 'RESTORE_FAILED: ' + $_ }
  try {
    if ($Target.ToLower().EndsWith('.jar')) {
      Start-Process -FilePath $java -ArgumentList '-jar', $Target -WorkingDirectory (Split-Path $Target) -PassThru
    } else {
      Start-Process -FilePath $Target -WorkingDirectory (Split-Path $Target) -PassThru
    }
  } catch {}
  exit 1
}

# Success: remove staged artifact
Remove-Item -LiteralPath $Next -Force -ErrorAction SilentlyContinue
Write-Output 'UPDATE_SUCCESS'
exit 0
