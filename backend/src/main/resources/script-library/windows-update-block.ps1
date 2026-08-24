$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\PCAgent\script-library'
$backupPath = Join-Path $root 'windows-update-policy-backup.json'
$policyPath = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU'

New-Item -ItemType Directory -Path $root -Force | Out-Null

if (-not (Test-Path -LiteralPath $backupPath)) {
    $values = @{}
    foreach ($name in @('NoAutoUpdate', 'AUOptions')) {
        $exists = $false
        $value = $null
        try {
            $value = (Get-ItemProperty -LiteralPath $policyPath -Name $name -ErrorAction Stop).$name
            $exists = $true
        } catch {}
        $values[$name] = @{ exists = $exists; value = $value }
    }
    $service = Get-CimInstance Win32_Service -Filter "Name='wuauserv'"
    @{
        policyValues = $values
        serviceStartMode = if ($service) { [string]$service.StartMode } else { 'Manual' }
        createdAt = (Get-Date).ToString('o')
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $backupPath -Encoding UTF8
}

New-Item -Path $policyPath -Force | Out-Null
New-ItemProperty -Path $policyPath -Name NoAutoUpdate -PropertyType DWord -Value 1 -Force | Out-Null
New-ItemProperty -Path $policyPath -Name AUOptions -PropertyType DWord -Value 2 -Force | Out-Null

Stop-Service -Name wuauserv -Force -ErrorAction SilentlyContinue
Set-Service -Name wuauserv -StartupType Disabled

$noAuto = (Get-ItemProperty -LiteralPath $policyPath -Name NoAutoUpdate).NoAutoUpdate
$auOptions = (Get-ItemProperty -LiteralPath $policyPath -Name AUOptions).AUOptions
$startMode = (Get-CimInstance Win32_Service -Filter "Name='wuauserv'").StartMode
$verified = ($noAuto -eq 1 -and $auOptions -eq 2 -and $startMode -eq 'Disabled')
Write-Output ('SCRIPT_LIBRARY_WINDOWS_UPDATE_BLOCK verified=' + $verified)
if (-not $verified) { exit 2 }
