$ErrorActionPreference = 'Continue'

$root = 'C:\Drivers'
$result = [ordered]@{
    computerName = $env:COMPUTERNAME
    exists = Test-Path -LiteralPath $root -PathType Container
    rootEntries = @()
    setupDirectories = @()
    installers = @()
    infFiles = @()
}

if ($result.exists) {
    $result.rootEntries = @(
        Get-ChildItem -LiteralPath $root -Force -ErrorAction SilentlyContinue |
            Select-Object Name, FullName, PSIsContainer, Length, LastWriteTime
    )
    $candidateDirectories = @(
        (Join-Path $root 'Setup'),
        'C:\Setup'
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Container } | Select-Object -Unique
    $result.setupDirectories = @($candidateDirectories)
    $files = @((Get-ChildItem -LiteralPath $root -File -Force -ErrorAction SilentlyContinue); $candidateDirectories | ForEach-Object {
        Get-ChildItem -LiteralPath $_ -File -Force -ErrorAction SilentlyContinue
    })
    $result.installers = @(
        $files |
            Where-Object {
                $_.Extension -in '.exe', '.cmd', '.bat', '.msi', '.ps1' -and
                $_.Name -match 'setup|install|driver|realtek|lan|auto'
            } |
            Select-Object -First 200 FullName, Length, LastWriteTime
    )
    $result.infFiles = @(
        $candidateDirectories | ForEach-Object {
            Get-ChildItem -LiteralPath $_ -File -Filter '*.inf' -Recurse -Force -ErrorAction SilentlyContinue |
            Where-Object Extension -EQ '.inf' |
            Where-Object { $_.FullName -match 'LAN|Network|Realtek|Ethernet|NIC' } |
            Select-Object -First 200 FullName, Length, LastWriteTime
        } |
            Select-Object -First 200
    )
}

$result | ConvertTo-Json -Depth 5 -Compress
