$ErrorActionPreference = 'Stop'

function Get-ProcessSnapshot([string[]]$Names) {
    $rows = foreach ($name in $Names) {
        Get-Process -Name $name -ErrorAction SilentlyContinue | ForEach-Object {
            $started = $null
            $responding = $null
            try { $started = $_.StartTime.ToString('s') } catch {}
            try { $responding = $_.Responding } catch {}
            [ordered]@{
                name = $_.ProcessName
                id = $_.Id
                session = $_.SessionId
                started = $started
                responding = $responding
            }
        }
    }
    @($rows)
}

function Get-RecentEvents {
    $since = (Get-Date).AddHours(-12)
    $providers = @(
        'Application Error', 'Application Hang', 'Windows Error Reporting',
        'Microsoft-Windows-Winlogon', 'Microsoft-Windows-User Profiles Service',
        'Microsoft-Windows-Dwm-Core', 'Display', 'igfx', 'igfxCUIService2.0.0.0',
        'nvlddmkm', 'amdkmdag'
    )
    $events = Get-WinEvent -FilterHashtable @{ LogName = 'Application','System'; StartTime = $since; Level = 1,2,3 } -ErrorAction SilentlyContinue |
        Where-Object { $providers -contains $_.ProviderName -or $_.Message -match '(?i)explorer\.exe|dwm\.exe|black screen' } |
        Sort-Object TimeCreated -Descending |
        Select-Object -First 16
    @($events | ForEach-Object {
        [ordered]@{
            time = $_.TimeCreated.ToString('s')
            log = $_.LogName
            provider = $_.ProviderName
            id = $_.Id
            message = if ($_.Message) { ($_.Message -replace '\s+', ' ').Trim().Substring(0, [Math]::Min(260, ($_.Message -replace '\s+', ' ').Trim().Length)) } else { $null }
        }
    })
}

$winlogon = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
$shell = (Get-ItemProperty -LiteralPath $winlogon -ErrorAction Stop).Shell
$userinit = (Get-ItemProperty -LiteralPath $winlogon -ErrorAction Stop).Userinit
$sessions = @((quser.exe 2>&1 | ForEach-Object { [string]$_ }) -join ' | ')
$video = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | ForEach-Object {
    $driverDate = $null
    if ($_.DriverDate) { $driverDate = $_.DriverDate.ToString('s') }
    [ordered]@{
        name = $_.Name
        status = $_.Status
        driverVersion = $_.DriverVersion
        driverDate = $driverDate
    }
})

[ordered]@{
    computer = $env:COMPUTERNAME
    collectedAt = (Get-Date).ToString('s')
    bootTime = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToString('s')
    sessions = $sessions
    winlogon = [ordered]@{ shell = $shell; userinit = $userinit }
    processes = Get-ProcessSnapshot @('explorer', 'dwm', 'sihost', 'ShellExperienceHost', 'StartMenuExperienceHost', 'userinit', 'winlogon', 'SCMateClient')
    video = $video
    recentEvents = Get-RecentEvents
} | ConvertTo-Json -Depth 6 -Compress
