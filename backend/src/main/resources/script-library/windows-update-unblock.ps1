$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\PCAgent\script-library'
$backupPath = Join-Path $root 'windows-update-policy-backup.json'
$policyPath = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU'

$expectedNoAutoExists = $false
$expectedNoAutoValue = $null
$expectedStartMode = 'Manual'
if (Test-Path -LiteralPath $backupPath) {
    $backup = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
    $expectedNoAutoExists = [bool]$backup.policyValues.NoAutoUpdate.exists
    $expectedNoAutoValue = $backup.policyValues.NoAutoUpdate.value
    $expectedStartMode = [string]$backup.serviceStartMode
    New-Item -Path $policyPath -Force | Out-Null
    foreach ($name in @('NoAutoUpdate', 'AUOptions')) {
        $saved = $backup.policyValues.$name
        if ($saved -and $saved.exists) {
            New-ItemProperty -Path $policyPath -Name $name -PropertyType DWord -Value ([int]$saved.value) -Force | Out-Null
        } else {
            Remove-ItemProperty -Path $policyPath -Name $name -ErrorAction SilentlyContinue
        }
    }
    $startup = switch ([string]$backup.serviceStartMode) {
        'Auto' { 'Automatic' }
        'Disabled' { 'Disabled' }
        default { 'Manual' }
    }
    Set-Service -Name wuauserv -StartupType $startup
    if ($startup -ne 'Disabled') { Start-Service -Name wuauserv -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $backupPath -Force
} else {
    Remove-ItemProperty -Path $policyPath -Name NoAutoUpdate -ErrorAction SilentlyContinue
    Remove-ItemProperty -Path $policyPath -Name AUOptions -ErrorAction SilentlyContinue
    Set-Service -Name wuauserv -StartupType Manual
    Start-Service -Name wuauserv -ErrorAction SilentlyContinue
}

$noAutoExists = $false
$noAutoValue = $null
try { $noAutoValue = (Get-ItemProperty -LiteralPath $policyPath -Name NoAutoUpdate -ErrorAction Stop).NoAutoUpdate; $noAutoExists = $true } catch {}
$startMode = (Get-CimInstance Win32_Service -Filter "Name='wuauserv'").StartMode
$policyVerified = ($noAutoExists -eq $expectedNoAutoExists)
if ($expectedNoAutoExists) { $policyVerified = ($policyVerified -and ([int]$noAutoValue -eq [int]$expectedNoAutoValue)) }
$verified = ($policyVerified -and $startMode -eq $expectedStartMode)
Write-Output ('SCRIPT_LIBRARY_WINDOWS_UPDATE_UNBLOCK verified=' + $verified)
if (-not $verified) { exit 2 }
