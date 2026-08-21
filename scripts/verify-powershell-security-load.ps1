$ErrorActionPreference = 'Stop'

Import-Module Microsoft.PowerShell.Security -Force -ErrorAction Stop
$command = Get-Command Get-AuthenticodeSignature -ErrorAction Stop
$self = Get-AuthenticodeSignature -LiteralPath $PSCommandPath -ErrorAction Stop

[ordered]@{
    computerName = $env:COMPUTERNAME
    securityModuleLoaded = $true
    commandSource = $command.Source
    signatureStatus = [string]$self.Status
} | ConvertTo-Json -Compress
