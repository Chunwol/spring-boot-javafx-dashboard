$ErrorActionPreference = 'Continue'

$path = 'HKLM:/SOFTWARE/WOW6432Node/SCMATE'
$values = if (Test-Path $path) { Get-ItemProperty $path } else { $null }
$service = Get-Service -Name ShdServ -ErrorAction SilentlyContinue
$drivers = @(Get-CimInstance Win32_SystemDriver -ErrorAction SilentlyContinue |
    Where-Object Name -In 'Shdbus', 'Shield', 'Shieldm')

[ordered]@{
    computerName = $env:COMPUTERNAME
    installed = $null -ne $values
    version = $values.SCMateClientVersion
    serverHost = $values.SCMateServerHOST
    serverPort = $values.SCMateServerPORT
    restore = $values.SCMateRestore
    autoReset = $values.SCMateAutoReset
    restoreSec = $values.RestoreSec
    localAuth = $values.LocalAuthYN
    serviceRunning = $service.Status -eq 'Running'
    bootDriversRunning = @($drivers | Where-Object State -EQ 'Running').Count
} | ConvertTo-Json -Compress
